"""
Local Silero TTS HTTP API for TTS-Story-Helper.

Run (after venv + pip install -r requirements.txt):
  python -m uvicorn main:app --host 127.0.0.1 --port 8765

First request downloads the model via torch.hub (needs network once).

Dependencies: omegaconf + PyYAML (hub / models.yml); scipy (v5 packaged model imports scipy.signal).
"""
from __future__ import annotations

import io
import logging
import re
import traceback
import wave
import xml.sax.saxutils as xml_esc

import numpy as np
import torch
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("local_tts")

app = FastAPI(title="Local Silero TTS", version="1.0.0")

# Same mapping as Kotlin yandexVoiceIdToSileroSpeaker (for curl / other clients).
YANDEX_TO_SILERO: dict[str, str] = {
    "alena": "baya",
    "filipp": "aidar",
    "ermil": "eugene",
    "jane": "xenia",
    "omazh": "xenia",
    "zahar": "aidar",
    "dasha": "baya",
    "julia": "kseniya",
    "lera": "baya",
    "masha": "xenia",
    "marina": "xenia",
    "alexander": "aidar",
    "kirill": "eugene",
    "anton": "aidar",
    "madi_ru": "aidar",
    "saule_ru": "kseniya",
    "zamira_ru": "baya",
    "zhanar_ru": "baya",
    "yulduz_ru": "xenia",
}

# Cyrillic + punctuation Silero v5_ru_tacotron knows; everything else → space (avoids KeyError on '^', emoji, Latin…).
_SAFE_TTS_EXTRA = (
    set(' +.,!?;:0123456789-()«»"\'')
    | set("\n\r\t\u00ab\u00bb\u2014\u2013")
)

_model: torch.nn.Module | None = None
_loaded_model_id: str | None = None


def _load_model(model_id: str) -> torch.nn.Module:
    global _model, _loaded_model_id
    if _model is not None and _loaded_model_id == model_id:
        return _model
    device = torch.device("cpu")
    torch.set_num_threads(4)
    model, _example = torch.hub.load(
        repo_or_dir="snakers4/silero-models",
        model="silero_tts",
        language="ru",
        speaker=model_id,
        trust_repo=True,
    )
    model.to(device)
    _model = model
    _loaded_model_id = model_id
    return model


class SynthesizeRequest(BaseModel):
    text: str
    speaker: str = "baya"
    sample_rate: int = Field(default=48000, ge=8000, le=48000)
    model_id: str = "v5_ru"
    # Same semantics as desktop app (Yandex-like); mapped to SSML prosody for Silero v5.
    speed: float = Field(default=1.0, ge=0.1, le=3.0)
    pitch_shift: float = Field(default=0.0, ge=-1000.0, le=1000.0)


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.post("/synthesize")
def synthesize(req: SynthesizeRequest) -> Response:
    text = _sanitize_v5_ru_tts_text(req.text)
    if not text:
        # Multi-chunk client: a slice may be only Latin/^/emoji — still need a valid WAV for merge.
        log.debug("Empty after sanitize; returning short silence (sample_rate=%s)", req.sample_rate)
        return Response(
            content=_silence_wav_bytes(int(req.sample_rate), duration_ms=120),
            media_type="audio/wav",
        )
    try:
        model = _load_model(req.model_id)
    except Exception as e:
        log.error("Model load failed:\n%s", traceback.format_exc())
        raise HTTPException(status_code=503, detail=f"model load failed: {e}") from e
    try:
        raw_sp = req.speaker.strip() or "baya"
        mapped = _map_yandex_speaker(raw_sp)
        speaker = _resolve_speaker(model, mapped)
        if raw_sp.lower() != speaker.lower():
            log.debug("Speaker client=%r -> silero=%r", raw_sp, speaker)
        ssml = _build_ssml_if_needed(text, req.speed, req.pitch_shift)
        if ssml is not None:
            audio = model.apply_tts(
                ssml_text=ssml,
                speaker=speaker,
                sample_rate=req.sample_rate,
            )
        else:
            audio = model.apply_tts(
                text=text,
                speaker=speaker,
                sample_rate=req.sample_rate,
            )
        pcm_bytes = _silero_audio_to_wav_pcm16(audio)
    except Exception as e:
        log.error("Synthesis failed:\n%s", traceback.format_exc())
        raise HTTPException(status_code=500, detail=f"synthesis failed: {e}") from e

    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(int(req.sample_rate))
        wf.writeframes(pcm_bytes)

    return Response(content=buf.getvalue(), media_type="audio/wav")


def _speed_to_ssml_rate(speed: float) -> str | None:
    """Silero v5: x-slow, slow, fast, x-fast (see examples_tts.ipynb)."""
    if speed < 0.75:
        return "x-slow"
    if speed < 0.95:
        return "slow"
    if speed > 1.35:
        return "x-fast"
    if speed > 1.05:
        return "fast"
    return None


def _pitch_shift_to_ssml_pitch(pitch_shift: float) -> str | None:
    """Map desktop slider (-1000..1000) to Silero pitch=\"x-low\" / \"x-high\"."""
    if pitch_shift <= -120:
        return "x-low"
    if pitch_shift >= 120:
        return "x-high"
    return None


def _build_ssml_if_needed(text: str, speed: float, pitch_shift: float) -> str | None:
    rate = _speed_to_ssml_rate(speed)
    pitch = _pitch_shift_to_ssml_pitch(pitch_shift)
    if rate is None and pitch is None:
        return None
    inner = xml_esc.escape(text)
    attrs: list[str] = []
    if rate is not None:
        attrs.append(f'rate="{rate}"')
    if pitch is not None:
        attrs.append(f'pitch="{pitch}"')
    prosody = f'<prosody {" ".join(attrs)}>{inner}</prosody>'
    return f"<speak>{prosody}</speak>"


def _silence_wav_bytes(sample_rate: int, duration_ms: int = 120) -> bytes:
    n = max(1, int(sample_rate * duration_ms / 1000))
    pcm = np.zeros(n, dtype=np.int16).tobytes()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm)
    return buf.getvalue()


def _map_yandex_speaker(requested: str) -> str:
    key = requested.strip().lower()
    return YANDEX_TO_SILERO.get(key, requested.strip())


def _sanitize_v5_ru_tts_text(text: str) -> str:
    out: list[str] = []
    for c in text:
        o = ord(c)
        if 0x0400 <= o <= 0x04FF:
            out.append(c)
        elif c in "\n\r\t":
            out.append(" ")
        elif c in _SAFE_TTS_EXTRA:
            out.append(c)
        else:
            out.append(" ")
    s = re.sub(r" +", " ", "".join(out)).strip()
    return s


def _resolve_speaker(model: torch.nn.Module, requested: str) -> str:
    """Ensure speaker id exists on the loaded model (case-insensitive)."""
    raw = getattr(model, "speakers", None)
    if not raw:
        return requested.strip() or "baya"
    valid = [str(s) for s in raw]
    canon = {s.lower(): s for s in valid}
    key = (requested.strip() or "baya").lower()
    if key in canon:
        return canon[key]
    fallback = "baya" if "baya" in canon else valid[0]
    log.warning(
        "Unknown speaker %r for this model; using %r. Valid: %s",
        requested,
        fallback,
        ", ".join(valid),
    )
    return fallback


def _silero_audio_to_wav_pcm16(audio: object) -> bytes:
    """Silero v5 may return torch.Tensor or numpy.ndarray (depends on torch / package)."""
    if isinstance(audio, (list, tuple)):
        if not audio:
            raise ValueError("empty audio sequence from model")
        audio = audio[0]

    if isinstance(audio, torch.Tensor):
        arr = audio.detach().cpu().numpy()
    else:
        arr = np.asarray(audio)

    arr = np.reshape(arr, (-1,))
    if arr.size == 0:
        raise ValueError("empty audio buffer")

    if np.issubdtype(arr.dtype, np.integer):
        pcm = np.clip(arr, -32768, 32767).astype(np.int16)
        return pcm.tobytes()

    arr = arr.astype(np.float64, copy=False)
    peak = float(np.max(np.abs(arr))) or 1.0
    if peak > 1.0:
        arr = arr / peak
    pcm = np.clip(arr * 32767.0, -32768.0, 32767.0).astype(np.int16)
    return pcm.tobytes()

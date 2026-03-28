package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.LocalTtsSettings
import by.tigre.speechhelper.domain.SynthesisBackend
import by.tigre.speechhelper.domain.VoiceSettings
import by.tigre.speechhelper.domain.yandexVoiceIdToSileroSpeaker
import kotlinx.coroutines.flow.Flow

object SpeechSynthesizer {

    fun synthesize(
        text: String,
        voiceSettings: VoiceSettings,
        outputFormat: String,
        backend: SynthesisBackend,
        localSettings: LocalTtsSettings,
        cloudToken: String,
    ): Flow<SynthesisResult> = when (backend) {
        SynthesisBackend.Cloud ->
            SpeechKitApi.synthesize(
                text = text,
                voice = voiceSettings.voice,
                role = voiceSettings.role.ifBlank { null },
                speed = voiceSettings.speed,
                pitchShift = voiceSettings.pitchShift,
                format = outputFormat,
                token = cloudToken,
            )
        SynthesisBackend.Local ->
            LocalTtsApi.synthesize(
                text = text,
                speaker = yandexVoiceIdToSileroSpeaker(voiceSettings.voice),
                settings = localSettings,
                speed = voiceSettings.speed,
                pitchShift = voiceSettings.pitchShift,
                outputFormat = outputFormat,
            )
    }
}

package by.tigre.speechhelper.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Concatenates PCM WAV files that share the same fmt chunk (same rate / channels / encoding).
 */
object WavMerge {

    fun merge(parts: List<ByteArray>): ByteArray {
        require(parts.isNotEmpty()) { "No WAV parts to merge" }
        if (parts.size == 1) return parts[0]

        val parsed = parts.map { parseWav(it) }
        val refFmt = parsed[0].fmtBody
        for (i in 1 until parsed.size) {
            if (!parsed[i].fmtBody.contentEquals(refFmt)) {
                throw IllegalArgumentException("WAV format mismatch between chunks (chunk 0 vs $i)")
            }
        }
        val totalPcm = parsed.fold(ByteArray(0)) { acc, p -> acc + p.pcmData }
        return buildWav(refFmt, totalPcm)
    }

    private data class Parsed(val fmtBody: ByteArray, val pcmData: ByteArray)

    private fun parseWav(bytes: ByteArray): Parsed {
        require(bytes.size >= 12) { "WAV too small" }
        require(chunkId(bytes, 0) == "RIFF") { "Not a RIFF file" }
        require(chunkId(bytes, 8) == "WAVE") { "Not a WAVE file" }

        var offset = 12
        var fmtBody: ByteArray? = null
        val pcmChunks = mutableListOf<ByteArray>()

        while (offset + 8 <= bytes.size) {
            val id = chunkId(bytes, offset)
            val size = readLeInt(bytes, offset + 4)
            val dataStart = offset + 8
            val dataEnd = dataStart + size
            require(dataEnd <= bytes.size) { "Corrupt WAV chunk $id" }

            when (id) {
                "fmt " -> fmtBody = bytes.copyOfRange(dataStart, dataEnd)
                "data" -> pcmChunks.add(bytes.copyOfRange(dataStart, dataEnd))
            }

            offset = dataEnd + (size and 1)
        }

        val fmt = fmtBody ?: throw IllegalArgumentException("WAV missing fmt chunk")
        if (pcmChunks.isEmpty()) throw IllegalArgumentException("WAV missing data chunk")
        val pcm = pcmChunks.fold(ByteArray(0)) { a, b -> a + b }
        return Parsed(fmt, pcm)
    }

    private fun buildWav(fmtBody: ByteArray, pcmData: ByteArray): ByteArray {
        val fmtPad = fmtBody.size and 1
        val dataPad = pcmData.size and 1
        val fmtSub = 8 + fmtBody.size + fmtPad
        val dataSub = 8 + pcmData.size + dataPad
        val riffChunkSize = 4 + fmtSub + dataSub

        val out = ByteArrayOutputStream(12 + fmtSub + dataSub)
        out.writeAscii("RIFF")
        out.writeLeInt(riffChunkSize)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeLeInt(fmtBody.size)
        out.write(fmtBody)
        if (fmtPad == 1) out.write(0)
        out.writeAscii("data")
        out.writeLeInt(pcmData.size)
        out.write(pcmData)
        if (dataPad == 1) out.write(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeLeInt(v: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(buf)
    }

    private fun chunkId(bytes: ByteArray, offset: Int): String {
        return Charsets.US_ASCII.decode(ByteBuffer.wrap(bytes, offset, 4)).toString()
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}

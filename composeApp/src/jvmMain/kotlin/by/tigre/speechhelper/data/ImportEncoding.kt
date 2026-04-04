package by.tigre.speechhelper.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Декодирует байты FB2/XML/HTML с учётом объявления encoding в прологе (например windows-1251)
 * и UTF-8 BOM. Иначе — UTF-8.
 */
internal fun decodeBytesWithDeclaredCharset(bytes: ByteArray): String {
    val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val offset: Int
    val forceUtf8: Boolean
    when {
        bytes.size >= 3 &&
            bytes[0] == utf8Bom[0] &&
            bytes[1] == utf8Bom[1] &&
            bytes[2] == utf8Bom[2] -> {
            offset = 3
            forceUtf8 = true
        }
        else -> {
            offset = 0
            forceUtf8 = false
        }
    }
    if (forceUtf8) {
        return String(bytes, offset, bytes.size - offset, StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
    }
    val probeLen = minOf(bytes.size - offset, 4096)
    val probe = String(bytes, offset, probeLen, StandardCharsets.ISO_8859_1)
    val charset = sniffEncodingFromProlog(probe) ?: StandardCharsets.UTF_8
    return String(bytes, offset, bytes.size - offset, charset)
        .removePrefix("\uFEFF")
}

private fun sniffEncodingFromProlog(probe: String): Charset? {
    Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(probe)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { name ->
            try {
                Charset.forName(name)
            } catch (_: Exception) {
                null
            }
        }
        ?.let { return it }

    Regex("""(?i)<meta[^>\n]+charset\s*=\s*["']?([^"'\s/>;]+)""")
        .find(probe)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { name ->
            try {
                return Charset.forName(name)
            } catch (_: Exception) {
                return@let
            }
        }

    return null
}

package com.example.engine

import com.example.model.FileCategory
import com.example.model.FileHealthState
import java.io.File
import java.io.InputStream
import kotlin.math.min

object FileCarver {

    data class CarveResult(
        val category: FileCategory,
        val detectedExtension: String,
        val mimeType: String,
        val magicSignature: String,
        val isHeaderValid: Boolean,
        val isFooterValid: Boolean,
        val healthScore: Int,
        val healthState: FileHealthState,
        val corruptionReason: String?,
        val hexSnippet: String
    )

    // Common magic byte headers
    private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    private val GIF_HEADER_87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF_HEADER_89 = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val PDF_HEADER = "%PDF".toByteArray(Charsets.US_ASCII)
    private val ZIP_HEADER = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())
    private val RAR_HEADER = byteArrayOf(0x52.toByte(), 0x61.toByte(), 0x72.toByte(), 0x21.toByte())
    private val SEVEN_Z_HEADER = byteArrayOf(0x37.toByte(), 0x7A.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27.toByte(), 0x1C.toByte())
    private val MP3_ID3 = "ID3".toByteArray(Charsets.US_ASCII)
    private val FLAC_HEADER = "fLaC".toByteArray(Charsets.US_ASCII)
    private val OGG_HEADER = "OggS".toByteArray(Charsets.US_ASCII)
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val SQLITE_HEADER_ALT = "Sqlite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun inspectFile(file: File): CarveResult {
        if (!file.exists() || file.length() == 0L) {
            return CarveResult(
                category = FileCategory.OTHER,
                detectedExtension = file.extension.ifEmpty { "bin" },
                mimeType = "application/octet-stream",
                magicSignature = "EMPTY",
                isHeaderValid = false,
                isFooterValid = false,
                healthScore = 0,
                healthState = FileHealthState.CORRUPTED,
                corruptionReason = "Zero-byte or non-existent file",
                hexSnippet = "[Empty File]"
            )
        }

        val headerSize = min(file.length().toInt(), 512)
        val headerBytes = ByteArray(headerSize)
        try {
            file.inputStream().use { input ->
                input.read(headerBytes)
            }
        } catch (e: Exception) {
            return CarveResult(
                category = FileCategory.OTHER,
                detectedExtension = file.extension.ifEmpty { "bin" },
                mimeType = "application/octet-stream",
                magicSignature = "UNREADABLE",
                isHeaderValid = false,
                isFooterValid = false,
                healthScore = 0,
                healthState = FileHealthState.CORRUPTED,
                corruptionReason = "IO error: ${e.localizedMessage}",
                hexSnippet = "[Unreadable]"
            )
        }

        // Tail inspection for footers
        val tailSize = min(file.length().toInt(), 1024)
        val tailBytes = ByteArray(tailSize)
        if (file.length() > 0) {
            try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val seekPos = file.length() - tailSize
                    raf.seek(seekPos)
                    raf.read(tailBytes)
                }
            } catch (_: Exception) {}
        }

        return analyzeBytes(headerBytes, tailBytes, file.extension, file.length())
    }

    fun analyzeBytes(
        header: ByteArray,
        tail: ByteArray,
        declaredExtension: String,
        fileSize: Long
    ): CarveResult {
        val hexDump = formatHexDump(header)

        // 1. JPEG
        if (startsWith(header, JPEG_HEADER)) {
            val hasEoi = containsSequence(tail, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            val score = if (hasEoi) 98 else 75
            val state = if (hasEoi) FileHealthState.EXCELLENT else FileHealthState.REPAIRABLE
            return CarveResult(
                category = FileCategory.PHOTO,
                detectedExtension = "jpg",
                mimeType = "image/jpeg",
                magicSignature = "FF D8 FF (JPEG)",
                isHeaderValid = true,
                isFooterValid = hasEoi,
                healthScore = score,
                healthState = state,
                corruptionReason = if (!hasEoi) "Missing standard JPEG EOI footer (repairable)" else null,
                hexSnippet = hexDump
            )
        }

        // 2. PNG
        if (startsWith(header, PNG_HEADER)) {
            val hasIend = containsSequence(tail, "IEND".toByteArray(Charsets.US_ASCII))
            val score = if (hasIend) 100 else 70
            val state = if (hasIend) FileHealthState.EXCELLENT else FileHealthState.REPAIRABLE
            return CarveResult(
                category = FileCategory.PHOTO,
                detectedExtension = "png",
                mimeType = "image/png",
                magicSignature = "89 50 4E 47 (PNG)",
                isHeaderValid = true,
                isFooterValid = hasIend,
                healthScore = score,
                healthState = state,
                corruptionReason = if (!hasIend) "Missing IEND terminator chunk (repairable)" else null,
                hexSnippet = hexDump
            )
        }

        // 3. GIF
        if (startsWith(header, GIF_HEADER_87) || startsWith(header, GIF_HEADER_89)) {
            return CarveResult(
                category = FileCategory.PHOTO,
                detectedExtension = "gif",
                mimeType = "image/gif",
                magicSignature = "GIF87a/89a",
                isHeaderValid = true,
                isFooterValid = tail.isNotEmpty() && tail.last() == 0x3B.toByte(),
                healthScore = 95,
                healthState = FileHealthState.EXCELLENT,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }

        // 4. RIFF formats: WEBP or WAV
        if (header.size >= 12 && startsWith(header, "RIFF".toByteArray(Charsets.US_ASCII))) {
            val riffType = String(header.copyOfRange(8, 12), Charsets.US_ASCII)
            if (riffType == "WEBP") {
                return CarveResult(
                    category = FileCategory.PHOTO,
                    detectedExtension = "webp",
                    mimeType = "image/webp",
                    magicSignature = "RIFF....WEBP",
                    isHeaderValid = true,
                    isFooterValid = true,
                    healthScore = 95,
                    healthState = FileHealthState.EXCELLENT,
                    corruptionReason = null,
                    hexSnippet = hexDump
                )
            } else if (riffType == "WAVE") {
                return CarveResult(
                    category = FileCategory.AUDIO,
                    detectedExtension = "wav",
                    mimeType = "audio/wav",
                    magicSignature = "RIFF....WAVE",
                    isHeaderValid = true,
                    isFooterValid = true,
                    healthScore = 95,
                    healthState = FileHealthState.EXCELLENT,
                    corruptionReason = null,
                    hexSnippet = hexDump
                )
            }
        }

        // 5. MP4 / MOV (ftyp box at offset 4)
        if (header.size >= 12 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
            header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) {
            val majorBrand = String(header.copyOfRange(8, 12), Charsets.US_ASCII).trim()
            val hasMdat = containsSequence(header, "mdat".toByteArray(Charsets.US_ASCII)) ||
                          containsSequence(tail, "mdat".toByteArray(Charsets.US_ASCII))
            val hasMoov = containsSequence(header, "moov".toByteArray(Charsets.US_ASCII)) ||
                          containsSequence(tail, "moov".toByteArray(Charsets.US_ASCII))
            val score = if (hasMdat && hasMoov) 98 else if (hasMdat) 70 else 55
            val state = if (score >= 90) FileHealthState.EXCELLENT else FileHealthState.GOOD
            return CarveResult(
                category = FileCategory.VIDEO,
                detectedExtension = "mp4",
                mimeType = "video/mp4",
                magicSignature = "ftyp $majorBrand (MP4/ISO)",
                isHeaderValid = true,
                isFooterValid = hasMoov,
                healthScore = score,
                healthState = state,
                corruptionReason = if (!hasMoov) "Movie metadata (moov) may be displaced" else null,
                hexSnippet = hexDump
            )
        }

        // 6. MP3
        if (startsWith(header, MP3_ID3) || (header.size >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)) {
            return CarveResult(
                category = FileCategory.AUDIO,
                detectedExtension = "mp3",
                mimeType = "audio/mpeg",
                magicSignature = "ID3 / Sync (MP3)",
                isHeaderValid = true,
                isFooterValid = true,
                healthScore = 95,
                healthState = FileHealthState.EXCELLENT,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }

        // 7. FLAC / OGG
        if (startsWith(header, FLAC_HEADER)) {
            return CarveResult(
                category = FileCategory.AUDIO,
                detectedExtension = "flac",
                mimeType = "audio/flac",
                magicSignature = "fLaC",
                isHeaderValid = true,
                isFooterValid = true,
                healthScore = 95,
                healthState = FileHealthState.EXCELLENT,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }
        if (startsWith(header, OGG_HEADER)) {
            return CarveResult(
                category = FileCategory.AUDIO,
                detectedExtension = "ogg",
                mimeType = "audio/ogg",
                magicSignature = "OggS",
                isHeaderValid = true,
                isFooterValid = true,
                healthScore = 95,
                healthState = FileHealthState.EXCELLENT,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }

        // 8. PDF
        if (startsWith(header, PDF_HEADER)) {
            val hasEof = containsSequence(tail, "%%EOF".toByteArray(Charsets.US_ASCII))
            val score = if (hasEof) 100 else 75
            val state = if (hasEof) FileHealthState.EXCELLENT else FileHealthState.REPAIRABLE
            return CarveResult(
                category = FileCategory.DOCUMENT,
                detectedExtension = "pdf",
                mimeType = "application/pdf",
                magicSignature = "%PDF",
                isHeaderValid = true,
                isFooterValid = hasEof,
                healthScore = score,
                healthState = state,
                corruptionReason = if (!hasEof) "Missing %%EOF marker (repairable)" else null,
                hexSnippet = hexDump
            )
        }

        // 9. ZIP / Office DOCX / APK
        if (startsWith(header, ZIP_HEADER)) {
            val ext = when (declaredExtension.lowercase()) {
                "docx" -> "docx"
                "xlsx" -> "xlsx"
                "pptx" -> "pptx"
                "apk" -> "apk"
                else -> "zip"
            }
            val cat = if (ext in listOf("docx", "xlsx", "pptx")) FileCategory.DOCUMENT else FileCategory.ARCHIVE
            val hasEocd = containsSequence(tail, byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x05.toByte(), 0x06.toByte()))
            val score = if (hasEocd) 95 else 70
            val state = if (hasEocd) FileHealthState.EXCELLENT else FileHealthState.REPAIRABLE
            return CarveResult(
                category = cat,
                detectedExtension = ext,
                mimeType = if (cat == FileCategory.DOCUMENT) "application/vnd.openxmlformats" else "application/zip",
                magicSignature = "50 4B 03 04 (PKZip)",
                isHeaderValid = true,
                isFooterValid = hasEocd,
                healthScore = score,
                healthState = state,
                corruptionReason = if (!hasEocd) "Central directory corrupted or truncated" else null,
                hexSnippet = hexDump
            )
        }

        // 10. RAR & 7Z
        if (startsWith(header, RAR_HEADER)) {
            return CarveResult(
                category = FileCategory.ARCHIVE,
                detectedExtension = "rar",
                mimeType = "application/x-rar-compressed",
                magicSignature = "Rar!",
                isHeaderValid = true,
                isFooterValid = true,
                healthScore = 95,
                healthState = FileHealthState.EXCELLENT,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }
        if (startsWith(header, SEVEN_Z_HEADER)) {
            return CarveResult(
                category = FileCategory.ARCHIVE,
                detectedExtension = "7z",
                mimeType = "application/x-7z-compressed",
                magicSignature = "7z",
                isHeaderValid = true,
                isFooterValid = true,
                healthScore = 95,
                healthState = FileHealthState.EXCELLENT,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }

        // 11. SQLite Database
        if (startsWith(header, SQLITE_HEADER) || startsWith(header, SQLITE_HEADER_ALT)) {
            return CarveResult(
                category = FileCategory.DATABASE,
                detectedExtension = "db",
                mimeType = "application/vnd.sqlite3",
                magicSignature = "SQLite format 3",
                isHeaderValid = true,
                isFooterValid = true,
                healthScore = 95,
                healthState = FileHealthState.EXCELLENT,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }

        // 12. Check if readable text / log / json / csv
        if (isPrintableAsciiOrUtf8(header)) {
            val detected = when {
                header.toString(Charsets.UTF_8).trimStart().startsWith("{") ||
                header.toString(Charsets.UTF_8).trimStart().startsWith("[") -> "json"
                header.toString(Charsets.UTF_8).trimStart().startsWith("<?xml") ||
                header.toString(Charsets.UTF_8).trimStart().startsWith("<") -> "xml"
                declaredExtension.lowercase() in listOf("txt", "csv", "json", "xml", "log", "md") -> declaredExtension.lowercase()
                else -> "txt"
            }
            return CarveResult(
                category = FileCategory.DOCUMENT,
                detectedExtension = detected,
                mimeType = "text/plain",
                magicSignature = "UTF-8 / ASCII Text",
                isHeaderValid = true,
                isFooterValid = true,
                healthScore = 90,
                healthState = FileHealthState.GOOD,
                corruptionReason = null,
                hexSnippet = hexDump
            )
        }

        // Unknown binary or damaged signature
        val declaredCat = mapExtensionToCategory(declaredExtension)
        val score = if (declaredExtension.isNotEmpty()) 45 else 20
        return CarveResult(
            category = declaredCat,
            detectedExtension = declaredExtension.ifEmpty { "bin" },
            mimeType = "application/octet-stream",
            magicSignature = "Unknown Binary [${header.take(4).joinToString(" ") { "%02X".format(it) }}]",
            isHeaderValid = false,
            isFooterValid = false,
            healthScore = score,
            healthState = if (score > 30) FileHealthState.REPAIRABLE else FileHealthState.CORRUPTED,
            corruptionReason = "Unrecognized file header or missing signature",
            hexSnippet = hexDump
        )
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    private fun containsSequence(data: ByteArray, seq: ByteArray): Boolean {
        if (data.size < seq.size || seq.isEmpty()) return false
        for (i in 0..(data.size - seq.size)) {
            var match = true
            for (j in seq.indices) {
                if (data[i + j] != seq[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun isPrintableAsciiOrUtf8(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        var printable = 0
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v in 32..126 || v == 9 || v == 10 || v == 13 || v >= 128) {
                printable++
            }
        }
        return (printable.toDouble() / data.size) > 0.85
    }

    private fun mapExtensionToCategory(ext: String): FileCategory {
        return when (ext.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "raw", "svg" -> FileCategory.PHOTO
            "mp4", "mkv", "avi", "mov", "3gp", "webm", "flv" -> FileCategory.VIDEO
            "mp3", "m4a", "wav", "aac", "ogg", "amr", "flac" -> FileCategory.AUDIO
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv" -> FileCategory.DOCUMENT
            "db", "sqlite", "sqlite3", "crypt14", "crypt15" -> FileCategory.DATABASE
            "zip", "rar", "7z", "tar", "gz", "apk" -> FileCategory.ARCHIVE
            else -> FileCategory.OTHER
        }
    }

    fun formatHexDump(bytes: ByteArray, maxLines: Int = 8): String {
        val sb = StringBuilder()
        val totalToDump = min(bytes.size, maxLines * 16)
        var offset = 0
        while (offset < totalToDump) {
            sb.append("%08X  ".format(offset))
            val lineBytes = min(16, totalToDump - offset)

            // Hex representation
            for (i in 0 until 16) {
                if (i == 8) sb.append(" ")
                if (i < lineBytes) {
                    sb.append("%02X ".format(bytes[offset + i]))
                } else {
                    sb.append("   ")
                }
            }
            sb.append(" |")

            // ASCII representation
            for (i in 0 until lineBytes) {
                val b = bytes[offset + i].toInt() and 0xFF
                if (b in 32..126) {
                    sb.append(b.toChar())
                } else {
                    sb.append('.')
                }
            }
            sb.append("|\n")
            offset += 16
        }
        return sb.toString().trimEnd()
    }
}

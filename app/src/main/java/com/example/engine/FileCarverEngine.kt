package com.example.engine

import android.content.Context
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Digital Forensics Grade File Carving Engine for recovering files from raw block devices,
 * damaged USB OTG flash drives, unreadable SD cards, and raw disk images (.img).
 *
 * Features:
 * - True full-capacity scanning (no hardcoded 256MB limit; queries actual device blocks)
 * - Streaming State Machine across unlimited consecutive 1MB chunks for large files (MP4, MKV, ZIP, PDF, high-res photos)
 * - Multi-Format Signatures with categorized subfolder organization (Images, Videos, Audio, Documents, Archives, Databases):
 *   1. JPEG (FF D8 FF -> FF D9)
 *   2. PNG (89 50 4E 47 0D 0A 1A 0A -> 49 45 4E 44 AE 42 60 82)
 *   3. WEBP (RIFF....WEBP with 32-bit Little-Endian size at offset 4..7)
 *   4. MP4 / MOV / 3GP (ISO Base Media ftyp at offset 4 + sequential Big-Endian box/atom length parsing)
 *   5. MP3 (ID3v2 49 44 33 header + synchsafe tag length + MPEG audio frames)
 *   6. WAV (RIFF....WAVE with 32-bit Little-Endian size at offset 4..7)
 *   7. PDF (25 50 44 46 %PDF -> 25 25 45 4F 46 %%EOF)
 *   8. ZIP / DOCX / XLSX / PPTX / APK (50 4B 03 04 -> 50 4B 05 06 End of Central Directory)
 *   9. SQLite 3 / WhatsApp DB (SQLite format 3\0 -> exact size from Page Size [16..17] * Page Count [28..31])
 * - Smart Zero-Block / Blank-Sector Skipping for high-speed scanning of large drives
 * - Fault-tolerant bad-sector recovery & Checkpoint/Resume support
 */
object FileCarverEngine {

    const val CHUNK_SIZE = 1024 * 1024 // 1MB chunks

    // Magic Numbers
    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    private val PNG_FOOTER = byteArrayOf(
        0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(),
        0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
    ) // IEND

    private val RIFF_HEADER = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()) // "RIFF"
    private val WEBP_FOURCC = byteArrayOf(0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte()) // "WEBP"
    private val WAVE_FOURCC = byteArrayOf(0x57.toByte(), 0x41.toByte(), 0x56.toByte(), 0x45.toByte()) // "WAVE"

    private val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte()) // "ftyp"
    private val MP3_ID3 = byteArrayOf(0x49.toByte(), 0x44.toByte(), 0x33.toByte()) // "ID3"

    private val PDF_HEADER = byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte()) // "%PDF"
    private val PDF_FOOTER = byteArrayOf(0x25.toByte(), 0x25.toByte(), 0x45.toByte(), 0x4F.toByte(), 0x46.toByte()) // "%%EOF"

    private val ZIP_HEADER = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte()) // PK\x03\x04
    private val ZIP_EOCD = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x05.toByte(), 0x06.toByte()) // PK\x05\x06

    private val SQLITE_HEADER = byteArrayOf(
        0x53.toByte(), 0x51.toByte(), 0x4C.toByte(), 0x69.toByte(),
        0x74.toByte(), 0x65.toByte(), 0x20.toByte(), 0x66.toByte(),
        0x6F.toByte(), 0x72.toByte(), 0x6D.toByte(), 0x61.toByte(),
        0x74.toByte(), 0x20.toByte(), 0x33.toByte(), 0x00.toByte()
    ) // "SQLite format 3\0"

    private val VALID_MP4_BOXES = setOf(
        "ftyp", "moov", "mdat", "free", "skip", "wide", "pnot",
        "uuid", "meta", "udta", "styp", "sidx", "moof", "mfra", "beam"
    )

    data class CarveProgress(
        val scannedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Float,
        val filesFound: Int,
        val badSectorsSkipped: Int,
        val zeroBlocksSkipped: Int = 0,
        val statusMessage: String
    )

    /**
     * Represents an open file currently being carved across one or more 1MB chunks.
     */
    private class ActiveCarveStream(
        val extension: String,
        val mimeType: String,
        val category: FileCategory,
        val signature: String,
        val startStreamOffset: Long,
        val targetFile: File,
        val outputStream: BufferedOutputStream,
        val maxAllowedBytes: Long,
        val footerPattern: ByteArray? = null,
        val trailingBytesAfterFooter: Int = 0,
        var expectedExactBytes: Long = -1L, // Used when exact size is known (WEBP, WAV, SQLite, MP4 box sum)
        val isMp4Stream: Boolean = false
    ) {
        var bytesWritten: Long = 0L
        val headerSample = ByteArray(128)
        var headerSampleLen = 0
        var tailOverlap = ByteArray(0)

        // MP4 streaming box state
        var mp4CurrentBoxRemaining: Long = 0L
        var mp4SeenMoov: Boolean = false
        var mp4SeenMdat: Boolean = false
        var mp4BoxHeaderBuffer = ByteArray(0)

        fun writeBytes(data: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            outputStream.write(data, offset, length)
            if (headerSampleLen < headerSample.size) {
                val toCopy = min(headerSample.size - headerSampleLen, length)
                System.arraycopy(data, offset, headerSample, headerSampleLen, toCopy)
                headerSampleLen += toCopy
            }
            bytesWritten += length
        }

        fun closeStream() {
            try {
                outputStream.flush()
            } catch (_: Exception) {}
            try {
                outputStream.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Stateful streaming carver that processes sequential chunks of arbitrary size (e.g. 1MB)
     * and seamlessly extracts both small files and multi-hundred-megabyte videos/archives.
     */
    class StreamingCarverSession(
        private val baseOutputDir: File
    ) {
        private var activeStream: ActiveCarveStream? = null
        private var crossChunkLeadBuffer = ByteArray(0)
        val rescuedFiles = mutableListOf<RescuedFile>()
        var zeroBlocksSkipped = 0
            private set

        /**
         * Processes the next sequential chunk of raw disk bytes.
         */
        fun feedChunk(chunk: ByteArray, chunkLength: Int, chunkStartDiskOffset: Long): List<RescuedFile> {
            if (chunkLength <= 0) return emptyList()
            val newlyRescued = mutableListOf<RescuedFile>()

            // Fast zero-block check when no stream is currently open
            if (activeStream == null && isAllZerosOrBlank(chunk, chunkLength)) {
                zeroBlocksSkipped++
                crossChunkLeadBuffer = ByteArray(0)
                return emptyList()
            }

            // Combine with small lead buffer from previous chunk (to detect headers split across chunk boundary)
            val data: ByteArray
            val dataSize: Int
            val baseDiskOffset: Long

            if (activeStream == null && crossChunkLeadBuffer.isNotEmpty()) {
                dataSize = crossChunkLeadBuffer.size + chunkLength
                data = ByteArray(dataSize)
                System.arraycopy(crossChunkLeadBuffer, 0, data, 0, crossChunkLeadBuffer.size)
                System.arraycopy(chunk, 0, data, crossChunkLeadBuffer.size, chunkLength)
                baseDiskOffset = (chunkStartDiskOffset - crossChunkLeadBuffer.size).coerceAtLeast(0L)
                crossChunkLeadBuffer = ByteArray(0)
            } else {
                data = if (chunkLength == chunk.size) chunk else chunk.copyOf(chunkLength)
                dataSize = chunkLength
                baseDiskOffset = chunkStartDiskOffset
            }

            var cursor = 0

            while (cursor < dataSize) {
                val currentActive = activeStream
                if (currentActive != null) {
                    // Continue streaming into the open file
                    val consumed = continueActiveStream(currentActive, data, cursor, dataSize, newlyRescued)
                    cursor += consumed
                } else {
                    // Scan for next file signature header
                    // If remaining bytes are fewer than 64, keep them in crossChunkLeadBuffer for next chunk
                    if (dataSize - cursor < 64) {
                        crossChunkLeadBuffer = data.copyOfRange(cursor, dataSize)
                        break
                    }

                    val detected = tryStartNewStream(data, cursor, dataSize, baseDiskOffset + cursor)
                    if (detected != null) {
                        activeStream = detected.first
                        // Advance cursor to the actual start of the file (e.g. cursor - 4 for MP4 ftyp)
                        cursor = detected.second
                    } else {
                        // Advance by 512-byte sector boundary if aligned, or scan next byte
                        cursor += 1
                    }
                }
            }

            return newlyRescued
        }

        /**
         * Finalizes any open stream when reaching end of disk/scan.
         */
        fun finishSession(): List<RescuedFile> {
            val newlyRescued = mutableListOf<RescuedFile>()
            val currentActive = activeStream
            if (currentActive != null) {
                finalizeStream(currentActive, isFooterComplete = false, newlyRescued)
                activeStream = null
            }
            crossChunkLeadBuffer = ByteArray(0)
            return newlyRescued
        }

        private fun tryStartNewStream(
            data: ByteArray,
            offset: Int,
            dataSize: Int,
            diskOffset: Long
        ): Pair<ActiveCarveStream, Int>? {
            val remaining = dataSize - offset
            if (remaining < 16) return null

            // 1. JPEG: FF D8 FF (with valid marker E0, E1, E2, E8, DB, C0, C4, FE)
            if (isMatch(data, offset, JPEG_SOI)) {
                val fourthByte = data[offset + 3].toInt() and 0xFF
                if (fourthByte in listOf(0xE0, 0xE1, 0xE2, 0xE8, 0xDB, 0xC0, 0xC2, 0xC4, 0xFE, 0xEE)) {
                    val stream = createStream(
                        ext = "jpg",
                        mime = "image/jpeg",
                        category = FileCategory.PHOTO,
                        signature = "FF D8 FF (JPEG)",
                        diskOffset = diskOffset,
                        maxAllowedBytes = 35L * 1024L * 1024L, // 35MB max JPEG
                        footerPattern = JPEG_EOI
                    ) ?: return null
                    return stream to offset
                }
            }

            // 2. PNG: 89 50 4E 47 0D 0A 1A 0A
            if (isMatch(data, offset, PNG_HEADER)) {
                val stream = createStream(
                    ext = "png",
                    mime = "image/png",
                    category = FileCategory.PHOTO,
                    signature = "89 50 4E 47 (PNG)",
                    diskOffset = diskOffset,
                    maxAllowedBytes = 50L * 1024L * 1024L, // 50MB max PNG
                    footerPattern = PNG_FOOTER
                ) ?: return null
                return stream to offset
            }

            // 3. WEBP or WAV: RIFF (52 49 46 46) + 4-byte LE size + WEBP or WAVE at offset 8
            if (remaining >= 12 && isMatch(data, offset, RIFF_HEADER)) {
                val riffPayloadSize = readUint32LittleEndian(data, offset + 4)
                val totalRiffSize = (riffPayloadSize + 8L).coerceIn(128L, 200L * 1024L * 1024L)
                if (isMatch(data, offset + 8, WEBP_FOURCC)) {
                    val stream = createStream(
                        ext = "webp",
                        mime = "image/webp",
                        category = FileCategory.PHOTO,
                        signature = "RIFF....WEBP",
                        diskOffset = diskOffset,
                        maxAllowedBytes = totalRiffSize,
                        expectedExactBytes = totalRiffSize
                    ) ?: return null
                    return stream to offset
                } else if (isMatch(data, offset + 8, WAVE_FOURCC)) {
                    val stream = createStream(
                        ext = "wav",
                        mime = "audio/wav",
                        category = FileCategory.AUDIO,
                        signature = "RIFF....WAVE",
                        diskOffset = diskOffset,
                        maxAllowedBytes = totalRiffSize,
                        expectedExactBytes = totalRiffSize
                    ) ?: return null
                    return stream to offset
                }
            }

            // 4. MP4 / MOV / 3GP: "ftyp" at offset + 4
            if (remaining >= 16 && isMatch(data, offset + 4, MP4_FTYP)) {
                val ftypBoxSize = readUint32BigEndian(data, offset)
                if (ftypBoxSize in 8L..1024L) {
                    val brand = String(data, offset + 8, 4, Charsets.US_ASCII)
                    val ext = when {
                        brand.startsWith("qt") -> "mov"
                        brand.startsWith("3gp") -> "3gp"
                        else -> "mp4"
                    }
                    val mime = when (ext) {
                        "mov" -> "video/quicktime"
                        "3gp" -> "video/3gpp"
                        else -> "video/mp4"
                    }
                    // Pre-calculate total MP4 size if all boxes fit in buffer, or use streaming box parser
                    val exactInBuffer = parseMp4TotalLengthInBuffer(data, offset, dataSize)
                    val stream = createStream(
                        ext = ext,
                        mime = mime,
                        category = FileCategory.VIDEO,
                        signature = "ftyp $brand (${ext.uppercase()})",
                        diskOffset = diskOffset,
                        maxAllowedBytes = 1024L * 1024L * 1024L, // Up to 1GB per video
                        expectedExactBytes = exactInBuffer,
                        isMp4Stream = exactInBuffer <= 0L
                    ) ?: return null
                    return stream to offset
                }
            }

            // 5. PDF: %PDF (25 50 44 46)
            if (remaining >= 8 && isMatch(data, offset, PDF_HEADER) && data[offset + 4] == '-'.code.toByte()) {
                val stream = createStream(
                    ext = "pdf",
                    mime = "application/pdf",
                    category = FileCategory.DOCUMENT,
                    signature = "%PDF (Document)",
                    diskOffset = diskOffset,
                    maxAllowedBytes = 100L * 1024L * 1024L, // Up to 100MB PDF
                    footerPattern = PDF_FOOTER,
                    trailingBytesAfterFooter = 2
                ) ?: return null
                return stream to offset
            }

            // 6. ZIP / DOCX / XLSX / PPTX / APK: 50 4B 03 04
            if (remaining >= 30 && isMatch(data, offset, ZIP_HEADER)) {
                // Inspect internal filename in Local File Header (offset 30, length at 26..27 LE)
                val fileNameLen = readUint16LittleEndian(data, offset + 26)
                val headerHint = if (fileNameLen in 1..120 && offset + 30 + fileNameLen <= dataSize) {
                    String(data, offset + 30, fileNameLen, Charsets.UTF_8)
                } else ""

                val (ext, cat, mime) = when {
                    headerHint.startsWith("word/") || headerHint.contains("[Content_Types].xml") ->
                        Triple("docx", FileCategory.DOCUMENT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    headerHint.startsWith("xl/") ->
                        Triple("xlsx", FileCategory.DOCUMENT, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    headerHint.startsWith("ppt/") ->
                        Triple("pptx", FileCategory.DOCUMENT, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                    headerHint == "AndroidManifest.xml" || headerHint == "classes.dex" ->
                        Triple("apk", FileCategory.ARCHIVE, "application/vnd.android.package-archive")
                    else ->
                        Triple("zip", FileCategory.ARCHIVE, "application/zip")
                }

                val stream = createStream(
                    ext = ext,
                    mime = mime,
                    category = cat,
                    signature = "50 4B 03 04 (${ext.uppercase()})",
                    diskOffset = diskOffset,
                    maxAllowedBytes = 300L * 1024L * 1024L, // Up to 300MB archive
                    footerPattern = ZIP_EOCD,
                    trailingBytesAfterFooter = 18 // 22-byte EOCD record minus 4-byte signature
                ) ?: return null
                return stream to offset
            }

            // 7. SQLite 3 / WhatsApp Database: "SQLite format 3\0"
            if (remaining >= 32 && isMatch(data, offset, SQLITE_HEADER)) {
                val rawPageSize = readUint16BigEndian(data, offset + 16)
                val pageSize = if (rawPageSize == 1) 65536L else rawPageSize.toLong()
                val pageCount = readUint32BigEndian(data, offset + 28)

                val calculatedSize = if (pageSize in 512L..65536L && pageCount > 0L) {
                    (pageSize * pageCount).coerceIn(4096L, 500L * 1024L * 1024L)
                } else {
                    2L * 1024L * 1024L // 2MB fallback if pageCount header is 0
                }

                val stream = createStream(
                    ext = "db",
                    mime = "application/vnd.sqlite3",
                    category = FileCategory.DATABASE,
                    signature = "SQLite format 3 (Database)",
                    diskOffset = diskOffset,
                    maxAllowedBytes = calculatedSize,
                    expectedExactBytes = calculatedSize
                ) ?: return null
                return stream to offset
            }

            // 8. MP3 Audio (ID3v2 tag: "ID3" 49 44 33 + version bytes < 0xFF)
            if (remaining >= 10 && isMatch(data, offset, MP3_ID3)) {
                val verMajor = data[offset + 3].toInt() and 0xFF
                val verRev = data[offset + 4].toInt() and 0xFF
                if (verMajor in 2..4 && verRev < 0xFF) {
                    val id3Size = readSynchsafeInt(data, offset + 6)
                    // Estimate MP3 size: ID3 tag + audio frames in stream, or up to 15MB
                    val estimatedSize = estimateMp3Length(data, offset, dataSize, id3Size)
                    val stream = createStream(
                        ext = "mp3",
                        mime = "audio/mpeg",
                        category = FileCategory.AUDIO,
                        signature = "ID3v2.$verMajor (MP3 Audio)",
                        diskOffset = diskOffset,
                        maxAllowedBytes = 25L * 1024L * 1024L,
                        expectedExactBytes = estimatedSize
                    ) ?: return null
                    return stream to offset
                }
            }

            return null
        }

        private fun continueActiveStream(
            stream: ActiveCarveStream,
            data: ByteArray,
            startOffset: Int,
            dataSize: Int,
            newlyRescued: MutableList<RescuedFile>
        ): Int {
            val available = dataSize - startOffset
            if (available <= 0) return 0

            // Case A: Exact byte length known (WEBP, WAV, SQLite, pre-parsed MP4, MP3)
            if (stream.expectedExactBytes > 0L) {
                val remainingNeeded = stream.expectedExactBytes - stream.bytesWritten
                val toWrite = min(available.toLong(), remainingNeeded).toInt()
                stream.writeBytes(data, startOffset, toWrite)

                if (stream.bytesWritten >= stream.expectedExactBytes || stream.bytesWritten >= stream.maxAllowedBytes) {
                    finalizeStream(stream, isFooterComplete = true, newlyRescued)
                    activeStream = null
                }
                return toWrite.coerceAtLeast(1)
            }

            // Case B: Streaming MP4 Box/Atom state machine across chunks
            if (stream.isMp4Stream) {
                var pos = startOffset
                while (pos < dataSize) {
                    if (stream.mp4CurrentBoxRemaining > 0L) {
                        val step = min((dataSize - pos).toLong(), stream.mp4CurrentBoxRemaining).toInt()
                        stream.writeBytes(data, pos, step)
                        pos += step
                        stream.mp4CurrentBoxRemaining -= step

                        if (stream.bytesWritten >= stream.maxAllowedBytes) {
                            finalizeStream(stream, isFooterComplete = false, newlyRescued)
                            activeStream = null
                            return pos - startOffset
                        }
                    } else {
                        // Need 8 bytes to read next MP4 Box header (4-byte BE size + 4-byte ASCII type)
                        val neededForHeader = 8 - stream.mp4BoxHeaderBuffer.size
                        val availableNow = dataSize - pos
                        if (availableNow < neededForHeader) {
                            val partial = data.copyOfRange(pos, dataSize)
                            stream.mp4BoxHeaderBuffer += partial
                            return dataSize - startOffset
                        }

                        val fullHeader = ByteArray(8)
                        val prevLen = stream.mp4BoxHeaderBuffer.size
                        if (prevLen > 0) {
                            System.arraycopy(stream.mp4BoxHeaderBuffer, 0, fullHeader, 0, prevLen)
                        }
                        System.arraycopy(data, pos, fullHeader, prevLen, neededForHeader)
                        stream.mp4BoxHeaderBuffer = ByteArray(0)

                        val boxSize = readUint32BigEndian(fullHeader, 0)
                        val boxType = String(fullHeader, 4, 4, Charsets.US_ASCII)
                        val isValidBox = boxType in VALID_MP4_BOXES ||
                                boxType.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' }

                        if (!isValidBox || boxSize < 8L || boxSize > stream.maxAllowedBytes) {
                            // End of valid MP4 atom chain! Finalize video cleanly without writing non-MP4 bytes
                            finalizeStream(
                                stream,
                                isFooterComplete = (stream.mp4SeenMdat || stream.mp4SeenMoov),
                                newlyRescued
                            )
                            activeStream = null
                            return (pos - startOffset).coerceAtLeast(1)
                        }

                        if (boxType == "moov") stream.mp4SeenMoov = true
                        if (boxType == "mdat") stream.mp4SeenMdat = true

                        // Write the 8-byte box header
                        stream.writeBytes(fullHeader, 0, 8)
                        pos += neededForHeader
                        stream.mp4CurrentBoxRemaining = boxSize - 8L
                    }
                }
                return dataSize - startOffset
            }

            // Case C: Footer-delimited stream (JPEG, PNG, PDF, ZIP/DOCX/APK)
            val footer = stream.footerPattern
            if (footer != null) {
                val searchWindow: ByteArray
                val overlapLen = stream.tailOverlap.size
                if (overlapLen > 0) {
                    searchWindow = ByteArray(overlapLen + available)
                    System.arraycopy(stream.tailOverlap, 0, searchWindow, 0, overlapLen)
                    System.arraycopy(data, startOffset, searchWindow, overlapLen, available)
                } else {
                    searchWindow = data
                }

                val searchStart = if (overlapLen > 0) 0 else startOffset
                val searchEnd = if (overlapLen > 0) searchWindow.size else dataSize

                val matchIndex = findPatternInWindow(searchWindow, searchStart, searchEnd, footer)
                if (matchIndex != -1) {
                    val endInWindow = (matchIndex + footer.size + stream.trailingBytesAfterFooter)
                        .coerceAtMost(searchEnd)
                    val bytesFromCurrentData = if (overlapLen > 0) {
                        (endInWindow - overlapLen).coerceIn(0, available)
                    } else {
                        (endInWindow - startOffset).coerceIn(0, available)
                    }

                    stream.writeBytes(data, startOffset, bytesFromCurrentData)
                    finalizeStream(stream, isFooterComplete = true, newlyRescued)
                    activeStream = null
                    return bytesFromCurrentData.coerceAtLeast(1)
                } else {
                    // Footer not in this chunk yet; write up to maxAllowedBytes
                    val maxRemaining = (stream.maxAllowedBytes - stream.bytesWritten).coerceAtLeast(0L)
                    val toWrite = min(available.toLong(), maxRemaining).toInt()
                    stream.writeBytes(data, startOffset, toWrite)

                    if (stream.bytesWritten >= stream.maxAllowedBytes) {
                        finalizeStream(stream, isFooterComplete = false, newlyRescued)
                        activeStream = null
                        return toWrite.coerceAtLeast(1)
                    }

                    // Keep last (footer.size + trailingBytesAfterFooter) bytes for cross-chunk footer detection
                    val keepLen = min(footer.size + stream.trailingBytesAfterFooter, toWrite)
                    if (keepLen > 0) {
                        stream.tailOverlap = data.copyOfRange(startOffset + toWrite - keepLen, startOffset + toWrite)
                    }
                    return toWrite.coerceAtLeast(1)
                }
            }

            return available
        }

        private fun createStream(
            ext: String,
            mime: String,
            category: FileCategory,
            signature: String,
            diskOffset: Long,
            maxAllowedBytes: Long,
            footerPattern: ByteArray? = null,
            trailingBytesAfterFooter: Int = 0,
            expectedExactBytes: Long = -1L,
            isMp4Stream: Boolean = false
        ): ActiveCarveStream? {
            return try {
                val categoryDir = FileRescueManager.getCategorySubdirectory(baseOutputDir, category)
                if (!categoryDir.exists()) categoryDir.mkdirs()

                val fileName = "rescued_${ext}_sec${diskOffset}_${System.currentTimeMillis() % 100000}.$ext"
                val targetFile = File(categoryDir, fileName)
                val bos = BufferedOutputStream(FileOutputStream(targetFile), 64 * 1024)

                ActiveCarveStream(
                    extension = ext,
                    mimeType = mime,
                    category = category,
                    signature = signature,
                    startStreamOffset = diskOffset,
                    targetFile = targetFile,
                    outputStream = bos,
                    maxAllowedBytes = maxAllowedBytes,
                    footerPattern = footerPattern,
                    trailingBytesAfterFooter = trailingBytesAfterFooter,
                    expectedExactBytes = expectedExactBytes,
                    isMp4Stream = isMp4Stream
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun finalizeStream(
            stream: ActiveCarveStream,
            isFooterComplete: Boolean,
            newlyRescued: MutableList<RescuedFile>
        ) {
            stream.closeStream()

            val minValidSize = when (stream.category) {
                FileCategory.PHOTO -> 512L
                FileCategory.VIDEO -> 1024L
                FileCategory.AUDIO -> 512L
                FileCategory.DOCUMENT -> 256L
                FileCategory.DATABASE -> 512L
                FileCategory.ARCHIVE -> 128L
                FileCategory.OTHER -> 512L
            }

            val actualLength = stream.targetFile.length()
            if (actualLength < minValidSize) {
                try {
                    stream.targetFile.delete()
                } catch (_: Exception) {}
                return
            }

            val sample = stream.headerSample.copyOf(stream.headerSampleLen)
            val hexSnippet = FileCarver.formatHexDump(sample)
            val healthState = if (isFooterComplete) FileHealthState.EXCELLENT else FileHealthState.REPAIRABLE
            val healthScore = if (isFooterComplete) 95 else 76

            val rescued = RescuedFile(
                id = UUID.randomUUID().toString(),
                name = stream.targetFile.name,
                path = stream.targetFile.absolutePath,
                uri = null,
                size = actualLength,
                lastModified = System.currentTimeMillis(),
                category = stream.category,
                mimeType = stream.mimeType,
                detectedExtension = stream.extension,
                originalExtension = stream.extension,
                healthState = healthState,
                healthScore = healthScore,
                sourceType = FileSourceType.USB_OTG_RAW_SECTOR,
                magicSignature = stream.signature,
                isHeaderValid = true,
                isFooterValid = isFooterComplete,
                corruptionReason = if (!isFooterComplete) "Stream reached limit before footer (repairable)" else null,
                isRescued = true,
                rescuedDestinationPath = stream.targetFile.absolutePath,
                hexSnippet = hexSnippet
            )

            rescuedFiles.add(rescued)
            newlyRescued.add(rescued)
        }
    }

    /**
     * Carves files directly from a libaums BlockDeviceDriver (partitions or raw disk).
     * Reads the FULL capacity of the storage device when maxBytesToScan <= 0L.
     */
    suspend fun carveFromBlockDevice(
        context: Context,
        blockDevice: BlockDeviceDriver,
        maxBytesToScan: Long = 0L, // 0L = Full Real Capacity of the USB/SD card
        startOffsetBytes: Long = 0L,
        onCheckpoint: ((Long) -> Unit)? = null,
        onProgress: suspend (CarveProgress) -> Unit
    ): List<RescuedFile> = withContext(Dispatchers.IO) {
        val outputDir = FileRescueManager.getRescueDirectory(context)

        val blockSize = try {
            blockDevice.blockSize.coerceAtLeast(512)
        } catch (_: Exception) {
            512
        }

        val totalBlocks = try {
            blockDevice.blocks
        } catch (_: Exception) {
            0L
        }

        val realDeviceBytes = if (totalBlocks > 0L) {
            totalBlocks * blockSize.toLong()
        } else {
            // If driver does not report block count, probe or allow large scan
            if (maxBytesToScan > 0L) maxBytesToScan else 64L * 1024L * 1024L * 1024L // 64GB probe ceiling
        }

        val targetScanBytes = if (maxBytesToScan > 0L) {
            realDeviceBytes.coerceAtMost(maxBytesToScan)
        } else {
            realDeviceBytes
        }

        val session = StreamingCarverSession(outputDir)
        var currentBlockOffset = (startOffsetBytes / blockSize).coerceAtLeast(0L)
        val totalTargetBlocks = (targetScanBytes / blockSize).coerceAtLeast(1L)
        val blocksPerChunk = (CHUNK_SIZE / blockSize).coerceAtLeast(1)

        var badSectorsCount = 0
        var consecutiveReadErrors = 0
        val readBuffer = ByteBuffer.allocate(blocksPerChunk * blockSize)

        while (currentBlockOffset < totalTargetBlocks && coroutineContext.isActive) {
            readBuffer.clear()
            val blocksToRead = blocksPerChunk.toLong()
                .coerceAtMost(totalTargetBlocks - currentBlockOffset)
                .toInt()
            val bytesToRead = blocksToRead * blockSize
            readBuffer.limit(bytesToRead)

            var readSuccessful = false
            try {
                // Note: In libaums BlockDeviceDriver, offset is in blocks (sectors)
                blockDevice.read(currentBlockOffset, readBuffer)
                readSuccessful = true
                consecutiveReadErrors = 0
            } catch (_: Exception) {
                // Bad sector encountered! Skip the damaged sector block and continue
                badSectorsCount++
                consecutiveReadErrors++
                val skipBlocks = if (consecutiveReadErrors > 16) 64L else 1L
                currentBlockOffset += skipBlocks

                // If we hit hundreds of consecutive errors past end of unannounced drive, stop gracefully
                if (totalBlocks <= 0L && consecutiveReadErrors > 256) {
                    break
                }
                continue
            }

            if (readSuccessful) {
                val actualBytes = bytesToRead
                val chunkBytes = ByteArray(actualBytes)
                readBuffer.flip()
                val availableInBuf = readBuffer.remaining().coerceAtMost(actualBytes)
                readBuffer.get(chunkBytes, 0, availableInBuf)

                val byteOffset = currentBlockOffset * blockSize
                session.feedChunk(chunkBytes, availableInBuf, byteOffset)

                currentBlockOffset += blocksToRead
                val scannedBytesNow = currentBlockOffset * blockSize
                onCheckpoint?.invoke(scannedBytesNow)

                val percent = if (targetScanBytes > 0L) {
                    (scannedBytesNow.toFloat() / targetScanBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f

                onProgress(
                    CarveProgress(
                        scannedBytes = scannedBytesNow,
                        totalBytes = targetScanBytes,
                        progressPercent = percent,
                        filesFound = session.rescuedFiles.size,
                        badSectorsSkipped = badSectorsCount,
                        zeroBlocksSkipped = session.zeroBlocksSkipped,
                        statusMessage = "Scanned ${FileRescueManager.formatFileSize(scannedBytesNow)} / ${FileRescueManager.formatFileSize(targetScanBytes)} • Found: ${session.rescuedFiles.size}"
                    )
                )
            }
        }

        session.finishSession()
        val finalBytes = currentBlockOffset * blockSize

        onProgress(
            CarveProgress(
                scannedBytes = finalBytes,
                totalBytes = targetScanBytes,
                progressPercent = 1.0f,
                filesFound = session.rescuedFiles.size,
                badSectorsSkipped = badSectorsCount,
                zeroBlocksSkipped = session.zeroBlocksSkipped,
                statusMessage = "Carve complete. Rescued ${session.rescuedFiles.size} files."
            )
        )

        session.rescuedFiles
    }

    /**
     * Carves files from a local raw disk image (.img) file using the Streaming State Machine.
     */
    suspend fun carveFromRawImageFile(
        context: Context,
        imageFile: File,
        startOffsetBytes: Long = 0L,
        onProgress: suspend (CarveProgress) -> Unit
    ): List<RescuedFile> = withContext(Dispatchers.IO) {
        if (!imageFile.exists() || !imageFile.isFile) return@withContext emptyList()
        val outputDir = FileRescueManager.getRescueDirectory(context)
        val session = StreamingCarverSession(outputDir)
        val totalBytes = imageFile.length()
        var currentOffset = startOffsetBytes.coerceIn(0L, totalBytes)
        val buffer = ByteArray(CHUNK_SIZE)

        RandomAccessFile(imageFile, "r").use { raf ->
            raf.seek(currentOffset)
            while (currentOffset < totalBytes && coroutineContext.isActive) {
                val read = raf.read(buffer)
                if (read <= 0) break
                session.feedChunk(buffer, read, currentOffset)
                currentOffset += read

                val percent = if (totalBytes > 0L) (currentOffset.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 1f
                onProgress(
                    CarveProgress(
                        scannedBytes = currentOffset,
                        totalBytes = totalBytes,
                        progressPercent = percent,
                        filesFound = session.rescuedFiles.size,
                        badSectorsSkipped = 0,
                        zeroBlocksSkipped = session.zeroBlocksSkipped,
                        statusMessage = "Carving Image: ${FileRescueManager.formatFileSize(currentOffset)} / ${FileRescueManager.formatFileSize(totalBytes)}"
                    )
                )
            }
        }

        session.finishSession()
        session.rescuedFiles
    }

    /**
     * Carves files from an in-memory byte array or sector buffer using the Streaming State Machine.
     */
    fun scanAndExtractCarvedFiles(data: ByteArray, streamOffset: Long, outputDir: File): List<RescuedFile> {
        val session = StreamingCarverSession(outputDir)
        session.feedChunk(data, data.size, streamOffset)
        session.finishSession()
        return session.rescuedFiles
    }

    /**
     * Checks if a chunk is completely empty (all 0x00 or all 0xFF) by sampling stride points first,
     * then verifying full bytes so non-empty sectors are never skipped.
     */
    fun isAllZerosOrBlank(buffer: ByteArray, length: Int): Boolean {
        if (length <= 0) return true
        val first = buffer[0]
        if (first != 0.toByte() && first != 0xFF.toByte()) return false

        // Quick stride check every 64 bytes
        var idx = 0
        while (idx < length) {
            if (buffer[idx] != first) return false
            idx += 64
        }
        // Full verification
        for (i in 0 until length) {
            if (buffer[i] != first) return false
        }
        return true
    }

    /**
     * Parses MP4 ISO Base Media box sequence if the entire video is contained within the current buffer.
     * Returns -1L if the video extends across future chunks (so streaming box mode takes over).
     */
    private fun parseMp4TotalLengthInBuffer(data: ByteArray, startOffset: Int, dataSize: Int): Long {
        var cursor = startOffset
        var totalSize = 0L
        var seenMoov = false
        var seenMdat = false

        while (cursor + 8 <= dataSize) {
            val boxSize = readUint32BigEndian(data, cursor)
            if (boxSize < 8L) break

            val tag = String(data, cursor + 4, 4, Charsets.US_ASCII)
            val isValidTag = tag in VALID_MP4_BOXES ||
                    tag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' }
            if (!isValidTag) break

            if (tag == "moov") seenMoov = true
            if (tag == "mdat") seenMdat = true

            if (cursor + boxSize > dataSize) {
                // Extends beyond current chunk! Return -1L to activate multi-chunk MP4 box streaming
                return -1L
            }

            totalSize += boxSize
            cursor += boxSize.toInt()
        }

        return if (totalSize >= 512L && (seenMoov || seenMdat)) totalSize else -1L
    }

    private fun estimateMp3Length(data: ByteArray, startOffset: Int, dataSize: Int, id3PayloadSize: Int): Long {
        val tagTotal = 10L + id3PayloadSize
        // Look ahead for end of audio or zero run in current buffer
        val searchStart = (startOffset + tagTotal.toInt()).coerceAtMost(dataSize)
        var zeroRun = 0
        for (i in searchStart until dataSize) {
            if (data[i] == 0.toByte()) {
                zeroRun++
                if (zeroRun >= 512) {
                    return ((i - 512) - startOffset).toLong().coerceAtLeast(tagTotal + 1024L)
                }
            } else {
                zeroRun = 0
            }
        }
        return (tagTotal + 4L * 1024L * 1024L).coerceAtMost(16L * 1024L * 1024L)
    }

    private fun readUint32BigEndian(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    private fun readUint32LittleEndian(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readUint16BigEndian(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUint16LittleEndian(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readSynchsafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
                ((data[offset + 1].toInt() and 0x7F) shl 14) or
                ((data[offset + 2].toInt() and 0x7F) shl 7) or
                (data[offset + 3].toInt() and 0x7F)
    }

    private fun isMatch(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset < 0 || offset + pattern.size > data.size) return false
        for (i in pattern.indices) {
            if (data[offset + i] != pattern[i]) return false
        }
        return true
    }

    private fun findPatternInWindow(data: ByteArray, startOffset: Int, endLimit: Int, pattern: ByteArray): Int {
        val maxIdx = endLimit - pattern.size
        if (startOffset > maxIdx) return -1
        val firstByte = pattern[0]
        var i = startOffset
        while (i <= maxIdx) {
            if (data[i] == firstByte) {
                var matched = true
                for (j in 1 until pattern.size) {
                    if (data[i + j] != pattern[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) return i
            }
            i++
        }
        return -1
    }
}

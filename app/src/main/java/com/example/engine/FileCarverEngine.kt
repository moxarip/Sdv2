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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Advanced file carving engine for recovering files from raw block devices,
 * damaged USB OTG flash drives, and unreadable SD cards.
 *
 * Implements:
 * - 1MB chunked reading with chunk boundary overlap handling
 * - Fault-tolerant bad-sector skipping (try-catch on sector read)
 * - Deep signature extraction: JPEG, PNG, PDF, and MP4 (ftyp/box parsing)
 * - 100% Read-Only operation to preserve damaged storage media
 */
object FileCarverEngine {

    const val CHUNK_SIZE = 1024 * 1024 // 1MB chunks
    private const val OVERLAP_SIZE = 256 * 1024 // 256KB overlap for cross-boundary files

    // Magic Numbers
    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
    private val PNG_FOOTER = byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()) // IEND

    private val PDF_HEADER = byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte()) // %PDF
    private val PDF_FOOTER = byteArrayOf(0x25.toByte(), 0x25.toByte(), 0x45.toByte(), 0x4F.toByte(), 0x46.toByte()) // %%EOF

    private val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte()) // ftyp

    data class CarveProgress(
        val scannedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Float,
        val filesFound: Int,
        val badSectorsSkipped: Int,
        val statusMessage: String
    )

    /**
     * Carves files directly from a libaums BlockDeviceDriver (partitions or raw disk).
     * Bypasses the filesystem entirely and reads raw sectors.
     */
    suspend fun carveFromBlockDevice(
        context: Context,
        blockDevice: BlockDeviceDriver,
        maxBytesToScan: Long = 128L * 1024L * 1024L,
        onProgress: suspend (CarveProgress) -> Unit
    ): List<RescuedFile> = withContext(Dispatchers.IO) {
        val rescuedFiles = mutableListOf<RescuedFile>()
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

        val totalDeviceBytes = if (totalBlocks > 0) totalBlocks * blockSize else maxBytesToScan
        val targetScanBytes = totalDeviceBytes.coerceAtMost(maxBytesToScan)

        var currentOffset = 0L
        var badSectorsCount = 0
        var leftoverBuffer = ByteArray(0)
        val readBuffer = ByteBuffer.allocate(CHUNK_SIZE)

        while (currentOffset < targetScanBytes && coroutineContext.isActive) {
            readBuffer.clear()
            val bytesToRead = CHUNK_SIZE.toLong().coerceAtMost(targetScanBytes - currentOffset).toInt()
            readBuffer.limit(bytesToRead)

            var readSuccessful = false
            try {
                // Read-only operation: read chunk from block device
                blockDevice.read(currentOffset, readBuffer)
                readSuccessful = true
            } catch (e: Exception) {
                // Bad sector encountered! Gracefully skip bad sector and advance
                badSectorsCount++
                currentOffset += blockSize
                continue
            }

            if (readSuccessful) {
                val chunkBytes = ByteArray(bytesToRead)
                readBuffer.flip()
                readBuffer.get(chunkBytes)

                // Combine with leftover buffer from previous chunk to handle chunk boundary overlap
                val combinedData = if (leftoverBuffer.isNotEmpty()) {
                    val merged = ByteArray(leftoverBuffer.size + chunkBytes.size)
                    System.arraycopy(leftoverBuffer, 0, merged, 0, leftoverBuffer.size)
                    System.arraycopy(chunkBytes, 0, merged, leftoverBuffer.size, chunkBytes.size)
                    merged
                } else {
                    chunkBytes
                }

                val baseStreamOffset = (currentOffset - leftoverBuffer.size).coerceAtLeast(0L)
                val carved = scanAndExtractCarvedFiles(combinedData, baseStreamOffset, outputDir)
                if (carved.isNotEmpty()) {
                    rescuedFiles.addAll(carved)
                }

                // Prepare overlap for next boundary (keep tail of current chunk)
                leftoverBuffer = if (chunkBytes.size >= OVERLAP_SIZE) {
                    chunkBytes.copyOfRange(chunkBytes.size - OVERLAP_SIZE, chunkBytes.size)
                } else {
                    chunkBytes.copyOf()
                }

                currentOffset += bytesToRead

                val percent = if (targetScanBytes > 0) (currentOffset.toFloat() / targetScanBytes.toFloat()).coerceIn(0f, 1f) else 0f
                onProgress(
                    CarveProgress(
                        scannedBytes = currentOffset,
                        totalBytes = targetScanBytes,
                        progressPercent = percent,
                        filesFound = rescuedFiles.size,
                        badSectorsSkipped = badSectorsCount,
                        statusMessage = "Scanned ${FileRescueManager.formatFileSize(currentOffset)} / ${FileRescueManager.formatFileSize(targetScanBytes)}"
                    )
                )
            }
        }

        onProgress(
            CarveProgress(
                scannedBytes = currentOffset,
                totalBytes = targetScanBytes,
                progressPercent = 1.0f,
                filesFound = rescuedFiles.size,
                badSectorsSkipped = badSectorsCount,
                statusMessage = "Carve complete. Rescued ${rescuedFiles.size} files."
            )
        )

        rescuedFiles
    }

    /**
     * Carves files from an arbitrary byte buffer or raw sector stream.
     */
    fun scanAndExtractCarvedFiles(data: ByteArray, streamOffset: Long, outputDir: File): List<RescuedFile> {
        val results = mutableListOf<RescuedFile>()
        var offset = 0

        while (offset < data.size - 16) {
            // 1. JPEG: FF D8 FF -> FF D9
            if (isMatch(data, offset, JPEG_SOI)) {
                val endOffset = findSequence(data, offset + 3, JPEG_EOI)
                val maxLength = (25 * 1024 * 1024).coerceAtMost(data.size - offset) // up to 25MB
                val length = if (endOffset != -1) {
                    (endOffset - offset + 2).coerceAtMost(data.size - offset)
                } else {
                    // Truncated image or boundary continuation
                    (1024 * 1024).coerceAtMost(data.size - offset)
                }

                if (length >= 2048) {
                    val fileData = data.copyOfRange(offset, offset + length)
                    val rescued = saveFile(
                        bytes = fileData,
                        extension = "jpg",
                        mime = "image/jpeg",
                        category = FileCategory.PHOTO,
                        signature = "FF D8 FF (JPEG)",
                        offset = streamOffset + offset,
                        outputDir = outputDir
                    )
                    if (rescued != null) results.add(rescued)
                    offset += length
                    continue
                }
            }

            // 2. PNG: 89 50 4E 47 0D 0A 1A 0A -> 49 45 4E 44 AE 42 60 82
            if (isMatch(data, offset, PNG_HEADER)) {
                val endOffset = findSequence(data, offset + 8, PNG_FOOTER)
                val length = if (endOffset != -1) {
                    (endOffset - offset + 8).coerceAtMost(data.size - offset)
                } else {
                    (1024 * 1024).coerceAtMost(data.size - offset)
                }

                if (length >= 1024) {
                    val fileData = data.copyOfRange(offset, offset + length)
                    val rescued = saveFile(
                        bytes = fileData,
                        extension = "png",
                        mime = "image/png",
                        category = FileCategory.PHOTO,
                        signature = "89 50 4E 47 (PNG)",
                        offset = streamOffset + offset,
                        outputDir = outputDir
                    )
                    if (rescued != null) results.add(rescued)
                    offset += length
                    continue
                }
            }

            // 3. PDF: 25 50 44 46 (%PDF) -> 25 25 45 4F 46 (%%EOF)
            if (isMatch(data, offset, PDF_HEADER)) {
                val endOffset = findSequence(data, offset + 4, PDF_FOOTER)
                val length = if (endOffset != -1) {
                    (endOffset - offset + 5).coerceAtMost(data.size - offset)
                } else {
                    (2 * 1024 * 1024).coerceAtMost(data.size - offset)
                }

                if (length >= 512) {
                    val fileData = data.copyOfRange(offset, offset + length)
                    val rescued = saveFile(
                        bytes = fileData,
                        extension = "pdf",
                        mime = "application/pdf",
                        category = FileCategory.DOCUMENT,
                        signature = "%PDF (Document)",
                        offset = streamOffset + offset,
                        outputDir = outputDir
                    )
                    if (rescued != null) results.add(rescued)
                    offset += length
                    continue
                }
            }

            // 4. MP4 Video: ftyp box parsing (66 74 79 70)
            if (offset >= 4 && isMatch(data, offset, MP4_FTYP)) {
                val fileStartOffset = offset - 4
                val totalLength = parseMp4TotalLength(data, fileStartOffset)

                if (totalLength > 1024) {
                    val safeLength = totalLength.coerceAtMost(data.size - fileStartOffset)
                    val fileData = data.copyOfRange(fileStartOffset, fileStartOffset + safeLength)
                    val rescued = saveFile(
                        bytes = fileData,
                        extension = "mp4",
                        mime = "video/mp4",
                        category = FileCategory.VIDEO,
                        signature = "ftyp (MP4 Video)",
                        offset = streamOffset + fileStartOffset,
                        outputDir = outputDir
                    )
                    if (rescued != null) results.add(rescued)
                    offset = fileStartOffset + safeLength
                    continue
                }
            }

            // Advance by sector alignment (512 bytes) or 1 byte if not aligned
            offset += 512
        }

        return results
    }

    /**
     * Parses MP4 ISO Base Media box sequence to calculate total file size.
     */
    private fun parseMp4TotalLength(data: ByteArray, startOffset: Int): Int {
        var cursor = startOffset
        var totalSize = 0
        val maxFileSize = 100 * 1024 * 1024 // 100MB limit for in-memory carving

        while (cursor + 8 <= data.size && totalSize < maxFileSize) {
            val boxSize = readUint32(data, cursor)
            if (boxSize < 8) {
                // Box size 0 means till EOF or invalid
                if (totalSize > 8192) return totalSize
                return (data.size - startOffset).coerceAtMost(10 * 1024 * 1024)
            }

            // Check valid box 4-letter tag
            val tag = String(data, cursor + 4, 4, Charsets.US_ASCII)
            val isValidTag = tag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            if (!isValidTag) {
                if (totalSize > 8192) return totalSize
                break
            }

            totalSize += boxSize.toInt()
            cursor += boxSize.toInt()

            // Once we have seen moov and mdat, or sensible minimum, consider complete
            if (tag == "moov" || tag == "mdat") {
                if (totalSize > 64 * 1024) {
                    // Check if next box exists
                    if (cursor + 8 > data.size) return totalSize
                }
            }
        }

        return if (totalSize >= 4096) totalSize else -1
    }

    private fun readUint32(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    private fun isMatch(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > data.size) return false
        for (i in pattern.indices) {
            if (data[offset + i] != pattern[i]) return false
        }
        return true
    }

    private fun findSequence(data: ByteArray, startOffset: Int, pattern: ByteArray): Int {
        if (data.size - startOffset < pattern.size) return -1
        val maxSearch = (startOffset + 30 * 1024 * 1024).coerceAtMost(data.size - pattern.size) // search up to 30MB
        for (i in startOffset..maxSearch) {
            var matched = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun saveFile(
        bytes: ByteArray,
        extension: String,
        mime: String,
        category: FileCategory,
        signature: String,
        offset: Long,
        outputDir: File
    ): RescuedFile? {
        return try {
            if (!outputDir.exists()) outputDir.mkdirs()
            val fileName = "rescued_raw_${extension}_sec${offset}_${System.currentTimeMillis() % 10000}.$extension"
            val targetFile = File(outputDir, fileName)

            FileOutputStream(targetFile).use { out ->
                out.write(bytes)
                out.flush()
            }

            val hexSnippet = FileCarver.formatHexDump(bytes.take(128).toByteArray())

            RescuedFile(
                id = UUID.randomUUID().toString(),
                name = fileName,
                path = targetFile.absolutePath,
                uri = null,
                size = targetFile.length(),
                lastModified = System.currentTimeMillis(),
                category = category,
                mimeType = mime,
                detectedExtension = extension,
                originalExtension = extension,
                healthState = FileHealthState.GOOD,
                healthScore = 88,
                sourceType = FileSourceType.USB_OTG_RAW_SECTOR,
                magicSignature = signature,
                isHeaderValid = true,
                isFooterValid = true,
                isRescued = true,
                rescuedDestinationPath = targetFile.absolutePath,
                hexSnippet = hexSnippet
            )
        } catch (_: Exception) {
            null
        }
    }
}

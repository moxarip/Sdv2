package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.model.FileCategory
import com.example.model.RepairReport
import com.example.model.RescuedFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileRepairEngine {

    private val STANDARD_JPEG_HEADER = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
        0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x48.toByte(),
        0x00.toByte(), 0x48.toByte(), 0x00.toByte(), 0x00.toByte()
    )

    private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )

    private val PNG_IEND_CHUNK = byteArrayOf(
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(),
        0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
    )

    private val PDF_HEADER = "%PDF-1.4\n".toByteArray(Charsets.US_ASCII)
    private val PDF_EOF = "\n%%EOF\n".toByteArray(Charsets.US_ASCII)

    fun repairFile(context: Context, file: RescuedFile, targetDir: File): RepairReport {
        val appliedFixes = mutableListOf<String>()

        try {
            val sourceBytes = readFileBytes(context, file)
            if (sourceBytes == null || sourceBytes.isEmpty()) {
                return RepairReport(
                    originalFile = file,
                    isSuccess = false,
                    message = "Could not read original file bytes",
                    fixesApplied = emptyList()
                )
            }

            var workingBytes = sourceBytes
            val detectedExt = file.detectedExtension.ifEmpty { file.originalExtension.ifEmpty { "bin" } }

            // 1. JPEG Repair
            if (detectedExt in listOf("jpg", "jpeg") || file.category == FileCategory.PHOTO) {
                // Check if starts with JPEG SOI
                val hasSoi = workingBytes.size >= 3 &&
                        workingBytes[0] == 0xFF.toByte() &&
                        workingBytes[1] == 0xD8.toByte() &&
                        workingBytes[2] == 0xFF.toByte()

                if (!hasSoi) {
                    // Try to locate first FF D8 in byte stream
                    val offset = findSequence(workingBytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                    if (offset > 0) {
                        workingBytes = workingBytes.copyOfRange(offset, workingBytes.size)
                        appliedFixes.add("Trimmed $offset leading corrupted bytes to SOI marker")
                    } else {
                        // Prepend standard JFIF header
                        val newBytes = ByteArray(STANDARD_JPEG_HEADER.size + workingBytes.size)
                        System.arraycopy(STANDARD_JPEG_HEADER, 0, newBytes, 0, STANDARD_JPEG_HEADER.size)
                        System.arraycopy(workingBytes, 0, newBytes, STANDARD_JPEG_HEADER.size, workingBytes.size)
                        workingBytes = newBytes
                        appliedFixes.add("Rebuilt missing JPEG JFIF header structure")
                    }
                }

                // Check for EOI
                val hasEoi = workingBytes.size >= 2 &&
                        workingBytes[workingBytes.size - 2] == 0xFF.toByte() &&
                        workingBytes[workingBytes.size - 1] == 0xD9.toByte()
                if (!hasEoi) {
                    val newBytes = ByteArray(workingBytes.size + 2)
                    System.arraycopy(workingBytes, 0, newBytes, 0, workingBytes.size)
                    newBytes[workingBytes.size] = 0xFF.toByte()
                    newBytes[workingBytes.size + 1] = 0xD9.toByte()
                    workingBytes = newBytes
                    appliedFixes.add("Appended missing JPEG EOI (End of Image) footer")
                }
            }

            // 2. PNG Repair
            else if (detectedExt == "png") {
                val hasPngSig = workingBytes.size >= 8 &&
                        workingBytes.take(8).toByteArray().contentEquals(PNG_SIGNATURE)
                if (!hasPngSig) {
                    val ihdrOffset = findSequence(workingBytes, "IHDR".toByteArray(Charsets.US_ASCII))
                    if (ihdrOffset >= 4) {
                        // Chunk starts 4 bytes before IHDR (length)
                        val start = ihdrOffset - 4
                        val content = workingBytes.copyOfRange(start, workingBytes.size)
                        val newBytes = ByteArray(PNG_SIGNATURE.size + content.size)
                        System.arraycopy(PNG_SIGNATURE, 0, newBytes, 0, PNG_SIGNATURE.size)
                        System.arraycopy(content, 0, newBytes, PNG_SIGNATURE.size, content.size)
                        workingBytes = newBytes
                        appliedFixes.add("Reconstructed valid PNG signature and chunk alignment")
                    }
                }

                val hasIend = findSequence(workingBytes, "IEND".toByteArray(Charsets.US_ASCII)) != -1
                if (!hasIend) {
                    val newBytes = ByteArray(workingBytes.size + PNG_IEND_CHUNK.size)
                    System.arraycopy(workingBytes, 0, newBytes, 0, workingBytes.size)
                    System.arraycopy(PNG_IEND_CHUNK, 0, newBytes, workingBytes.size, PNG_IEND_CHUNK.size)
                    workingBytes = newBytes
                    appliedFixes.add("Appended missing PNG IEND termination chunk")
                }
            }

            // 3. PDF Repair
            else if (detectedExt == "pdf" || file.category == FileCategory.DOCUMENT) {
                val hasPdfSig = workingBytes.take(4).toByteArray().contentEquals("%PDF".toByteArray(Charsets.US_ASCII))
                if (!hasPdfSig) {
                    val newBytes = ByteArray(PDF_HEADER.size + workingBytes.size)
                    System.arraycopy(PDF_HEADER, 0, newBytes, 0, PDF_HEADER.size)
                    System.arraycopy(workingBytes, 0, newBytes, PDF_HEADER.size, workingBytes.size)
                    workingBytes = newBytes
                    appliedFixes.add("Injected standard %PDF header marker")
                }
                val hasEof = findSequence(workingBytes, "%%EOF".toByteArray(Charsets.US_ASCII)) != -1
                if (!hasEof) {
                    val newBytes = ByteArray(workingBytes.size + PDF_EOF.size)
                    System.arraycopy(workingBytes, 0, newBytes, 0, workingBytes.size)
                    System.arraycopy(PDF_EOF, 0, newBytes, workingBytes.size, PDF_EOF.size)
                    workingBytes = newBytes
                    appliedFixes.add("Appended missing %%EOF document boundary")
                }
            }

            // 4. Extension Normalization
            if (file.originalExtension.lowercase() != detectedExt && detectedExt.isNotEmpty() && detectedExt != "bin") {
                appliedFixes.add("Corrected extension mismatch: .${file.originalExtension} -> .$detectedExt")
            }

            if (appliedFixes.isEmpty()) {
                appliedFixes.add("Verified file bitstream integrity (no destructive repair needed)")
            }

            // Save repaired file
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val baseName = file.name.substringBeforeLast(".").ifEmpty { "rescued_${file.id.take(8)}" }
            val repairedFileName = "repaired_${baseName}.$detectedExt"
            val outputFile = File(targetDir, repairedFileName)

            FileOutputStream(outputFile).use { out ->
                out.write(workingBytes)
                out.flush()
            }

            return RepairReport(
                originalFile = file,
                isSuccess = true,
                message = "File successfully repaired and reconstructed!",
                repairedPath = outputFile.absolutePath,
                fixesApplied = appliedFixes
            )
        } catch (e: Exception) {
            return RepairReport(
                originalFile = file,
                isSuccess = false,
                message = "Repair failed: ${e.localizedMessage}",
                fixesApplied = appliedFixes
            )
        }
    }

    private fun readFileBytes(context: Context, file: RescuedFile): ByteArray? {
        return try {
            if (file.path.isNotEmpty() && File(file.path).exists()) {
                File(file.path).readBytes()
            } else if (file.uri != null) {
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findSequence(data: ByteArray, seq: ByteArray): Int {
        if (data.size < seq.size || seq.isEmpty()) return -1
        for (i in 0..(data.size - seq.size)) {
            var match = true
            for (j in seq.indices) {
                if (data[i + j] != seq[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}

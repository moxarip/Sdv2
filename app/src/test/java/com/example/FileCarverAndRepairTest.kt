package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.FileCarver
import com.example.engine.FileRepairEngine
import com.example.engine.FileRescueManager
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileCarverAndRepairTest {

    @Test
    fun testAppNameString() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OpenFileRescue", appName)
    }

    @Test
    fun testJpegCarvingAndHealthDetection() {
        val sampleJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
            0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte()
        )
        val validTail = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xD9.toByte())

        val result = FileCarver.analyzeBytes(
            header = sampleJpeg,
            tail = validTail,
            declaredExtension = "dat",
            fileSize = 1024L
        )

        assertEquals(FileCategory.PHOTO, result.category)
        assertEquals("jpg", result.detectedExtension)
        assertEquals(FileHealthState.EXCELLENT, result.healthState)
        assertTrue(result.isHeaderValid)
        assertTrue(result.isFooterValid)
        assertTrue(result.healthScore >= 90)
    }

    @Test
    fun testCorruptedJpegRepair() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "repair_test_dir").apply { mkdirs() }

        // Create a simulated truncated JPEG (missing EOI footer)
        val truncatedJpegFile = File(tempDir, "corrupted_photo.tmp")
        val brokenBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte()
        )
        truncatedJpegFile.writeBytes(brokenBytes)

        val rescuedModel = RescuedFile(
            id = "test_1",
            name = "corrupted_photo.tmp",
            path = truncatedJpegFile.absolutePath,
            size = brokenBytes.size.toLong(),
            lastModified = System.currentTimeMillis(),
            category = FileCategory.PHOTO,
            mimeType = "image/jpeg",
            detectedExtension = "jpg",
            originalExtension = "tmp",
            healthState = FileHealthState.REPAIRABLE,
            healthScore = 75,
            sourceType = FileSourceType.DEEP_CARVED
        )

        val targetDir = File(tempDir, "rescued_output").apply { mkdirs() }
        val report = FileRepairEngine.repairFile(context, rescuedModel, targetDir)

        assertTrue("Repair should succeed", report.isSuccess)
        assertNotNull(report.repairedPath)
        val repairedFile = File(report.repairedPath!!)
        assertTrue(repairedFile.exists())

        // Validate repaired file ends with FF D9
        val repairedBytes = repairedFile.readBytes()
        assertTrue(repairedBytes.size >= brokenBytes.size + 2)
        assertEquals(0xFF.toByte(), repairedBytes[repairedBytes.size - 2])
        assertEquals(0xD9.toByte(), repairedBytes[repairedBytes.size - 1])
    }

    @Test
    fun testPngCarving() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
        )
        val pngTail = "IEND\u00AEB`\u0082".toByteArray(Charsets.ISO_8859_1)

        val result = FileCarver.analyzeBytes(
            header = pngHeader,
            tail = pngTail,
            declaredExtension = "bin",
            fileSize = 2048L
        )

        assertEquals(FileCategory.PHOTO, result.category)
        assertEquals("png", result.detectedExtension)
        assertEquals(FileHealthState.EXCELLENT, result.healthState)
    }

    @Test
    fun testPdfCarving() {
        val pdfHeader = "%PDF-1.7".toByteArray(Charsets.US_ASCII)
        val pdfTail = "startxref\n12345\n%%EOF\n".toByteArray(Charsets.US_ASCII)

        val result = FileCarver.analyzeBytes(
            header = pdfHeader,
            tail = pdfTail,
            declaredExtension = "pdf",
            fileSize = 5000L
        )

        assertEquals(FileCategory.DOCUMENT, result.category)
        assertEquals("pdf", result.detectedExtension)
        assertEquals(FileHealthState.EXCELLENT, result.healthState)
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", FileRescueManager.formatFileSize(0))
        assertEquals("1.0 KB", FileRescueManager.formatFileSize(1024))
        assertEquals("2.5 MB", FileRescueManager.formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testFileCarverEngineExtraction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDir = File(context.cacheDir, "carver_test_output").apply { mkdirs() }

        // Construct a synthetic buffer containing JPEG, PNG, PDF, and MP4 files
        val buffer = java.io.ByteArrayOutputStream()

        // Sector padding
        buffer.write(ByteArray(512))

        // 1. JPEG (FF D8 FF ... FF D9)
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val jpegBody = ByteArray(2500) { 0x55.toByte() }
        val jpegTail = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        buffer.write(jpegHeader)
        buffer.write(jpegBody)
        buffer.write(jpegTail)

        // Sector alignment
        buffer.write(ByteArray(512))

        // 2. PNG (89 50 4E 47 ... IEND)
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        val pngBody = ByteArray(1500) { 0x33.toByte() }
        val pngTail = byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte())
        buffer.write(pngHeader)
        buffer.write(pngBody)
        buffer.write(pngTail)

        // Sector alignment
        buffer.write(ByteArray(512))

        // 3. PDF (%PDF ... %%EOF)
        val pdfHeader = "%PDF-1.4".toByteArray(Charsets.US_ASCII)
        val pdfBody = ByteArray(1000) { 0x20.toByte() }
        val pdfTail = "%%EOF".toByteArray(Charsets.US_ASCII)
        buffer.write(pdfHeader)
        buffer.write(pdfBody)
        buffer.write(pdfTail)

        val rawData = buffer.toByteArray()
        val carved = com.example.engine.FileCarverEngine.scanAndExtractCarvedFiles(rawData, 0L, outputDir)

        assertTrue("Should extract at least 3 files (JPEG, PNG, PDF)", carved.size >= 3)
        assertTrue("Contains JPEG", carved.any { it.detectedExtension == "jpg" })
        assertTrue("Contains PNG", carved.any { it.detectedExtension == "png" })
        assertTrue("Contains PDF", carved.any { it.detectedExtension == "pdf" })
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.FileCarver
import com.example.engine.FileCarverEngine
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun testMultiFormatExtractionAndSubfolders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDir = File(context.cacheDir, "carver_multi_${System.currentTimeMillis()}").apply { mkdirs() }

        val buffer = ByteArrayOutputStream()
        buffer.write(ByteArray(256))

        // 1. JPEG (FF D8 FF E0 ... FF D9) -> Images/
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val jpegBody = ByteArray(2048) { 0x55.toByte() }
        val jpegTail = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        buffer.write(jpegHeader)
        buffer.write(jpegBody)
        buffer.write(jpegTail)
        buffer.write(ByteArray(128))

        // 2. PNG (89 50 4E 47 ... IEND) -> Images/
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        val pngBody = ByteArray(1500) { 0x33.toByte() }
        val pngTail = byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte())
        buffer.write(pngHeader)
        buffer.write(pngBody)
        buffer.write(pngTail)
        buffer.write(ByteArray(128))

        // 3. WEBP (RIFF + 4B LE size + WEBP) -> Images/
        val webpPayload = ByteArray(1000) { 0x77.toByte() }
        val webpBuf = ByteBuffer.allocate(12 + webpPayload.size).order(ByteOrder.LITTLE_ENDIAN)
        webpBuf.put("RIFF".toByteArray(Charsets.US_ASCII))
        webpBuf.putInt(4 + webpPayload.size)
        webpBuf.put("WEBP".toByteArray(Charsets.US_ASCII))
        webpBuf.put(webpPayload)
        buffer.write(webpBuf.array())
        buffer.write(ByteArray(128))

        // 4. PDF (%PDF-1.5 ... %%EOF) -> Documents/
        val pdfHeader = "%PDF-1.5\n".toByteArray(Charsets.US_ASCII)
        val pdfBody = ByteArray(1000) { 0x20.toByte() }
        val pdfTail = "%%EOF\n".toByteArray(Charsets.US_ASCII)
        buffer.write(pdfHeader)
        buffer.write(pdfBody)
        buffer.write(pdfTail)
        buffer.write(ByteArray(128))

        // 5. SQLite 3 Database (Page Size = 4096, Page Count = 2 -> 8192 bytes) -> Databases/
        val sqliteBytes = ByteArray(8192)
        val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(sqliteMagic, 0, sqliteBytes, 0, 16)
        // Page size 4096 (0x1000 Big-Endian at offset 16..17)
        sqliteBytes[16] = 0x10.toByte()
        sqliteBytes[17] = 0x00.toByte()
        // Page count 2 (0x00000002 Big-Endian at offset 28..31)
        sqliteBytes[28] = 0x00.toByte()
        sqliteBytes[29] = 0x00.toByte()
        sqliteBytes[30] = 0x00.toByte()
        sqliteBytes[31] = 0x02.toByte()
        buffer.write(sqliteBytes)

        val rawData = buffer.toByteArray()
        val carved = FileCarverEngine.scanAndExtractCarvedFiles(rawData, 0L, outputDir)

        assertTrue("Should extract all 5 files (JPEG, PNG, WEBP, PDF, SQLite)", carved.size >= 5)
        assertTrue("Contains JPEG", carved.any { it.detectedExtension == "jpg" && it.path.contains("Images") })
        assertTrue("Contains PNG", carved.any { it.detectedExtension == "png" && it.path.contains("Images") })
        assertTrue("Contains WEBP", carved.any { it.detectedExtension == "webp" && it.path.contains("Images") })
        assertTrue("Contains PDF", carved.any { it.detectedExtension == "pdf" && it.path.contains("Documents") })
        val dbFile = carved.find { it.detectedExtension == "db" }
        assertNotNull("Contains SQLite DB", dbFile)
        assertEquals(8192L, dbFile!!.size)
        assertTrue("DB saved in Databases subfolder", dbFile.path.contains("Databases"))
    }

    @Test
    fun testStreamingStateMachineMultiChunkMp4Video() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDir = File(context.cacheDir, "streaming_mp4_${System.currentTimeMillis()}").apply { mkdirs() }
        val session = FileCarverEngine.StreamingCarverSession(outputDir)

        // Build a synthetic MP4 file spanning 3 separate chunks:
        // Box 1: ftyp (24 bytes)
        // Box 2: moov (1024 bytes)
        // Box 3: mdat (12000 bytes) -> Total MP4 size = 13048 bytes
        val ftypSize = 24
        val moovSize = 1024
        val mdatSize = 12000
        val totalMp4Size = ftypSize + moovSize + mdatSize

        val mp4Buffer = ByteBuffer.allocate(totalMp4Size).order(ByteOrder.BIG_ENDIAN)
        // ftyp box
        mp4Buffer.putInt(ftypSize)
        mp4Buffer.put("ftyp".toByteArray(Charsets.US_ASCII))
        mp4Buffer.put("isom".toByteArray(Charsets.US_ASCII))
        mp4Buffer.put(ByteArray(ftypSize - 12))
        // moov box
        mp4Buffer.putInt(moovSize)
        mp4Buffer.put("moov".toByteArray(Charsets.US_ASCII))
        mp4Buffer.put(ByteArray(moovSize - 8) { 0x11.toByte() })
        // mdat box
        mp4Buffer.putInt(mdatSize)
        mp4Buffer.put("mdat".toByteArray(Charsets.US_ASCII))
        mp4Buffer.put(ByteArray(mdatSize - 8) { 0x22.toByte() })

        val fullMp4 = mp4Buffer.array()

        // Split MP4 across 3 chunks + a trailing non-MP4 chunk + a zero block
        val chunk1 = fullMp4.copyOfRange(0, 4096)
        val chunk2 = fullMp4.copyOfRange(4096, 9000)
        val chunk3 = fullMp4.copyOfRange(9000, totalMp4Size) + ByteArray(512) { 0xFF.toByte() }
        val chunk4Zero = ByteArray(4096) { 0x00.toByte() }

        session.feedChunk(chunk1, chunk1.size, 0L)
        session.feedChunk(chunk2, chunk2.size, 4096L)
        session.feedChunk(chunk3, chunk3.size, 9000L)
        session.feedChunk(chunk4Zero, chunk4Zero.size, 14000L)
        session.finishSession()

        assertEquals("Should skip 1 zero block", 1, session.zeroBlocksSkipped)
        val mp4Rescued = session.rescuedFiles.find { it.detectedExtension == "mp4" }
        assertNotNull("Should reconstruct MP4 across multiple chunks", mp4Rescued)
        assertEquals("MP4 size should match sum of ftyp + moov + mdat boxes", totalMp4Size.toLong(), mp4Rescued!!.size)
        assertTrue("MP4 should be saved in Videos subfolder", mp4Rescued.path.contains("Videos"))

        // Also test ZIP archive creation from rescued files
        val zipFile = FileRescueManager.createZipArchive(context, session.rescuedFiles)
        assertNotNull("ZIP archive should be created", zipFile)
        assertTrue("ZIP archive should exist and be non-empty", zipFile!!.exists() && zipFile.length() > 0L)
    }
}

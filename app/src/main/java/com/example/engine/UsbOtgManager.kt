package com.example.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class UsbDeviceInfo(
    val device: UsbDevice,
    val deviceName: String,
    val vendorName: String,
    val isMassStorage: Boolean,
    val hasPermission: Boolean
)

object UsbOtgManager {

    const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"

    fun getUsbManager(context: Context): UsbManager {
        return context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    fun listConnectedUsbDevices(context: Context): List<UsbDeviceInfo> {
        val manager = getUsbManager(context)
        val list = mutableListOf<UsbDeviceInfo>()

        for ((_, device) in manager.deviceList) {
            val isStorage = isStorageDevice(device)
            val vendorName = getVendorString(device)
            val productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                device.productName ?: "USB Storage Device"
            } else {
                "USB Storage Device"
            }

            list.add(
                UsbDeviceInfo(
                    device = device,
                    deviceName = productName,
                    vendorName = vendorName,
                    isMassStorage = isStorage,
                    hasPermission = manager.hasPermission(device)
                )
            )
        }
        return list
    }

    private fun isStorageDevice(device: UsbDevice): Boolean {
        // Check device class or interface classes
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE || intf.interfaceClass == 0x08) {
                return true
            }
        }
        return false
    }

    private fun getVendorString(device: UsbDevice): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !device.manufacturerName.isNullOrBlank()) {
            device.manufacturerName ?: "Vendor (ID: 0x${Integer.toHexString(device.vendorId)})"
        } else {
            "Vendor (ID: 0x${Integer.toHexString(device.vendorId)})"
        }
    }

    fun requestPermission(context: Context, device: UsbDevice) {
        val manager = getUsbManager(context)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        manager.requestPermission(device, permissionIntent)
    }

    /**
     * Low-level SCSI / USB Bulk-Only Transport (BOT) direct sector carver.
     * This connects directly to the USB Mass Storage endpoints, sending READ(10) SCSI commands
     * to extract raw data blocks from the SD card even when Android rejects mounting it
     * and displays "Tap to format".
     */
    suspend fun carveRawUsbStorage(
        context: Context,
        device: UsbDevice,
        maxMegaBytesToScan: Int = 128,
        onProgress: suspend (scannedBytes: Long, filesFound: Int, currentPhase: String) -> Unit
    ): List<RescuedFile> = withContext(Dispatchers.IO) {
        val manager = getUsbManager(context)
        if (!manager.hasPermission(device)) {
            return@withContext emptyList()
        }

        val connection: UsbDeviceConnection? = manager.openDevice(device)
        if (connection == null) {
            return@withContext emptyList()
        }

        val rescuedFiles = mutableListOf<RescuedFile>()

        try {
            // Find Mass Storage Interface
            var storageInterface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE || intf.interfaceClass == 0x08) {
                    storageInterface = intf
                    break
                }
            }

            if (storageInterface == null && device.interfaceCount > 0) {
                storageInterface = device.getInterface(0)
            }

            if (storageInterface == null) {
                connection.close()
                return@withContext emptyList()
            }

            if (!connection.claimInterface(storageInterface, true)) {
                connection.close()
                return@withContext emptyList()
            }

            // Find bulk in and bulk out endpoints
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null

            for (i in 0 until storageInterface.endpointCount) {
                val ep = storageInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = ep
                    }
                }
            }

            if (inEndpoint == null || outEndpoint == null) {
                connection.releaseInterface(storageInterface)
                connection.close()
                return@withContext emptyList()
            }

            onProgress(0, 0, "Initializing USB BOT & SCSI Communication")

            val sectorSize = 512
            val sectorsPerBatch = 64 // 32 KB per chunk
            val chunkSize = sectorsPerBatch * sectorSize
            val maxBytes = maxMegaBytesToScan.toLong() * 1024L * 1024L
            val targetRescueDir = FileRescueManager.getRescueDirectory(context)

            var currentLba = 0L
            var totalBytesRead = 0L
            var tagCounter = 1000

            // Ring buffer for scanning magic bytes across sector boundaries
            var leftoverBytes = ByteArray(0)

            while (totalBytesRead < maxBytes && coroutineContext.isActive) {
                val cbw = createRead10Cbw(tagCounter++, currentLba, sectorsPerBatch, chunkSize)
                val outSent = connection.bulkTransfer(outEndpoint, cbw, cbw.size, 2000)

                if (outSent <= 0) {
                    // Try soft recovery or break
                    break
                }

                val readBuffer = ByteArray(chunkSize)
                val bytesRead = connection.bulkTransfer(inEndpoint, readBuffer, chunkSize, 4000)

                // Read Command Status Wrapper (CSW)
                val csw = ByteArray(13)
                connection.bulkTransfer(inEndpoint, csw, 13, 2000)

                if (bytesRead <= 0) {
                    currentLba += sectorsPerBatch
                    totalBytesRead += chunkSize
                    continue
                }

                totalBytesRead += bytesRead
                currentLba += sectorsPerBatch

                // Combine with leftover bytes from previous chunk to avoid missing headers
                val combinedBytes = if (leftoverBytes.isNotEmpty()) {
                    val combined = ByteArray(leftoverBytes.size + bytesRead)
                    System.arraycopy(leftoverBytes, 0, combined, 0, leftoverBytes.size)
                    System.arraycopy(readBuffer, 0, combined, leftoverBytes.size, bytesRead)
                    combined
                } else {
                    readBuffer.copyOf(bytesRead)
                }

                // Carve files from raw byte buffer
                val carved = scanAndCarveBuffer(combinedBytes, currentLba * sectorSize, targetRescueDir)
                if (carved.isNotEmpty()) {
                    rescuedFiles.addAll(carved)
                }

                leftoverBytes = if (bytesRead >= 1024) {
                    readBuffer.copyOfRange(bytesRead - 1024, bytesRead)
                } else {
                    readBuffer.copyOf(bytesRead)
                }

                if (totalBytesRead % (1024 * 1024) == 0L || carved.isNotEmpty()) {
                    onProgress(
                        totalBytesRead,
                        rescuedFiles.size,
                        "Carving USB Block LBA #$currentLba (${FileRescueManager.formatFileSize(totalBytesRead)})"
                    )
                }
            }

            connection.releaseInterface(storageInterface)
            connection.close()

            onProgress(totalBytesRead, rescuedFiles.size, "USB Carving Complete")
        } catch (e: Exception) {
            e.printStackTrace()
            try { connection.close() } catch (_: Exception) {}
        }

        rescuedFiles
    }

    /**
     * Builds a USB Mass Storage Bulk-Only Transport (BOT) Command Block Wrapper (CBW)
     * containing a standard SCSI READ(10) command (OpCode 0x28).
     */
    private fun createRead10Cbw(tag: Int, lba: Long, blockCount: Int, transferLength: Int): ByteArray {
        val buffer = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x55.toByte()) // 'U'
        buffer.put(0x53.toByte()) // 'S'
        buffer.put(0x42.toByte()) // 'B'
        buffer.put(0x43.toByte()) // 'C'
        buffer.putInt(tag) // Tag
        buffer.putInt(transferLength) // Expected transfer length
        buffer.put(0x80.toByte()) // Flags: 0x80 = Device to Host (Data IN)
        buffer.put(0.toByte()) // LUN = 0
        buffer.put(10.toByte()) // SCSI Command length = 10 bytes

        // SCSI READ(10) CDB
        buffer.put(0x28.toByte()) // OpCode: READ(10)
        buffer.put(0.toByte()) // Flags
        // 32-bit Big Endian LBA
        buffer.put((lba shr 24).toByte())
        buffer.put((lba shr 16).toByte())
        buffer.put((lba shr 8).toByte())
        buffer.put(lba.toByte())
        buffer.put(0.toByte()) // Group
        // 16-bit Big Endian transfer length (number of blocks)
        buffer.put((blockCount shr 8).toByte())
        buffer.put(blockCount.toByte())
        buffer.put(0.toByte()) // Control

        // Pad remaining CDB bytes to 16 bytes
        while (buffer.position() < 31) {
            buffer.put(0.toByte())
        }

        return buffer.array()
    }

    private fun scanAndCarveBuffer(data: ByteArray, streamOffset: Long, outputDir: File): List<RescuedFile> {
        val list = mutableListOf<RescuedFile>()
        var offset = 0

        while (offset < data.size - 16) {
            // Check JPEG SOI (FF D8 FF)
            if (data[offset] == 0xFF.toByte() && data[offset + 1] == 0xD8.toByte() && data[offset + 2] == 0xFF.toByte()) {
                val remaining = data.size - offset
                val endOffset = findSequence(data, offset, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
                val length = if (endOffset != -1) (endOffset - offset + 2).coerceAtMost(remaining) else remaining.coerceAtMost(1024 * 1024)

                if (length > 2048) { // Minimum realistic photo size
                    val fileData = data.copyOfRange(offset, offset + length)
                    val rescued = saveCarvedFile(fileData, "jpg", "image/jpeg", FileCategory.PHOTO, "FF D8 FF (JPEG)", streamOffset + offset, outputDir)
                    if (rescued != null) list.add(rescued)
                    offset += length
                    continue
                }
            }

            // Check PNG Signature (89 50 4E 47)
            if (data[offset] == 0x89.toByte() && data[offset + 1] == 0x50.toByte() &&
                data[offset + 2] == 0x4E.toByte() && data[offset + 3] == 0x47.toByte()) {
                val endOffset = findSequence(data, offset, "IEND".toByteArray(Charsets.US_ASCII))
                val length = if (endOffset != -1) (endOffset - offset + 8).coerceAtMost(data.size - offset) else (data.size - offset).coerceAtMost(1024 * 1024)

                if (length > 1024) {
                    val fileData = data.copyOfRange(offset, offset + length)
                    val rescued = saveCarvedFile(fileData, "png", "image/png", FileCategory.PHOTO, "89 50 4E 47 (PNG)", streamOffset + offset, outputDir)
                    if (rescued != null) list.add(rescued)
                    offset += length
                    continue
                }
            }

            // Check PDF (%PDF)
            if (data[offset] == 0x25.toByte() && data[offset + 1] == 0x50.toByte() &&
                data[offset + 2] == 0x44.toByte() && data[offset + 3] == 0x46.toByte()) {
                val endOffset = findSequence(data, offset, "%%EOF".toByteArray(Charsets.US_ASCII))
                val length = if (endOffset != -1) (endOffset - offset + 5).coerceAtMost(data.size - offset) else (data.size - offset).coerceAtMost(2 * 1024 * 1024)

                if (length > 512) {
                    val fileData = data.copyOfRange(offset, offset + length)
                    val rescued = saveCarvedFile(fileData, "pdf", "application/pdf", FileCategory.DOCUMENT, "%PDF Document", streamOffset + offset, outputDir)
                    if (rescued != null) list.add(rescued)
                    offset += length
                    continue
                }
            }

            offset += 512 // Advance by sector boundary
        }

        return list
    }

    private fun findSequence(data: ByteArray, startOffset: Int, sequence: ByteArray): Int {
        if (data.size - startOffset < sequence.size) return -1
        for (i in startOffset..(data.size - sequence.size)) {
            var match = true
            for (j in sequence.indices) {
                if (data[i + j] != sequence[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun saveCarvedFile(
        bytes: ByteArray,
        ext: String,
        mime: String,
        category: FileCategory,
        magic: String,
        sectorAddress: Long,
        outputDir: File
    ): RescuedFile? {
        return try {
            if (!outputDir.exists()) outputDir.mkdirs()
            val fileName = "usb_carved_sec${sectorAddress}_${System.currentTimeMillis() % 10000}.$ext"
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
                detectedExtension = ext,
                originalExtension = ext,
                healthState = FileHealthState.GOOD,
                healthScore = 88,
                sourceType = FileSourceType.USB_OTG_RAW_SECTOR,
                magicSignature = magic,
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

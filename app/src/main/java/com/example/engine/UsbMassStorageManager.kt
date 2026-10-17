package com.example.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Direct USB Mass Storage & OTG Hardware Connection Manager.
 *
 * Implements a dual-mode strategy to rescue data from damaged USB sticks,
 * external hard drives, and SD cards that Android refuses to mount and prompts to format:
 *
 * Attempt 1: Filesystem Level (Bypass Android mount restrictions via libaums FAT/FAT32/exFAT).
 * Attempt 2: Raw Block Device Level (SCSI BlockDeviceDriver carving via FileCarverEngine).
 *
 * Strictly Read-Only: Never executes any write operations to avoid overwriting damaged sectors.
 */
object UsbMassStorageManager {

    const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"

    data class RescueSessionResult(
        val isFilesystemMode: Boolean,
        val rescuedFiles: List<RescuedFile>,
        val errorMessage: String? = null
    )

    fun getUsbManager(context: Context): UsbManager {
        return context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    /**
     * Discovers all connected USB mass storage devices and SD card readers.
     */
    fun discoverDevices(context: Context): List<UsbMassStorageDevice> {
        return try {
            UsbMassStorageDevice.getMassStorageDevices(context).toList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Checks if hardware permission has been granted for the device.
     */
    fun hasPermission(context: Context, device: UsbMassStorageDevice): Boolean {
        val manager = getUsbManager(context)
        return manager.hasPermission(device.usbDevice)
    }

    /**
     * Requests direct hardware access permission using PendingIntent.FLAG_MUTABLE.
     */
    fun requestPermission(context: Context, device: UsbMassStorageDevice) {
        val manager = getUsbManager(context)
        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName) // Mandatory on Android 14 to prevent FLAG_MUTABLE crash
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            permissionIntent,
            flags
        )
        try {
            manager.requestPermission(device.usbDevice, pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Dual-Mode Rescue Pipeline:
     * - Attempt 1: Tries device.init() and reading partitions[0].fileSystem.
     * - Attempt 2: If Attempt 1 fails (RAW/unsupported/format error), falls back to
     *   raw sector carving via device.partitions[0] (BlockDeviceDriver) or direct SCSI BOT.
     */
    suspend fun rescueFromDamagedUsb(
        context: Context,
        device: UsbMassStorageDevice,
        maxBytesToScan: Long = 128L * 1024L * 1024L,
        onProgress: suspend (progressPercent: Float, filesFound: Int, statusMessage: String) -> Unit
    ): RescueSessionResult = withContext(Dispatchers.IO) {
        if (!hasPermission(context, device)) {
            return@withContext RescueSessionResult(
                isFilesystemMode = false,
                rescuedFiles = emptyList(),
                errorMessage = "USB hardware permission not granted"
            )
        }

        var isInitialized = false

        try {
            onProgress(0.05f, 0, "Attempt 1: Initializing Mass Storage & Partition Table...")
            // Attempt 1: Initialize device and inspect partitions
            device.init()
            isInitialized = true

            val partitions = device.partitions
            if (partitions.isNotEmpty()) {
                val firstPartition = partitions[0]
                val fileSystem: FileSystem? = try {
                    firstPartition.fileSystem
                } catch (_: Exception) {
                    null
                }

                if (fileSystem != null) {
                    onProgress(0.15f, 0, "Attempt 1 Success: Filesystem detected! Bypassing Android mount...")
                    val extractedFiles = mutableListOf<RescuedFile>()
                    val root = fileSystem.rootDirectory
                    extractFilesRecursively(context, root, extractedFiles, onProgress)

                    if (extractedFiles.isNotEmpty()) {
                        onProgress(1.0f, extractedFiles.size, "Rescue complete via direct filesystem read!")
                        return@withContext RescueSessionResult(
                            isFilesystemMode = true,
                            rescuedFiles = extractedFiles
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Attempt 1 failed (Corrupted partition table or RAW storage requiring format)
            e.printStackTrace()
        }

        // Attempt 2: Raw Block Device Mode (Direct Sector Carving)
        onProgress(0.20f, 0, "Attempt 2: Partition unreadable (RAW). Switching to Raw Block Device (SCSI)...")

        val partitions = try { device.partitions } catch (_: Exception) { emptyList() }
        val rescued = if (partitions.isNotEmpty()) {
            val blockDriver = partitions[0]
            FileCarverEngine.carveFromBlockDevice(
                context = context,
                blockDevice = blockDriver,
                maxBytesToScan = maxBytesToScan,
                onProgress = { progress ->
                    onProgress(
                        progress.progressPercent,
                        progress.filesFound,
                        "Raw Carving: ${progress.statusMessage} (Bad sectors skipped: ${progress.badSectorsSkipped})"
                    )
                }
            )
        } else {
            // If device.init() failed to parse partitions at all, use direct USB Bulk-Only Transport (BOT)
            UsbOtgManager.carveRawUsbStorage(
                context = context,
                device = device.usbDevice,
                maxMegaBytesToScan = (maxBytesToScan / (1024 * 1024)).toInt(),
                onProgress = { scannedBytes, filesCount, phase ->
                    val percent = (scannedBytes.toFloat() / maxBytesToScan.toFloat()).coerceIn(0f, 1f)
                    onProgress(percent, filesCount, phase)
                }
            )
        }

        try {
            if (isInitialized) device.close()
        } catch (_: Exception) {}

        RescueSessionResult(
            isFilesystemMode = false,
            rescuedFiles = rescued
        )
    }

    private suspend fun extractFilesRecursively(
        context: Context,
        directory: UsbFile,
        outList: MutableList<RescuedFile>,
        onProgress: suspend (Float, Int, String) -> Unit
    ) {
        val rescueDir = FileRescueManager.getRescueDirectory(context)
        val files = try {
            directory.listFiles()
        } catch (_: Exception) {
            emptyArray<UsbFile>()
        }

        for (file in files) {
            if (file.isDirectory) {
                extractFilesRecursively(context, file, outList, onProgress)
            } else {
                val rescued = copyUsbFileToPhone(file, rescueDir)
                if (rescued != null) {
                    outList.add(rescued)
                    onProgress(0.5f, outList.size, "Salvaged: ${file.name}")
                }
            }
        }
    }

    private fun copyUsbFileToPhone(usbFile: UsbFile, outputDir: File): RescuedFile? {
        return try {
            if (!outputDir.exists()) outputDir.mkdirs()
            val cleanName = usbFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(outputDir, "usb_fs_${cleanName}")

            val fileSize = usbFile.length
            val buffer = ByteBuffer.allocate(32 * 1024) // 32KB buffer

            FileOutputStream(targetFile).use { outStream ->
                var offset = 0L
                while (offset < fileSize) {
                    buffer.clear()
                    val toRead = 32 * 1024L.coerceAtMost(fileSize - offset).toInt()
                    buffer.limit(toRead)
                    usbFile.read(offset, buffer)
                    buffer.flip()
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    outStream.write(bytes)
                    offset += bytes.size
                }
                outStream.flush()
            }

            val ext = targetFile.extension.lowercase()
            val category = when (ext) {
                "jpg", "jpeg", "png", "webp", "gif" -> FileCategory.PHOTO
                "mp4", "mkv", "avi", "mov" -> FileCategory.VIDEO
                "mp3", "m4a", "wav", "flac" -> FileCategory.AUDIO
                "pdf", "doc", "docx", "txt" -> FileCategory.DOCUMENT
                "zip", "rar", "7z" -> FileCategory.ARCHIVE
                else -> FileCategory.OTHER
            }

            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }

            RescuedFile(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                path = targetFile.absolutePath,
                uri = null,
                size = targetFile.length(),
                lastModified = System.currentTimeMillis(),
                category = category,
                mimeType = mime,
                detectedExtension = ext,
                originalExtension = ext,
                healthState = FileHealthState.EXCELLENT,
                healthScore = 98,
                sourceType = FileSourceType.USB_OTG_RAW_SECTOR,
                magicSignature = "Direct Filesystem Read",
                isHeaderValid = true,
                isFooterValid = true,
                isRescued = true,
                rescuedDestinationPath = targetFile.absolutePath
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

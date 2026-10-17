package com.example.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
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
 * Attempt 2: Raw Block Device Level (Full-capacity SCSI BlockDeviceDriver streaming carving via FileCarverEngine).
 *
 * Strictly Read-Only during rescue: Never executes any write operations to avoid overwriting damaged sectors.
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
     * - Attempt 1: Tries device.init() and reading partitions[0].fileSystem (unless resuming raw sector checkpoint).
     * - Attempt 2: If Attempt 1 fails (RAW/unsupported/format error), switches to full-capacity
     *   raw sector carving via BlockDeviceDriver or direct SCSI BOT with Streaming State Machine.
     *
     * @param maxBytesToScan 0L = Full real capacity of the USB/SD card (No 256MB limit!).
     */
    suspend fun rescueFromDamagedUsb(
        context: Context,
        device: UsbMassStorageDevice,
        maxBytesToScan: Long = 0L, // 0L = Full Capacity
        resumeFromCheckpoint: Boolean = false,
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

        if (!resumeFromCheckpoint) {
            try {
                onProgress(0.03f, 0, "Attempt 1: Initializing Mass Storage & Partition Table...")
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
                        onProgress(0.10f, 0, "Attempt 1 Success: Filesystem detected! Bypassing Android mount...")
                        val extractedFiles = mutableListOf<RescuedFile>()
                        val root = fileSystem.rootDirectory
                        extractFilesRecursively(context, root, extractedFiles, onProgress)

                        if (extractedFiles.isNotEmpty()) {
                            try { device.close() } catch (_: Exception) {}
                            onProgress(1.0f, extractedFiles.size, "Rescue complete via direct filesystem read!")
                            return@withContext RescueSessionResult(
                                isFilesystemMode = true,
                                rescuedFiles = extractedFiles
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                device.init()
                isInitialized = true
            } catch (_: Exception) {}
        }

        // Attempt 2: Full-Capacity Raw Block Device Mode (Direct Sector Carving with Streaming State Machine)
        onProgress(0.12f, 0, "Attempt 2: Switching to Full-Capacity Raw Block Device (SCSI Streaming)...")

        val startOffset = if (resumeFromCheckpoint) {
            UsbOtgManager.getSavedCheckpointOffset(context, device.usbDevice)
        } else 0L

        val rawBlockDriver: BlockDeviceDriver? = try {
            val partitions = device.partitions
            if (partitions.isNotEmpty()) {
                partitions[0]
            } else {
                // Attempt reflection on blockDevices field if partitions failed to mount
                val field = device.javaClass.getDeclaredField("blockDevices").apply { isAccessible = true }
                val list = field.get(device) as? List<*>
                list?.firstOrNull() as? BlockDeviceDriver
            }
        } catch (_: Exception) {
            null
        }

        val rescued = if (rawBlockDriver != null && rawBlockDriver.blocks > 0L) {
            val carvedList = FileCarverEngine.carveFromBlockDevice(
                context = context,
                blockDevice = rawBlockDriver,
                maxBytesToScan = maxBytesToScan,
                startOffsetBytes = startOffset,
                onCheckpoint = { offset ->
                    UsbOtgManager.saveCheckpointOffset(context, device.usbDevice, offset)
                },
                onProgress = { progress ->
                    onProgress(
                        progress.progressPercent,
                        progress.filesFound,
                        "Raw Carving: ${progress.statusMessage} (Bad: ${progress.badSectorsSkipped}, Zero-Skip: ${progress.zeroBlocksSkipped})"
                    )
                }
            )
            UsbOtgManager.clearCheckpoint(context, device.usbDevice)
            carvedList
        } else {
            try {
                if (isInitialized) {
                    device.close()
                    isInitialized = false
                }
            } catch (_: Exception) {}

            // Direct USB Bulk-Only Transport (BOT) with SCSI READ CAPACITY(10)
            val maxMb = if (maxBytesToScan > 0L) (maxBytesToScan / (1024L * 1024L)).toInt() else 0
            UsbOtgManager.carveRawUsbStorage(
                context = context,
                device = device.usbDevice,
                maxMegaBytesToScan = maxMb,
                resumeFromCheckpoint = resumeFromCheckpoint,
                onProgress = { scannedBytes, totalBytes, filesCount, phase ->
                    val denom = if (totalBytes > 0L) totalBytes else 1L
                    val percent = (scannedBytes.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
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
        val baseRescueDir = FileRescueManager.getRescueDirectory(context)
        val files = try {
            directory.listFiles()
        } catch (_: Exception) {
            emptyArray<UsbFile>()
        }

        for (file in files) {
            if (file.isDirectory) {
                extractFilesRecursively(context, file, outList, onProgress)
            } else {
                val rescued = copyUsbFileToPhone(file, baseRescueDir)
                if (rescued != null) {
                    outList.add(rescued)
                    onProgress(0.5f, outList.size, "Salvaged: ${file.name}")
                }
            }
        }
    }

    private fun copyUsbFileToPhone(usbFile: UsbFile, baseRescueDir: File): RescuedFile? {
        return try {
            val cleanName = usbFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val ext = cleanName.substringAfterLast(".", "").lowercase()
            val category = when (ext) {
                "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> FileCategory.PHOTO
                "mp4", "mkv", "avi", "mov", "3gp", "webm" -> FileCategory.VIDEO
                "mp3", "m4a", "wav", "flac", "ogg", "aac" -> FileCategory.AUDIO
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt" -> FileCategory.DOCUMENT
                "db", "sqlite", "sqlite3", "crypt14", "crypt15" -> FileCategory.DATABASE
                "zip", "rar", "7z", "tar", "gz", "apk" -> FileCategory.ARCHIVE
                else -> FileCategory.OTHER
            }

            val categoryDir = FileRescueManager.getCategorySubdirectory(baseRescueDir, category)
            if (!categoryDir.exists()) categoryDir.mkdirs()

            val targetFile = File(categoryDir, "usb_fs_${cleanName}")
            val fileSize = usbFile.length
            val buffer = ByteBuffer.allocate(64 * 1024) // 64KB buffer

            FileOutputStream(targetFile).use { outStream ->
                var offset = 0L
                while (offset < fileSize) {
                    buffer.clear()
                    val toRead = (64 * 1024L).coerceAtMost(fileSize - offset).toInt()
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

            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "pdf" -> "application/pdf"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "3gp" -> "video/3gpp"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "zip" -> "application/zip"
                "db", "sqlite", "sqlite3" -> "application/vnd.sqlite3"
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
                magicSignature = "Direct Filesystem Read (${category.folderName})",
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

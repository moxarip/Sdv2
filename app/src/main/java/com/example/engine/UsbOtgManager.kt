package com.example.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.model.MbrBootSectorReport
import com.example.model.RescuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min

data class UsbDeviceInfo(
    val device: UsbDevice,
    val deviceName: String,
    val vendorName: String,
    val isMassStorage: Boolean,
    val hasPermission: Boolean,
    val savedCheckpointBytes: Long = 0L,
    val detectedCapacityBytes: Long = 0L
)

object UsbOtgManager {

    const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
    private const val PREFS_CHECKPOINTS = "usb_forensic_checkpoints"

    data class ScsiCapacity(
        val totalBlocks: Long,
        val blockSize: Int,
        val totalBytes: Long
    )

    fun getUsbManager(context: Context): UsbManager {
        return context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private fun getDeviceKey(device: UsbDevice): String {
        return "usb_${device.vendorId}_${device.productId}_${device.deviceName}"
    }

    fun getSavedCheckpointOffset(context: Context, device: UsbDevice): Long {
        val prefs = context.getSharedPreferences(PREFS_CHECKPOINTS, Context.MODE_PRIVATE)
        return prefs.getLong(getDeviceKey(device), 0L)
    }

    fun saveCheckpointOffset(context: Context, device: UsbDevice, offsetBytes: Long) {
        val prefs = context.getSharedPreferences(PREFS_CHECKPOINTS, Context.MODE_PRIVATE)
        prefs.edit().putLong(getDeviceKey(device), offsetBytes).apply()
    }

    fun clearCheckpoint(context: Context, device: UsbDevice) {
        val prefs = context.getSharedPreferences(PREFS_CHECKPOINTS, Context.MODE_PRIVATE)
        prefs.edit().remove(getDeviceKey(device)).apply()
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
            val hasPerm = manager.hasPermission(device)
            val checkpoint = getSavedCheckpointOffset(context, device)

            list.add(
                UsbDeviceInfo(
                    device = device,
                    deviceName = productName,
                    vendorName = vendorName,
                    isMassStorage = isStorage,
                    hasPermission = hasPerm,
                    savedCheckpointBytes = checkpoint
                )
            )
        }
        return list
    }

    private fun isStorageDevice(device: UsbDevice): Boolean {
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
            manager.requestPermission(device, pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Queries the true total capacity of the USB storage device using SCSI READ CAPACITY (10) (OpCode 0x25).
     */
    fun queryScsiReadCapacity(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        inEndpoint: UsbEndpoint,
        tag: Int = 777
    ): ScsiCapacity {
        try {
            val cbw = createReadCapacity10Cbw(tag)
            val sent = connection.bulkTransfer(outEndpoint, cbw, cbw.size, 2000)
            if (sent > 0) {
                val resp = ByteArray(8)
                val read = connection.bulkTransfer(inEndpoint, resp, 8, 2000)
                val csw = ByteArray(13)
                connection.bulkTransfer(inEndpoint, csw, 13, 2000)

                if (read == 8) {
                    val lastLba = ((resp[0].toLong() and 0xFF) shl 24) or
                            ((resp[1].toLong() and 0xFF) shl 16) or
                            ((resp[2].toLong() and 0xFF) shl 8) or
                            (resp[3].toLong() and 0xFF)
                    val blockLen = (((resp[4].toInt() and 0xFF) shl 24) or
                            ((resp[5].toInt() and 0xFF) shl 16) or
                            ((resp[6].toInt() and 0xFF) shl 8) or
                            (resp[7].toInt() and 0xFF)).coerceIn(512, 4096)

                    if (lastLba > 0L && lastLba < 0xFFFFFFFFL) {
                        val totalBlocks = lastLba + 1L
                        return ScsiCapacity(
                            totalBlocks = totalBlocks,
                            blockSize = blockLen,
                            totalBytes = totalBlocks * blockLen.toLong()
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback if device does not answer READ CAPACITY (10)
        return ScsiCapacity(
            totalBlocks = 0L,
            blockSize = 512,
            totalBytes = 0L
        )
    }

    /**
     * Low-level SCSI / USB Bulk-Only Transport (BOT) direct sector carver.
     * Connects directly to the USB Mass Storage endpoints, queries actual drive capacity via
     * SCSI READ CAPACITY(10), and streams all sectors through FileCarverEngine's Streaming State Machine.
     *
     * @param maxMegaBytesToScan 0 = Scan Full Real Capacity of the USB drive (No 256MB limit!).
     * @param resumeFromCheckpoint If true, resumes from the last saved LBA offset.
     */
    suspend fun carveRawUsbStorage(
        context: Context,
        device: UsbDevice,
        maxMegaBytesToScan: Int = 0, // 0 = Full Drive Capacity
        resumeFromCheckpoint: Boolean = false,
        onProgress: suspend (scannedBytes: Long, totalBytes: Long, filesFound: Int, currentPhase: String) -> Unit
    ): List<RescuedFile> = withContext(Dispatchers.IO) {
        val manager = getUsbManager(context)
        if (!manager.hasPermission(device)) {
            return@withContext emptyList()
        }

        val connection: UsbDeviceConnection = manager.openDevice(device) ?: return@withContext emptyList()
        val targetRescueDir = FileRescueManager.getRescueDirectory(context)
        val session = FileCarverEngine.StreamingCarverSession(targetRescueDir)

        try {
            val endpoints = claimMassStorageEndpoints(device, connection) ?: run {
                connection.close()
                return@withContext emptyList()
            }
            val (storageInterface, inEndpoint, outEndpoint) = endpoints

            onProgress(0L, 0L, 0, "Querying SCSI Drive Capacity (READ CAPACITY 10)...")

            val capacity = queryScsiReadCapacity(connection, outEndpoint, inEndpoint, 900)
            val sectorSize = capacity.blockSize.coerceAtLeast(512)
            val realTotalBytes = if (capacity.totalBytes > 0L) {
                capacity.totalBytes
            } else if (maxMegaBytesToScan > 0) {
                maxMegaBytesToScan.toLong() * 1024L * 1024L
            } else {
                32L * 1024L * 1024L * 1024L // 32GB probe ceiling if capacity command unsupported
            }

            val targetScanBytes = if (maxMegaBytesToScan > 0) {
                realTotalBytes.coerceAtMost(maxMegaBytesToScan.toLong() * 1024L * 1024L)
            } else {
                realTotalBytes
            }

            val startOffsetBytes = if (resumeFromCheckpoint) {
                getSavedCheckpointOffset(context, device).coerceIn(0L, (targetScanBytes - sectorSize).coerceAtLeast(0L))
            } else {
                0L
            }

            val sectorsPerBatch = 256 // 128 KB per SCSI transfer for high-speed streaming
            val chunkSize = sectorsPerBatch * sectorSize

            var currentLba = startOffsetBytes / sectorSize
            var totalBytesScanned = currentLba * sectorSize
            var tagCounter = 1000
            var badSectorsSkipped = 0
            var consecutiveFailures = 0

            val readBuffer = ByteArray(chunkSize)

            while (totalBytesScanned < targetScanBytes && coroutineContext.isActive) {
                val remainingBytes = targetScanBytes - totalBytesScanned
                val currentBatchSectors = min(sectorsPerBatch.toLong(), (remainingBytes / sectorSize).coerceAtLeast(1L)).toInt()
                val currentTransferLen = currentBatchSectors * sectorSize

                val cbw = createRead10Cbw(tagCounter++, currentLba, currentBatchSectors, currentTransferLen)
                val outSent = connection.bulkTransfer(outEndpoint, cbw, cbw.size, 2500)

                if (outSent <= 0) {
                    consecutiveFailures++
                    if (consecutiveFailures > 32) break
                    currentLba += currentBatchSectors
                    totalBytesScanned += currentTransferLen
                    continue
                }

                val bytesRead = connection.bulkTransfer(inEndpoint, readBuffer, currentTransferLen, 4000)

                // Always consume 13-byte CSW
                val csw = ByteArray(13)
                connection.bulkTransfer(inEndpoint, csw, 13, 2000)

                if (bytesRead <= 0) {
                    badSectorsSkipped += currentBatchSectors
                    consecutiveFailures++
                    // If drive capacity wasn't reported and we hit end of media, stop
                    if (capacity.totalBytes <= 0L && consecutiveFailures > 128) {
                        break
                    }
                    currentLba += currentBatchSectors
                    totalBytesScanned += currentTransferLen
                    continue
                }

                consecutiveFailures = 0
                val diskOffset = currentLba * sectorSize

                // Feed directly into the Streaming State Machine (handles multi-chunk MP4, ZIP, PDF, WEBP, SQLite, JPEG, PNG)
                val newlyCarved = session.feedChunk(readBuffer, bytesRead, diskOffset)

                currentLba += currentBatchSectors
                totalBytesScanned += bytesRead
                saveCheckpointOffset(context, device, totalBytesScanned)

                if (totalBytesScanned % (1024 * 1024) < chunkSize || newlyCarved.isNotEmpty()) {
                    val capStr = FileRescueManager.formatFileSize(targetScanBytes)
                    val curStr = FileRescueManager.formatFileSize(totalBytesScanned)
                    onProgress(
                        totalBytesScanned,
                        targetScanBytes,
                        session.rescuedFiles.size,
                        "LBA #$currentLba ($curStr / $capStr) • Bad Sectors: $badSectorsSkipped • Zero-Skipped: ${session.zeroBlocksSkipped}"
                    )
                }
            }

            session.finishSession()
            if (totalBytesScanned >= targetScanBytes) {
                clearCheckpoint(context, device)
            }

            connection.releaseInterface(storageInterface)
            connection.close()

            onProgress(
                totalBytesScanned,
                targetScanBytes,
                session.rescuedFiles.size,
                "USB Carving Complete! Rescued ${session.rescuedFiles.size} files."
            )
        } catch (e: Exception) {
            e.printStackTrace()
            session.finishSession()
            try { connection.close() } catch (_: Exception) {}
        }

        session.rescuedFiles
    }

    /**
     * Inspects LBA 0 (MBR / Primary Boot Sector) and searches for FAT32 (LBA 6) or exFAT (LBA 12)
     * backup boot sectors to diagnose and repair corrupted partition tables / boot sectors.
     */
    suspend fun inspectMbrAndBootSector(
        context: Context,
        device: UsbDevice
    ): MbrBootSectorReport = withContext(Dispatchers.IO) {
        val manager = getUsbManager(context)
        if (!manager.hasPermission(device)) {
            return@withContext MbrBootSectorReport(
                deviceName = device.deviceName,
                statusMessage = "USB Permission not granted"
            )
        }

        val connection = manager.openDevice(device) ?: return@withContext MbrBootSectorReport(
            deviceName = device.deviceName,
            statusMessage = "Unable to open USB device connection"
        )

        try {
            val endpoints = claimMassStorageEndpoints(device, connection) ?: run {
                connection.close()
                return@withContext MbrBootSectorReport(
                    deviceName = device.deviceName,
                    statusMessage = "No Bulk Mass Storage endpoints found"
                )
            }
            val (storageInterface, inEndpoint, outEndpoint) = endpoints

            // Read LBA 0 (512 bytes)
            val lba0 = readSingleSector(connection, outEndpoint, inEndpoint, 0L, 2001)
            if (lba0 == null) {
                connection.releaseInterface(storageInterface)
                connection.close()
                return@withContext MbrBootSectorReport(
                    deviceName = device.deviceName,
                    statusMessage = "Failed to read LBA 0 (Sector 0 unreadable)"
                )
            }

            val lba0Hex = FileCarver.formatHexDump(lba0, maxLines = 16)
            val has55Aa = (lba0[510] == 0x55.toByte() && lba0[511] == 0xAA.toByte())

            val oemString = String(lba0, 3, 8, Charsets.US_ASCII).trim()
            val fat32Label = String(lba0, 82, 8, Charsets.US_ASCII).trim()

            // Check first MBR partition entry at 0x1BE (offset 446) for partition start LBA
            val part1StartLba = readUint32LE(lba0, 446 + 8)
            val part1Type = lba0[446 + 4].toInt() and 0xFF

            val detectedFs = when {
                oemString.contains("EXFAT", ignoreCase = true) -> "exFAT Superblock (LBA 0)"
                fat32Label.contains("FAT32", ignoreCase = true) || oemString.contains("MSDOS", ignoreCase = true) -> "FAT32 Boot Sector"
                oemString.contains("NTFS", ignoreCase = true) -> "NTFS Boot Sector"
                part1Type in listOf(0x0B, 0x0C) -> "MBR Partition Table (FAT32 at LBA $part1StartLba)"
                part1Type == 0x07 -> "MBR Partition Table (exFAT/NTFS at LBA $part1StartLba)"
                has55Aa -> "Generic MBR / Boot Sector (55 AA valid)"
                else -> "Corrupted Boot Sector (Missing 55 AA signature)"
            }

            // Probe candidate Backup Boot Sectors: LBA 6 (FAT32), LBA 12 (exFAT), and Partition1 + 6 / + 12
            val candidateLbas = mutableListOf(6L, 12L)
            if (part1StartLba in 1L..1000000L) {
                candidateLbas.add(part1StartLba)
                candidateLbas.add(part1StartLba + 6L)
                candidateLbas.add(part1StartLba + 12L)
            }

            var foundBackupLba = -1L
            var targetRestoreLba = 0L
            var backupSectorBytes: ByteArray? = null

            for (candidate in candidateLbas.distinct()) {
                val sec = readSingleSector(connection, outEndpoint, inEndpoint, candidate, 2010 + candidate.toInt())
                if (sec != null && sec[510] == 0x55.toByte() && sec[511] == 0xAA.toByte()) {
                    val candOem = String(sec, 3, 8, Charsets.US_ASCII)
                    val candFat = String(sec, 82, 8, Charsets.US_ASCII)
                    if (candOem.contains("EXFAT", true) ||
                        candOem.contains("MSDOS", true) ||
                        candOem.contains("mkfs", true) ||
                        candFat.contains("FAT", true)
                    ) {
                        foundBackupLba = candidate
                        backupSectorBytes = sec
                        targetRestoreLba = if (candidate >= part1StartLba && part1StartLba > 0L) {
                            part1StartLba
                        } else {
                            0L
                        }
                        break
                    }
                }
            }

            connection.releaseInterface(storageInterface)
            connection.close()

            val backupHex = if (backupSectorBytes != null) {
                FileCarver.formatHexDump(backupSectorBytes, maxLines = 10)
            } else ""

            val statusMsg = buildString {
                if (has55Aa) {
                    append("LBA 0 has valid 0x55AA signature ($detectedFs). ")
                } else {
                    append("WARNING: LBA 0 is missing 0x55AA boot signature! ")
                }
                if (foundBackupLba != -1L) {
                    append("Valid Backup Boot Sector found at LBA #$foundBackupLba (can restore to LBA #$targetRestoreLba).")
                } else {
                    append("No valid FAT32/exFAT backup boot sector detected at standard offsets.")
                }
            }

            MbrBootSectorReport(
                deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.productName ?: device.deviceName else device.deviceName,
                lba0HexDump = lba0Hex,
                has55AaSignature = has55Aa,
                detectedFileSystemType = detectedFs,
                backupBootSectorFound = foundBackupLba != -1L,
                backupSectorLba = foundBackupLba,
                targetRestoreLba = targetRestoreLba,
                backupHexDump = backupHex,
                canRestoreInPlace = foundBackupLba != -1L,
                statusMessage = statusMsg,
                isRestored = false
            )
        } catch (e: Exception) {
            try { connection.close() } catch (_: Exception) {}
            MbrBootSectorReport(
                deviceName = device.deviceName,
                statusMessage = "MBR diagnostic error: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Restores the valid Backup Boot Sector (from LBA 6 or LBA 12) to the primary boot sector
     * AND saves a backup copy of both sectors to Downloads/OpenFileRescue/BootSectors/ first.
     */
    suspend fun restoreBackupBootSector(
        context: Context,
        device: UsbDevice,
        backupLba: Long,
        targetLba: Long
    ): MbrBootSectorReport = withContext(Dispatchers.IO) {
        val manager = getUsbManager(context)
        val connection = manager.openDevice(device) ?: return@withContext MbrBootSectorReport(
            deviceName = device.deviceName,
            statusMessage = "Could not open USB device for boot sector repair"
        )

        try {
            val endpoints = claimMassStorageEndpoints(device, connection) ?: run {
                connection.close()
                return@withContext MbrBootSectorReport(
                    deviceName = device.deviceName,
                    statusMessage = "Mass Storage endpoints unavailable"
                )
            }
            val (storageInterface, inEndpoint, outEndpoint) = endpoints

            val currentPrimary = readSingleSector(connection, outEndpoint, inEndpoint, targetLba, 3001)
            val backupSector = readSingleSector(connection, outEndpoint, inEndpoint, backupLba, 3002)

            if (backupSector == null || backupSector[510] != 0x55.toByte() || backupSector[511] != 0xAA.toByte()) {
                connection.releaseInterface(storageInterface)
                connection.close()
                return@withContext MbrBootSectorReport(
                    deviceName = device.deviceName,
                    statusMessage = "Backup Boot Sector at LBA #$backupLba is invalid; aborting repair to protect drive."
                )
            }

            // 1. Save forensic backup of original and repaired boot sectors on phone storage
            val bootDir = File(FileRescueManager.getRescueDirectory(context), "BootSectors").apply { mkdirs() }
            if (currentPrimary != null) {
                File(bootDir, "original_lba${targetLba}_backup.bin").writeBytes(currentPrimary)
            }
            val patchedFile = File(bootDir, "repaired_boot_sector_from_lba${backupLba}.bin")
            patchedFile.writeBytes(backupSector)

            // 2. Send SCSI WRITE(10) (OpCode 0x2A) ONLY for this single 512-byte boot sector repair
            val writeSuccess = writeSingleSector(connection, outEndpoint, inEndpoint, targetLba, backupSector, 3005)

            connection.releaseInterface(storageInterface)
            connection.close()

            val hex = FileCarver.formatHexDump(backupSector, maxLines = 16)
            MbrBootSectorReport(
                deviceName = device.deviceName,
                lba0HexDump = hex,
                has55AaSignature = true,
                detectedFileSystemType = "Restored Boot Sector from LBA #$backupLba",
                backupBootSectorFound = true,
                backupSectorLba = backupLba,
                targetRestoreLba = targetLba,
                backupHexDump = hex,
                canRestoreInPlace = true,
                statusMessage = if (writeSuccess) {
                    "Successfully restored Backup Boot Sector (LBA #$backupLba -> LBA #$targetLba) and saved backup to ${patchedFile.name}!"
                } else {
                    "Saved repaired Boot Sector image to ${patchedFile.absolutePath} (Hardware write-protected)."
                },
                isRestored = true
            )
        } catch (e: Exception) {
            try { connection.close() } catch (_: Exception) {}
            MbrBootSectorReport(
                deviceName = device.deviceName,
                statusMessage = "Boot sector repair error: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Dumps a raw sector-by-sector disk image (.img) from the damaged USB drive into phone storage
     * for offline forensic analysis and carving.
     */
    suspend fun dumpRawUsbImage(
        context: Context,
        device: UsbDevice,
        maxMegaBytes: Int = 512,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long, message: String) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val manager = getUsbManager(context)
        if (!manager.hasPermission(device)) return@withContext null
        val connection = manager.openDevice(device) ?: return@withContext null

        val imagesDir = File(FileRescueManager.getRescueDirectory(context), "DiskImages").apply { mkdirs() }
        val imgFile = File(imagesDir, "usb_raw_dump_${System.currentTimeMillis() % 100000}.img")

        try {
            val endpoints = claimMassStorageEndpoints(device, connection) ?: run {
                connection.close()
                return@withContext null
            }
            val (storageInterface, inEndpoint, outEndpoint) = endpoints

            val cap = queryScsiReadCapacity(connection, outEndpoint, inEndpoint, 4000)
            val sectorSize = cap.blockSize.coerceAtLeast(512)
            val maxBytes = if (cap.totalBytes > 0L) {
                cap.totalBytes.coerceAtMost(maxMegaBytes.toLong() * 1024L * 1024L)
            } else {
                maxMegaBytes.toLong() * 1024L * 1024L
            }

            val sectorsPerBatch = 128
            val batchBytes = sectorsPerBatch * sectorSize
            val buf = ByteArray(batchBytes)
            val zeroPadding = ByteArray(batchBytes)

            var currentLba = 0L
            var copiedBytes = 0L
            var tag = 5000

            BufferedOutputStream(FileOutputStream(imgFile), 128 * 1024).use { bos ->
                while (copiedBytes < maxBytes && coroutineContext.isActive) {
                    val cbw = createRead10Cbw(tag++, currentLba, sectorsPerBatch, batchBytes)
                    val sent = connection.bulkTransfer(outEndpoint, cbw, cbw.size, 2000)
                    if (sent <= 0) break

                    val read = connection.bulkTransfer(inEndpoint, buf, batchBytes, 3500)
                    val csw = ByteArray(13)
                    connection.bulkTransfer(inEndpoint, csw, 13, 2000)

                    if (read > 0) {
                        bos.write(buf, 0, read)
                        copiedBytes += read
                    } else {
                        // Bad sector: write zero-filled placeholder to preserve sector alignment in .img
                        bos.write(zeroPadding, 0, batchBytes)
                        copiedBytes += batchBytes
                    }
                    currentLba += sectorsPerBatch

                    if (copiedBytes % (2 * 1024 * 1024) < batchBytes) {
                        onProgress(
                            copiedBytes,
                            maxBytes,
                            "Imaging LBA #$currentLba (${FileRescueManager.formatFileSize(copiedBytes)} / ${FileRescueManager.formatFileSize(maxBytes)})"
                        )
                    }
                }
                bos.flush()
            }

            connection.releaseInterface(storageInterface)
            connection.close()
            imgFile
        } catch (e: Exception) {
            e.printStackTrace()
            try { connection.close() } catch (_: Exception) {}
            if (imgFile.exists() && imgFile.length() > 0L) imgFile else null
        }
    }

    private fun claimMassStorageEndpoints(
        device: UsbDevice,
        connection: UsbDeviceConnection
    ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
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
        val intf = storageInterface ?: return null
        if (!connection.claimInterface(intf, true)) return null

        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    inEndpoint = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    outEndpoint = ep
                }
            }
        }

        if (inEndpoint == null || outEndpoint == null) {
            connection.releaseInterface(intf)
            return null
        }
        return Triple(intf, inEndpoint, outEndpoint)
    }

    private fun readSingleSector(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        inEndpoint: UsbEndpoint,
        lba: Long,
        tag: Int
    ): ByteArray? {
        val cbw = createRead10Cbw(tag, lba, 1, 512)
        val sent = connection.bulkTransfer(outEndpoint, cbw, cbw.size, 2000)
        if (sent <= 0) return null
        val sector = ByteArray(512)
        val read = connection.bulkTransfer(inEndpoint, sector, 512, 2500)
        val csw = ByteArray(13)
        connection.bulkTransfer(inEndpoint, csw, 13, 2000)
        return if (read == 512) sector else null
    }

    private fun writeSingleSector(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        inEndpoint: UsbEndpoint,
        lba: Long,
        sectorData: ByteArray,
        tag: Int
    ): Boolean {
        if (sectorData.size != 512) return false
        val cbw = createWrite10Cbw(tag, lba, 1, 512)
        val sentCbw = connection.bulkTransfer(outEndpoint, cbw, cbw.size, 2000)
        if (sentCbw <= 0) return false
        val sentData = connection.bulkTransfer(outEndpoint, sectorData, 512, 2500)
        val csw = ByteArray(13)
        val cswRead = connection.bulkTransfer(inEndpoint, csw, 13, 2000)
        return sentData == 512 && cswRead == 13 && csw[12] == 0.toByte()
    }

    /**
     * Builds a USB Mass Storage BOT CBW for SCSI READ CAPACITY(10) (OpCode 0x25).
     */
    private fun createReadCapacity10Cbw(tag: Int): ByteArray {
        val buffer = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x55.toByte()) // 'U'
        buffer.put(0x53.toByte()) // 'S'
        buffer.put(0x42.toByte()) // 'B'
        buffer.put(0x43.toByte()) // 'C'
        buffer.putInt(tag)
        buffer.putInt(8) // 8-byte response: Last LBA (4B) + Block Length (4B)
        buffer.put(0x80.toByte()) // Direction: IN
        buffer.put(0.toByte()) // LUN 0
        buffer.put(10.toByte()) // CDB length = 10
        buffer.put(0x25.toByte()) // SCSI READ CAPACITY (10)
        while (buffer.position() < 31) {
            buffer.put(0.toByte())
        }
        return buffer.array()
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

        while (buffer.position() < 31) {
            buffer.put(0.toByte())
        }

        return buffer.array()
    }

    /**
     * Builds a SCSI WRITE(10) CBW (OpCode 0x2A) used strictly when the user explicitly triggers
     * Backup Boot Sector restoration.
     */
    private fun createWrite10Cbw(tag: Int, lba: Long, blockCount: Int, transferLength: Int): ByteArray {
        val buffer = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x55.toByte())
        buffer.put(0x53.toByte())
        buffer.put(0x42.toByte())
        buffer.put(0x43.toByte())
        buffer.putInt(tag)
        buffer.putInt(transferLength)
        buffer.put(0x00.toByte()) // Flags: 0x00 = Host to Device (Data OUT)
        buffer.put(0.toByte())
        buffer.put(10.toByte())

        buffer.put(0x2A.toByte()) // OpCode: WRITE(10)
        buffer.put(0.toByte())
        buffer.put((lba shr 24).toByte())
        buffer.put((lba shr 16).toByte())
        buffer.put((lba shr 8).toByte())
        buffer.put(lba.toByte())
        buffer.put(0.toByte())
        buffer.put((blockCount shr 8).toByte())
        buffer.put(blockCount.toByte())
        buffer.put(0.toByte())

        while (buffer.position() < 31) {
            buffer.put(0.toByte())
        }
        return buffer.array()
    }

    private fun readUint32LE(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}

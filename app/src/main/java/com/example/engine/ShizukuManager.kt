package com.example.engine

import android.content.Context
import android.content.pm.PackageManager
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class ShizukuStatus(val labelEn: String, val labelAr: String) {
    NOT_INSTALLED_OR_RUNNING("Service Not Running", "خدمة سيزوكي غير نشطة"),
    PERMISSION_NEEDED("Permission Required", "يتطلب منح الإذن من سيزوكي"),
    READY("Authorized & Active", "مصرح ونشط بصلاحيات النظام")
}

object ShizukuManager {

    const val SHIZUKU_REQUEST_CODE = 8991

    fun getStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_INSTALLED_OR_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.READY
            } else {
                ShizukuStatus.PERMISSION_NEEDED
            }
        } catch (_: Throwable) {
            ShizukuStatus.NOT_INSTALLED_OR_RUNNING
        }
    }

    fun requestPermission(requestCode: Int = SHIZUKU_REQUEST_CODE) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    suspend fun executeShellCommand(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            if (getStatus() != ShizukuStatus.READY) {
                return@withContext "Error: Shizuku is not authorized or running"
            }

            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            process.waitFor()
            sb.toString().trim()
        } catch (t: Throwable) {
            "Error executing Shizuku process: ${t.localizedMessage}"
        }
    }

    /**
     * Reads the exact byte capacity of a raw block device (e.g. /dev/block/sda or /dev/block/mmcblk1)
     * from /sys/class/block/<dev>/size (which reports 512-byte sectors) or blockdev --getsize64.
     */
    suspend fun getBlockDeviceCapacityBytes(blockPath: String): Long = withContext(Dispatchers.IO) {
        if (getStatus() != ShizukuStatus.READY) return@withContext 0L
        try {
            val devName = blockPath.substringAfterLast("/")
            val sysSizeCmd = "cat /sys/class/block/$devName/size 2>/dev/null || blockdev --getsize64 $blockPath 2>/dev/null"
            val output = executeShellCommand(sysSizeCmd).lines().firstOrNull()?.trim() ?: ""
            val value = output.toLongOrNull() ?: 0L
            // /sys/class/block/<dev>/size is in 512-byte sectors
            if (value > 0L) {
                val isSysSectors = executeShellCommand("cat /sys/class/block/$devName/size 2>/dev/null").trim().toLongOrNull() != null
                if (isSysSectors) value * 512L else value
            } else {
                0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Inspects elevated storage nodes (/mnt/media_rw, /storage, /dev/block)
     * to find raw unmounted external SD cards and OTG partitions.
     */
    suspend fun listElevatedBlockStorage(): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        if (getStatus() != ShizukuStatus.READY) return@withContext list

        try {
            val cmd = "ls -d /mnt/media_rw/* /dev/block/sd* /dev/block/mmcblk* 2>/dev/null"
            val output = executeShellCommand(cmd)
            output.lines().filter { it.isNotBlank() && !it.startsWith("Error") }.forEach { line ->
                list.add(line.trim())
            }
        } catch (_: Throwable) {}
        list
    }

    /**
     * Deep scans /mnt/media_rw or raw /dev/block/sdX nodes via Shizuku.
     * - For raw block devices (/dev/block/...): reads actual capacity from /sys/class/block/sdX/size
     *   and streams 1MB chunks into FileCarverEngine's StreamingCarverSession.
     * - For mounted/unmounted directories (/mnt/media_rw/...): extracts all files into categorized subfolders.
     */
    suspend fun carveWithShizuku(
        context: Context,
        targetPath: String,
        onProgress: suspend (foundCount: Int, status: String) -> Unit
    ): List<RescuedFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RescuedFile>()
        if (getStatus() != ShizukuStatus.READY) return@withContext results

        try {
            val rescueDir = FileRescueManager.getRescueDirectory(context)

            if (targetPath.startsWith("/dev/block/")) {
                // Raw block device carving via Shizuku stream
                val totalBytes = getBlockDeviceCapacityBytes(targetPath)
                val capDisplay = if (totalBytes > 0L) FileRescueManager.formatFileSize(totalBytes) else "Full Device"
                onProgress(0, "Reading raw block device $targetPath ($capDisplay)...")

                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }

                val ddCmd = "dd if=\"$targetPath\" bs=1048576 conv=noerror,sync 2>/dev/null"
                val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", ddCmd), null, null) as Process
                val session = FileCarverEngine.StreamingCarverSession(rescueDir)
                val buffer = ByteArray(FileCarverEngine.CHUNK_SIZE)
                var totalRead = 0L

                process.inputStream.use { input ->
                    while (coroutineContext.isActive) {
                        var offsetInBuf = 0
                        while (offsetInBuf < buffer.size) {
                            val r = input.read(buffer, offsetInBuf, buffer.size - offsetInBuf)
                            if (r <= 0) break
                            offsetInBuf += r
                        }
                        if (offsetInBuf <= 0) break

                        session.feedChunk(buffer, offsetInBuf, totalRead)
                        totalRead += offsetInBuf

                        onProgress(
                            session.rescuedFiles.size,
                            "Shizuku Raw Carve: ${FileRescueManager.formatFileSize(totalRead)} / $capDisplay • Rescued: ${session.rescuedFiles.size}"
                        )
                    }
                }
                try { process.destroy() } catch (_: Exception) {}
                session.finishSession()
                return@withContext session.rescuedFiles
            }

            onProgress(0, "Inspecting elevated directory: $targetPath")
            // No hardcoded head -n 100 limit! Retrieve all valid files
            val findCmd = "find \"$targetPath\" -type f -size +512c 2>/dev/null"
            val filePaths = executeShellCommand(findCmd).lines()
                .filter { it.isNotBlank() && !it.startsWith("Error") }

            filePaths.forEach { path ->
                if (!coroutineContext.isActive) return@forEach
                val name = path.substringAfterLast("/")
                val ext = name.substringAfterLast(".", "")

                val tempFile = File(rescueDir, "shizuku_tmp_${System.currentTimeMillis() % 100000}_$name")
                val copyCmd = "cat \"$path\" > \"${tempFile.absolutePath}\""
                executeShellCommand(copyCmd)

                if (tempFile.exists() && tempFile.length() > 0) {
                    val carveRes = FileCarver.inspectFile(tempFile)
                    val categoryDir = FileRescueManager.getCategorySubdirectory(rescueDir, carveRes.category)
                    val finalFile = File(categoryDir, "shizuku_${System.currentTimeMillis() % 100000}_$name")
                    tempFile.renameTo(finalFile)
                    val target = if (finalFile.exists()) finalFile else tempFile

                    val rescued = RescuedFile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        path = target.absolutePath,
                        uri = null,
                        size = target.length(),
                        lastModified = target.lastModified(),
                        category = carveRes.category,
                        mimeType = carveRes.mimeType,
                        detectedExtension = carveRes.detectedExtension,
                        originalExtension = ext,
                        healthState = carveRes.healthState,
                        healthScore = carveRes.healthScore,
                        sourceType = FileSourceType.SHIZUKU_ELEVATED,
                        magicSignature = carveRes.magicSignature,
                        isHeaderValid = carveRes.isHeaderValid,
                        isFooterValid = carveRes.isFooterValid,
                        isRescued = true,
                        rescuedDestinationPath = target.absolutePath,
                        hexSnippet = carveRes.hexSnippet
                    )
                    results.add(rescued)
                    onProgress(results.size, "Salvaged via Shizuku (${carveRes.category.folderName}): $name")
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        results
    }
}

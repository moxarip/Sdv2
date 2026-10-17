package com.example.engine

import android.content.Context
import android.content.pm.PackageManager
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

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
     * Inspects elevated storage nodes (/mnt/media_rw, /storage, /dev/block)
     * to find raw unmounted external SD cards and OTG partitions.
     */
    suspend fun listElevatedBlockStorage(): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        if (getStatus() != ShizukuStatus.READY) return@withContext list

        try {
            val cmd = "ls -d /mnt/media_rw/* /dev/block/sd* /dev/block/mmcblk* 2>/dev/null"
            val output = executeShellCommand(cmd)
            output.lines().filter { it.isNotBlank() }.forEach { line ->
                list.add(line.trim())
            }
        } catch (_: Throwable) {}
        list
    }

    /**
     * Deep scans /mnt/media_rw or any elevated directory with Shizuku
     * to salvage files that Android's normal sandbox is forbidden from reading.
     */
    suspend fun carveWithShizuku(
        context: Context,
        targetPath: String,
        onProgress: suspend (foundCount: Int, status: String) -> Unit
    ): List<RescuedFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RescuedFile>()
        if (getStatus() != ShizukuStatus.READY) return@withContext results

        try {
            onProgress(0, "Inspecting elevated directory: $targetPath")
            // List files recursively with find
            val findCmd = "find \"$targetPath\" -type f -size +1k 2>/dev/null | head -n 100"
            val filePaths = executeShellCommand(findCmd).lines().filter { it.isNotBlank() }

            val rescueDir = FileRescueManager.getRescueDirectory(context)

            filePaths.forEachIndexed { index, path ->
                val name = path.substringAfterLast("/")
                val ext = name.substringAfterLast(".", "")

                // Copy to app rescue directory via elevated cat/dd
                val destFile = File(rescueDir, "shizuku_rescued_${System.currentTimeMillis() % 10000}_$name")
                val copyCmd = "cat \"$path\" > \"${destFile.absolutePath}\""
                executeShellCommand(copyCmd)

                if (destFile.exists() && destFile.length() > 0) {
                    val carveRes = FileCarver.inspectFile(destFile)
                    val rescued = RescuedFile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        path = destFile.absolutePath,
                        uri = null,
                        size = destFile.length(),
                        lastModified = destFile.lastModified(),
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
                        rescuedDestinationPath = destFile.absolutePath,
                        hexSnippet = carveRes.hexSnippet
                    )
                    results.add(rescued)
                    onProgress(results.size, "Salvaged via Shizuku: $name")
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        results
    }
}

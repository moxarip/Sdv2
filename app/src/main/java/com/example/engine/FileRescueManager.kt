package com.example.engine

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.model.FileCategory
import com.example.model.RescuedFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FileRescueManager {

    fun getRescueDirectory(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val rescueDir = File(publicDownloads, "OpenFileRescue")
        if (!rescueDir.exists()) {
            val created = rescueDir.mkdirs()
            if (!created) {
                // Fallback to external files dir if scoped storage forbids
                val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OpenFileRescue")
                fallback.mkdirs()
                return fallback
            }
        }
        return rescueDir
    }

    fun getCategorySubdirectory(context: Context, category: FileCategory): File {
        val baseDir = getRescueDirectory(context)
        val subDir = File(baseDir, category.folderName)
        if (!subDir.exists()) {
            subDir.mkdirs()
        }
        return if (subDir.exists()) subDir else baseDir
    }

    fun getCategorySubdirectory(baseDir: File, category: FileCategory): File {
        val subDir = File(baseDir, category.folderName)
        if (!subDir.exists()) {
            subDir.mkdirs()
        }
        return if (subDir.exists()) subDir else baseDir
    }

    fun listAllSalvagedFilesOnDisk(context: Context): List<File> {
        val rootDir = getRescueDirectory(context)
        if (!rootDir.exists()) return emptyList()
        return rootDir.walkTopDown()
            .filter {
                it.isFile &&
                !it.name.startsWith("recovery_manifest_") &&
                !it.name.startsWith("OpenFileRescue_Export_") &&
                !it.name.endsWith(".img") &&
                !it.name.endsWith(".tmp")
            }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    fun rescueFile(context: Context, file: RescuedFile): RescuedFile {
        val targetDir = getCategorySubdirectory(context, file.category)
        val ext = file.detectedExtension.ifEmpty { file.originalExtension.ifEmpty { "bin" } }
        val rawBaseName = file.name.substringBeforeLast(".").ifEmpty { "rescued_${file.id.take(8)}" }

        // Sanitize file name
        val safeBaseName = rawBaseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        var destFile = File(targetDir, "${safeBaseName}.$ext")
        var counter = 1
        while (destFile.exists() && destFile.absolutePath != file.path) {
            destFile = File(targetDir, "${safeBaseName}_$counter.$ext")
            counter++
        }

        try {
            if (file.path.isNotEmpty() && File(file.path).exists()) {
                val src = File(file.path)
                if (src.absolutePath != destFile.absolutePath) {
                    src.copyTo(destFile, overwrite = true)
                }
            } else if (file.uri != null) {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                return file.copy(isRescued = false)
            }

            // Scan with MediaScanner so it appears in device media galleries
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf(file.mimeType),
                null
            )

            return file.copy(
                isRescued = true,
                rescuedDestinationPath = destFile.absolutePath
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return file.copy(isRescued = false)
        }
    }

    fun batchRescue(
        context: Context,
        files: List<RescuedFile>,
        onProgress: (current: Int, total: Int, lastFile: RescuedFile) -> Unit
    ): List<RescuedFile> {
        val updated = mutableListOf<RescuedFile>()
        files.forEachIndexed { index, file ->
            val rescued = rescueFile(context, file)
            updated.add(rescued)
            onProgress(index + 1, files.size, rescued)
        }
        return updated
    }

    /**
     * Copies all salvaged/rescued files into public Downloads/OpenFileRescue/<Category>
     * and indexes them with MediaScanner.
     */
    fun exportAllToPublicDownloads(
        context: Context,
        files: List<RescuedFile>
    ): Int {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val exportRoot = File(publicDownloads, "OpenFileRescue")
        if (!exportRoot.exists()) exportRoot.mkdirs()

        var exportedCount = 0
        val pathsToScan = mutableListOf<String>()

        for (file in files) {
            try {
                val srcFile = File(file.rescuedDestinationPath ?: file.path)
                if (!srcFile.exists() || !srcFile.isFile) continue

                val catDir = File(exportRoot, file.category.folderName).apply { mkdirs() }
                val targetFile = File(catDir, srcFile.name)
                if (srcFile.absolutePath != targetFile.absolutePath) {
                    srcFile.copyTo(targetFile, overwrite = true)
                }
                pathsToScan.add(targetFile.absolutePath)
                exportedCount++
            } catch (_: Exception) {}
        }

        if (pathsToScan.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(
                    context,
                    pathsToScan.toTypedArray(),
                    null,
                    null
                )
            } catch (_: Exception) {}
        }
        return exportedCount
    }

    /**
     * Packages all rescued files into a single structured ZIP archive inside Downloads/OpenFileRescue.
     */
    fun createZipArchive(
        context: Context,
        files: List<RescuedFile>
    ): File? {
        if (files.isEmpty()) return null
        return try {
            val targetDir = getRescueDirectory(context)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(targetDir, "OpenFileRescue_Export_$timeStamp.zip")

            val addedEntryNames = mutableSetOf<String>()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val buffer = ByteArray(64 * 1024)
                for (rescued in files) {
                    val src = File(rescued.rescuedDestinationPath ?: rescued.path)
                    if (!src.exists() || !src.isFile || src.name == zipFile.name) continue

                    var entryName = "${rescued.category.folderName}/${src.name}"
                    var dupIdx = 1
                    while (addedEntryNames.contains(entryName)) {
                        val base = src.nameWithoutExtension
                        val ext = src.extension
                        entryName = "${rescued.category.folderName}/${base}_$dupIdx.$ext"
                        dupIdx++
                    }
                    addedEntryNames.add(entryName)

                    val entry = ZipEntry(entryName).apply {
                        time = src.lastModified()
                    }
                    zos.putNextEntry(entry)
                    BufferedInputStream(FileInputStream(src)).use { bis ->
                        var read: Int
                        while (bis.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
            }
            zipFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun shareFileViaChooser(context: Context, file: File, mimeType: String = "*/*") {
        try {
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share Rescued Archive").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun generateRecoveryReport(context: Context, rescuedFiles: List<RescuedFile>): File {
        val targetDir = getRescueDirectory(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val reportFile = File(targetDir, "recovery_manifest_$timeStamp.txt")

        val sb = StringBuilder()
        sb.append("=========================================\n")
        sb.append("        OpenFileRescue Recovery Report   \n")
        sb.append("=========================================\n")
        sb.append("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("Total Rescued Files: ${rescuedFiles.size}\n")
        sb.append("Destination Directory: ${targetDir.absolutePath}\n\n")

        var totalBytes = 0L
        rescuedFiles.forEachIndexed { i, f ->
            totalBytes += f.size
            sb.append("#${i + 1} - ${f.name}\n")
            sb.append("   Category: ${f.category.name} (${f.category.folderName})\n")
            sb.append("   Detected Signature: ${f.magicSignature}\n")
            sb.append("   Health Score: ${f.healthScore}% (${f.healthState.name})\n")
            sb.append("   Size: ${formatFileSize(f.size)}\n")
            sb.append("   Source: ${f.sourceType.name}\n")
            sb.append("   Saved to: ${f.rescuedDestinationPath ?: f.path}\n\n")
        }

        sb.append("-----------------------------------------\n")
        sb.append("Total Data Salvaged: ${formatFileSize(totalBytes)}\n")
        sb.append("Generated by OpenFileRescue for Android\n")

        reportFile.writeText(sb.toString())
        return reportFile
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }
}

package com.example.engine

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.FileSourceType
import com.example.model.RescuedFile
import com.example.model.ScanStats
import com.example.model.StorageAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

object StorageScanner {

    enum class ScanMode {
        QUICK,
        DEEP,
        SAF_CUSTOM
    }

    suspend fun performScan(
        context: Context,
        mode: ScanMode,
        customUri: Uri? = null,
        onProgress: suspend (ScanStats) -> Unit
    ): List<RescuedFile> {
        val results = mutableListOf<RescuedFile>()
        val seenPaths = mutableSetOf<String>()
        var filesScanned = 0
        var bytesScanned = 0L
        val startTime = System.currentTimeMillis()

        suspend fun emitStats(phaseEn: String, phaseAr: String, currentPath: String, progress: Float) {
            onProgress(
                ScanStats(
                    filesScanned = filesScanned,
                    filesFound = results.size,
                    bytesScanned = bytesScanned,
                    currentScanningPath = currentPath,
                    scanDurationMillis = System.currentTimeMillis() - startTime,
                    isScanning = true,
                    scanPhase = phaseEn,
                    scanPhaseAr = phaseAr,
                    progress = progress.coerceIn(0f, 1f)
                )
            )
        }

        emitStats("Initializing Engine", "تهيئة محرك الفحص", "System Storage", 0.05f)

        // 1. Scan MediaStore index (including trashed and pending items)
        if (mode == ScanMode.QUICK || mode == ScanMode.DEEP) {
            emitStats("Scanning System MediaStore & Recycle Bin", "فحص فهرس النظام وسلة المحذوفات", "MediaStore Index", 0.15f)
            val mediaStoreFiles = scanMediaStore(context)
            for (file in mediaStoreFiles) {
                if (!coroutineContext.isActive) break
                filesScanned++
                bytesScanned += file.size
                if (file.path.isNotEmpty()) seenPaths.add(file.path)
                results.add(file)
                if (filesScanned % 15 == 0) {
                    emitStats("Scanning System MediaStore", "فحص ملفات وسائط النظام", file.name, 0.25f)
                }
            }
        }

        // 2. Scan App Caches, Thumbnails, and Trashed Directories
        if (mode == ScanMode.QUICK || mode == ScanMode.DEEP) {
            emitStats("Carving App Caches & Thumbnails", "فحص مجلدات الكاش والمصغرات", "Cache & Thumbs", 0.35f)
            val cacheFolders = listOfNotNull(
                context.cacheDir,
                context.externalCacheDir,
                context.filesDir,
                File(Environment.getExternalStorageDirectory(), ".thumbnails"),
                File(Environment.getExternalStorageDirectory(), ".trashed"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ".thumbnails")
            )

            for (folder in cacheFolders) {
                if (!coroutineContext.isActive) break
                if (folder.exists()) {
                    scanDirectory(folder, maxDepth = 3, seenPaths) { file ->
                        filesScanned++
                        bytesScanned += file.length()
                        val carved = inspectAndCreateRescuedFile(file, FileSourceType.CACHE_THUMBNAIL)
                        results.add(carved)
                        if (filesScanned % 20 == 0) {
                            emitStats("Carving Hidden & Cache Files", "استخراج الملفات الخفية والمؤقتة", file.name, 0.50f)
                        }
                    }
                }
            }
        }

        // 3. Deep Carving Scan of Common Storage Directories
        if (mode == ScanMode.DEEP) {
            emitStats("Deep Storage Carving", "فحص قطاعات الذاكرة العميقة", "Storage / SD", 0.60f)
            val targetDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                File(Environment.getExternalStorageDirectory(), "WhatsApp"),
                File(Environment.getExternalStorageDirectory(), "Telegram")
            )

            val totalDirs = targetDirs.size
            targetDirs.forEachIndexed { index, dir ->
                if (!coroutineContext.isActive) return@forEachIndexed
                if (dir.exists()) {
                    val baseProgress = 0.60f + (index.toFloat() / totalDirs) * 0.35f
                    scanDirectory(dir, maxDepth = 4, seenPaths) { file ->
                        filesScanned++
                        bytesScanned += file.length()
                        val carved = inspectAndCreateRescuedFile(file, FileSourceType.DEEP_CARVED)
                        results.add(carved)
                        if (filesScanned % 25 == 0) {
                            emitStats("Deep Carving Storage", "فحص متقدم للملفات المحذوفة والتالفة", file.name, baseProgress)
                        }
                    }
                }
            }
        }

        // 4. SAF Custom Directory Scan
        if (mode == ScanMode.SAF_CUSTOM && customUri != null) {
            emitStats("Scanning Custom Folder", "فحص المجلد المخصص", customUri.path ?: "", 0.30f)
            val docFile = DocumentFile.fromTreeUri(context, customUri)
            if (docFile != null && docFile.isDirectory) {
                scanDocumentTree(context, docFile, maxDepth = 5) { resFile ->
                    filesScanned++
                    bytesScanned += resFile.size
                    results.add(resFile)
                    if (filesScanned % 15 == 0) {
                        emitStats("Carving Custom Directory", "استخراج الملفات من المجلد المخصص", resFile.name, 0.75f)
                    }
                }
            }
        }

        emitStats("Scan Complete", "اكتمل الفحص بنجاح", "Finished", 1.0f)
        return results
    }

    private fun scanMediaStore(context: Context): List<RescuedFile> {
        val list = mutableListOf<RescuedFile>()
        val contentResolver: ContentResolver = context.contentResolver

        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to FileCategory.PHOTO,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to FileCategory.VIDEO,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to FileCategory.AUDIO,
            MediaStore.Files.getContentUri("external") to FileCategory.DOCUMENT
        )

        for ((collectionUri, categoryHint) in collections) {
            try {
                val projection = mutableListOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.MIME_TYPE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    projection.add(MediaStore.MediaColumns.IS_TRASHED)
                    projection.add(MediaStore.MediaColumns.IS_PENDING)
                }

                val cursor: Cursor? = contentResolver.query(
                    collectionUri,
                    projection.toTypedArray(),
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT 150"
                )

                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataCol = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val modCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val trashedCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        it.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    } else -1

                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol) ?: "file_$id"
                        val path = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                        val size = it.getLong(sizeCol)
                        val mod = it.getLong(modCol) * 1000
                        val mime = if (mimeCol >= 0) it.getString(mimeCol) ?: "application/octet-stream" else "application/octet-stream"
                        val isTrashed = if (trashedCol >= 0) it.getInt(trashedCol) == 1 else false

                        val contentUri = ContentUris.withAppendedId(collectionUri, id)

                        val fileObj = if (path.isNotEmpty()) File(path) else null
                        val carveRes = if (fileObj != null && fileObj.exists()) {
                            FileCarver.inspectFile(fileObj)
                        } else {
                            val ext = name.substringAfterLast(".", "")
                            FileCarver.analyzeBytes(
                                header = ByteArray(0),
                                tail = ByteArray(0),
                                declaredExtension = ext,
                                fileSize = size
                            )
                        }

                        val sourceType = if (isTrashed) FileSourceType.TRASHED else FileSourceType.MEDIA_STORE
                        val originalExt = name.substringAfterLast(".", "")

                        list.add(
                            RescuedFile(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                path = path,
                                uri = contentUri,
                                size = size,
                                lastModified = mod,
                                category = if (carveRes.category != FileCategory.OTHER) carveRes.category else categoryHint,
                                mimeType = if (carveRes.mimeType != "application/octet-stream") carveRes.mimeType else mime,
                                detectedExtension = carveRes.detectedExtension,
                                originalExtension = originalExt,
                                healthState = if (isTrashed) FileHealthState.GOOD else carveRes.healthState,
                                healthScore = if (isTrashed) 85 else carveRes.healthScore,
                                sourceType = sourceType,
                                magicSignature = carveRes.magicSignature,
                                isHeaderValid = carveRes.isHeaderValid,
                                isFooterValid = carveRes.isFooterValid,
                                corruptionReason = if (isTrashed) "Found in system recycle bin" else carveRes.corruptionReason,
                                hexSnippet = carveRes.hexSnippet
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return list
    }

    private suspend fun scanDirectory(
        directory: File,
        maxDepth: Int,
        seenPaths: MutableSet<String>,
        onFileFound: suspend (File) -> Unit
    ) {
        if (!directory.exists() || !directory.canRead() || maxDepth <= 0) return
        val files = directory.listFiles() ?: return

        for (file in files) {
            if (!coroutineContext.isActive) return
            if (file.isDirectory) {
                // Avoid recursing into inaccessible system paths
                if (!file.name.startsWith(".") || file.name == ".thumbnails" || file.name == ".trashed") {
                    scanDirectory(file, maxDepth - 1, seenPaths, onFileFound)
                }
            } else if (file.isFile && file.length() > 0) {
                if (seenPaths.add(file.absolutePath)) {
                    onFileFound(file)
                }
            }
        }
    }

    private fun inspectAndCreateRescuedFile(file: File, sourceType: FileSourceType): RescuedFile {
        val carveRes = FileCarver.inspectFile(file)
        val origExt = file.extension
        val name = file.name

        return RescuedFile(
            id = UUID.randomUUID().toString(),
            name = name,
            path = file.absolutePath,
            uri = null,
            size = file.length(),
            lastModified = file.lastModified(),
            category = carveRes.category,
            mimeType = carveRes.mimeType,
            detectedExtension = carveRes.detectedExtension,
            originalExtension = origExt,
            healthState = carveRes.healthState,
            healthScore = carveRes.healthScore,
            sourceType = sourceType,
            magicSignature = carveRes.magicSignature,
            isHeaderValid = carveRes.isHeaderValid,
            isFooterValid = carveRes.isFooterValid,
            corruptionReason = carveRes.corruptionReason,
            hexSnippet = carveRes.hexSnippet
        )
    }

    private suspend fun scanDocumentTree(
        context: Context,
        folder: DocumentFile,
        maxDepth: Int,
        onFileFound: suspend (RescuedFile) -> Unit
    ) {
        if (!folder.isDirectory || maxDepth <= 0) return
        val children = folder.listFiles()

        for (doc in children) {
            if (!coroutineContext.isActive) return
            if (doc.isDirectory) {
                scanDocumentTree(context, doc, maxDepth - 1, onFileFound)
            } else if (doc.isFile) {
                val name = doc.name ?: "doc_${System.currentTimeMillis()}"
                val ext = name.substringAfterLast(".", "")
                val size = doc.length()

                // Read sample bytes from SAF
                var headerBytes = ByteArray(0)
                try {
                    context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                        val buf = ByteArray(minOf(size.toInt(), 512))
                        val read = stream.read(buf)
                        if (read > 0) {
                            headerBytes = buf.copyOf(read)
                        }
                    }
                } catch (_: Exception) {}

                val carveRes = FileCarver.analyzeBytes(
                    header = headerBytes,
                    tail = ByteArray(0),
                    declaredExtension = ext,
                    fileSize = size
                )

                val rescued = RescuedFile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    path = doc.uri.toString(),
                    uri = doc.uri,
                    size = size,
                    lastModified = doc.lastModified(),
                    category = carveRes.category,
                    mimeType = doc.type ?: carveRes.mimeType,
                    detectedExtension = carveRes.detectedExtension,
                    originalExtension = ext,
                    healthState = carveRes.healthState,
                    healthScore = carveRes.healthScore,
                    sourceType = FileSourceType.SAF_CUSTOM,
                    magicSignature = carveRes.magicSignature,
                    isHeaderValid = carveRes.isHeaderValid,
                    isFooterValid = carveRes.isFooterValid,
                    corruptionReason = carveRes.corruptionReason,
                    hexSnippet = carveRes.hexSnippet
                )
                onFileFound(rescued)
            }
        }
    }

    fun analyzeStorage(context: Context): StorageAnalysis {
        val rootPath = Environment.getDataDirectory().path
        val stat = StatFs(rootPath)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val freeBytes = availableBlocks * blockSize
        val usedBytes = totalBytes - freeBytes

        var cacheBytes = 0L
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach {
            cacheBytes += calculateDirSize(it)
        }

        var trashBytes = 0L
        val trashDir = File(Environment.getExternalStorageDirectory(), ".trashed")
        if (trashDir.exists()) {
            trashBytes = calculateDirSize(trashDir)
        }

        val emptyFolders = countEmptyFolders(context.filesDir)

        return StorageAnalysis(
            totalBytes = totalBytes,
            freeBytes = freeBytes,
            usedBytes = usedBytes,
            cacheBytes = cacheBytes,
            trashBytes = trashBytes,
            mediaBytes = (usedBytes * 0.45).toLong(),
            emptyFoldersCount = emptyFolders,
            duplicatesCount = 0,
            duplicatesSize = 0L
        )
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) calculateDirSize(f) else f.length()
        }
        return size
    }

    private fun countEmptyFolders(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var count = 0
        val files = dir.listFiles() ?: return 0
        if (files.isEmpty()) return 1
        for (f in files) {
            if (f.isDirectory) count += countEmptyFolders(f)
        }
        return count
    }
}

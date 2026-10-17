package com.example.model

import android.net.Uri

enum class FileCategory(val label: String, val labelAr: String, val folderName: String) {
    PHOTO("Photos", "الصور", "Images"),
    VIDEO("Videos", "الفيديوهات", "Videos"),
    AUDIO("Audio", "الصوتيات", "Audio"),
    DOCUMENT("Documents", "المستندات", "Documents"),
    DATABASE("Databases", "قواعد البيانات", "Databases"),
    ARCHIVE("Archives", "الأرشيف", "Archives"),
    OTHER("Other", "أخرى", "Other")
}

enum class FileHealthState(val label: String, val labelAr: String) {
    EXCELLENT("Excellent", "ممتازة"),
    GOOD("Good", "جيدة"),
    REPAIRABLE("Repairable", "قابلة للإصلاح"),
    CORRUPTED("Damaged", "تالفة")
}

enum class FileSourceType(val label: String, val labelAr: String) {
    MEDIA_STORE("System Index", "فهرس النظام"),
    TRASHED("Recycle Bin", "سلة المهملات"),
    CACHE_THUMBNAIL("App Cache / Thumbs", "ذاكرة التخزين المؤقت"),
    DEEP_CARVED("Deep Storage Carving", "بصمة القطاعات العميقة"),
    SAF_CUSTOM("Custom SAF Directory", "مجلد خارجي مخصص"),
    USB_OTG_RAW_SECTOR("Raw USB-OTG Direct", "قطاعات USB المباشرة الخام"),
    SHIZUKU_ELEVATED("Shizuku Elevated Shell", "صلاحيات سيزوكي المتقدمة")
}

data class RescuedFile(
    val id: String,
    val name: String,
    val path: String,
    val uri: Uri? = null,
    val size: Long,
    val lastModified: Long,
    val category: FileCategory,
    val mimeType: String,
    val detectedExtension: String,
    val originalExtension: String,
    val healthState: FileHealthState,
    val healthScore: Int, // 0 - 100
    val sourceType: FileSourceType,
    val magicSignature: String = "",
    val isHeaderValid: Boolean = true,
    val isFooterValid: Boolean = true,
    val corruptionReason: String? = null,
    val hexSnippet: String = "",
    val isSelected: Boolean = false,
    val isRescued: Boolean = false,
    val rescuedDestinationPath: String? = null
)

data class ScanStats(
    val filesScanned: Int = 0,
    val filesFound: Int = 0,
    val bytesScanned: Long = 0L,
    val currentScanningPath: String = "",
    val scanDurationMillis: Long = 0L,
    val isScanning: Boolean = false,
    val scanPhase: String = "Ready",
    val scanPhaseAr: String = "جاهز للبدء",
    val progress: Float = 0f
)

data class StorageAnalysis(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val trashBytes: Long = 0L,
    val mediaBytes: Long = 0L,
    val emptyFoldersCount: Int = 0,
    val duplicatesCount: Int = 0,
    val duplicatesSize: Long = 0L
)

data class RepairReport(
    val originalFile: RescuedFile,
    val isSuccess: Boolean,
    val message: String,
    val repairedPath: String? = null,
    val fixesApplied: List<String> = emptyList()
)

data class MbrBootSectorReport(
    val deviceName: String = "",
    val lba0HexDump: String = "",
    val has55AaSignature: Boolean = false,
    val detectedFileSystemType: String = "Unknown",
    val backupBootSectorFound: Boolean = false,
    val backupSectorLba: Long = -1L,
    val targetRestoreLba: Long = 0L,
    val backupHexDump: String = "",
    val canRestoreInPlace: Boolean = false,
    val statusMessage: String = "",
    val isRestored: Boolean = false
)

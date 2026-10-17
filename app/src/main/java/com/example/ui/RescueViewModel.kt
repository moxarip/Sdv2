package com.example.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.DetectedStorageVolume
import com.example.engine.FileCarver
import com.example.engine.FileRepairEngine
import com.example.engine.FileRescueManager
import com.example.engine.ShizukuManager
import com.example.engine.ShizukuStatus
import com.example.engine.StorageScanner
import com.example.engine.StorageVolumeDetector
import com.example.engine.UsbDeviceInfo
import com.example.engine.UsbMassStorageManager
import com.example.engine.UsbOtgManager
import com.example.model.FileCategory
import com.example.model.FileHealthState
import com.example.model.MbrBootSectorReport
import com.example.model.RepairReport
import com.example.model.RescuedFile
import com.example.model.ScanStats
import com.example.model.StorageAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class AppTab {
    SCANNER,
    USB_OTG,
    RESULTS,
    REPAIR,
    STORAGE,
    SALVAGED
}

data class RescueUiState(
    val currentTab: AppTab = AppTab.SCANNER,
    val scanMode: StorageScanner.ScanMode = StorageScanner.ScanMode.QUICK,
    val scanStats: ScanStats = ScanStats(),
    val allFiles: List<RescuedFile> = emptyList(),
    val selectedCategory: FileCategory? = null,
    val selectedHealth: FileHealthState? = null,
    val searchQuery: String = "",
    val inspectingFile: RescuedFile? = null,
    val repairTargetFile: RescuedFile? = null,
    val repairReport: RepairReport? = null,
    val isRepairing: Boolean = false,
    val isBatchRescuing: Boolean = false,
    val batchCurrent: Int = 0,
    val batchTotal: Int = 0,
    val storageAnalysis: StorageAnalysis = StorageAnalysis(),
    val isAnalyzingStorage: Boolean = false,
    val salvagedFiles: List<RescuedFile> = emptyList(),
    val isExportingZip: Boolean = false,
    val appLanguage: String = if (Locale.getDefault().language.startsWith("ar")) "ar" else "en",
    val snackbarMessage: String? = null,

    // USB-OTG, Forensics & Shizuku state
    val connectedUsbDevices: List<UsbDeviceInfo> = emptyList(),
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED_OR_RUNNING,
    val detectedVolumes: List<DetectedStorageVolume> = emptyList(),
    val isUsbCarving: Boolean = false,
    val usbCarveStatus: String = "",
    val usbCarveProgressPercent: Float = 0f,
    val usbRescuedCount: Int = 0,
    val elevatedBlockNodes: List<String> = emptyList(),
    val mbrReport: MbrBootSectorReport? = null,
    val isInspectingMbr: Boolean = false
)

class RescueViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RescueUiState())
    val uiState: StateFlow<RescueUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var usbJob: Job? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                UsbOtgManager.ACTION_USB_PERMISSION -> {
                    refreshUsbAndShizuku()
                }
            }
        }
    }

    init {
        loadExistingSalvagedFiles()
        refreshStorageAnalysis()
        refreshUsbAndShizuku()

        try {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbOtgManager.ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    application.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
                } catch (_: Exception) {
                    application.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                }
            } else {
                application.registerReceiver(usbReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    fun setTab(tab: AppTab) {
        _uiState.update { it.copy(currentTab = tab) }
        if (tab == AppTab.USB_OTG) {
            refreshUsbAndShizuku()
        } else if (tab == AppTab.SALVAGED) {
            loadExistingSalvagedFiles()
        }
    }

    fun toggleLanguage() {
        _uiState.update {
            val next = if (it.appLanguage == "ar") "en" else "ar"
            it.copy(appLanguage = next)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setCategoryFilter(category: FileCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setHealthFilter(health: FileHealthState?) {
        _uiState.update { it.copy(selectedHealth = health) }
    }

    fun refreshUsbAndShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val usbList = UsbOtgManager.listConnectedUsbDevices(context)
            val shizuku = ShizukuManager.getStatus()
            val volumes = StorageVolumeDetector.getDetectedVolumes(context)
            val elevated = if (shizuku == ShizukuStatus.READY) {
                ShizukuManager.listElevatedBlockStorage()
            } else emptyList()

            _uiState.update {
                it.copy(
                    connectedUsbDevices = usbList,
                    shizukuStatus = shizuku,
                    detectedVolumes = volumes,
                    elevatedBlockNodes = elevated
                )
            }
        }
    }

    fun requestUsbPermission(device: UsbDevice) {
        UsbOtgManager.requestPermission(getApplication(), device)
    }

    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            refreshUsbAndShizuku()
        }
    }

    /**
     * Starts full-capacity (or custom range) USB OTG raw sector carving with Streaming State Machine.
     * @param maxMegaBytes 0 = Full Real Capacity of the USB drive (No 256MB limit!).
     */
    fun startRawUsbCarve(
        device: UsbDevice,
        maxMegaBytes: Int = 0,
        resumeFromCheckpoint: Boolean = false
    ) {
        usbJob?.cancel()
        _uiState.update {
            it.copy(
                isUsbCarving = true,
                usbCarveProgressPercent = 0.02f,
                usbRescuedCount = 0,
                usbCarveStatus = if (it.appLanguage == "ar")
                    "قراءة السعة الحقيقية للـ USB وبدء محرك الاستخراج المتدفق (Streaming State Machine)..."
                else
                    "Querying real USB capacity & starting Streaming State Machine carver..."
            )
        }

        usbJob = viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val massStorageDevices = UsbMassStorageManager.discoverDevices(context)
            val matchedDevice = massStorageDevices.find { it.usbDevice.deviceId == device.deviceId }
            val maxBytes = if (maxMegaBytes > 0) maxMegaBytes.toLong() * 1024L * 1024L else 0L

            val rescued = if (matchedDevice != null) {
                val sessionResult = UsbMassStorageManager.rescueFromDamagedUsb(
                    context = context,
                    device = matchedDevice,
                    maxBytesToScan = maxBytes,
                    resumeFromCheckpoint = resumeFromCheckpoint,
                    onProgress = { percent, count, message ->
                        _uiState.update {
                            it.copy(
                                usbCarveProgressPercent = percent,
                                usbRescuedCount = count,
                                usbCarveStatus = message
                            )
                        }
                    }
                )
                sessionResult.rescuedFiles
            } else {
                UsbOtgManager.carveRawUsbStorage(
                    context = context,
                    device = device,
                    maxMegaBytesToScan = maxMegaBytes,
                    resumeFromCheckpoint = resumeFromCheckpoint,
                    onProgress = { scannedBytes, totalBytes, foundCount, phase ->
                        val denom = if (totalBytes > 0L) totalBytes else 1L
                        val percent = (scannedBytes.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
                        _uiState.update {
                            it.copy(
                                usbCarveProgressPercent = percent,
                                usbRescuedCount = foundCount,
                                usbCarveStatus = "$phase | Found: $foundCount"
                            )
                        }
                    }
                )
            }

            loadExistingSalvagedFiles()
            refreshUsbAndShizuku()

            _uiState.update { state ->
                val newAll = (rescued + state.allFiles).distinctBy { it.id }
                state.copy(
                    isUsbCarving = false,
                    usbCarveProgressPercent = 1.0f,
                    usbRescuedCount = rescued.size,
                    allFiles = newAll,
                    currentTab = AppTab.RESULTS,
                    snackbarMessage = if (state.appLanguage == "ar")
                        "تم استخراج وتصنيف ${rescued.size} ملف من الـ USB بنجاح في مجلدات فرعية!"
                    else
                        "Rescued and categorized ${rescued.size} files from USB without formatting!"
                )
            }
        }
    }

    fun inspectUsbMbr(device: UsbDevice) {
        _uiState.update { it.copy(isInspectingMbr = true, mbrReport = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val report = UsbOtgManager.inspectMbrAndBootSector(getApplication(), device)
            _uiState.update {
                it.copy(
                    isInspectingMbr = false,
                    mbrReport = report
                )
            }
        }
    }

    fun restoreUsbBootSector(device: UsbDevice, backupLba: Long, targetLba: Long) {
        _uiState.update { it.copy(isInspectingMbr = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val report = UsbOtgManager.restoreBackupBootSector(
                context = getApplication(),
                device = device,
                backupLba = backupLba,
                targetLba = targetLba
            )
            _uiState.update {
                it.copy(
                    isInspectingMbr = false,
                    mbrReport = report,
                    snackbarMessage = report.statusMessage
                )
            }
        }
    }

    fun dismissMbrReport() {
        _uiState.update { it.copy(mbrReport = null) }
    }

    fun dumpRawUsbDiskImage(device: UsbDevice) {
        usbJob?.cancel()
        _uiState.update {
            it.copy(
                isUsbCarving = true,
                usbCarveProgressPercent = 0.02f,
                usbCarveStatus = if (it.appLanguage == "ar")
                    "جاري إنشاء نسخة خام للقطاعات (.img) داخل ذاكرة الهاتف..."
                else
                    "Creating raw sector-by-sector disk image (.img)..."
            )
        }

        usbJob = viewModelScope.launch(Dispatchers.IO) {
            val imgFile = UsbOtgManager.dumpRawUsbImage(
                context = getApplication(),
                device = device,
                maxMegaBytes = 512,
                onProgress = { copied, total, msg ->
                    val pct = if (total > 0L) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    _uiState.update {
                        it.copy(
                            usbCarveProgressPercent = pct,
                            usbCarveStatus = msg
                        )
                    }
                }
            )

            _uiState.update { state ->
                state.copy(
                    isUsbCarving = false,
                    usbCarveProgressPercent = 1.0f,
                    snackbarMessage = if (imgFile != null) {
                        if (state.appLanguage == "ar")
                            "تم حفظ صورة القرص الخام بنجاح: ${imgFile.name}"
                        else
                            "Raw disk image saved: ${imgFile.name}"
                    } else {
                        if (state.appLanguage == "ar") "تعذر إنشاء صورة القرص الخام" else "Could not dump raw disk image"
                    }
                )
            }
        }
    }

    fun startShizukuCarve(targetPath: String) {
        _uiState.update {
            it.copy(
                isUsbCarving = true,
                usbCarveStatus = if (it.appLanguage == "ar") "فحص الدليل/الكتلة بصلاحيات سيزوكي المتقدمة..." else "Scanning elevated node via Shizuku..."
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val carved = ShizukuManager.carveWithShizuku(
                context = getApplication(),
                targetPath = targetPath,
                onProgress = { count, status ->
                    _uiState.update {
                        it.copy(
                            usbRescuedCount = count,
                            usbCarveStatus = status
                        )
                    }
                }
            )

            loadExistingSalvagedFiles()

            _uiState.update { state ->
                val newAll = (carved + state.allFiles).distinctBy { it.id }
                state.copy(
                    isUsbCarving = false,
                    allFiles = newAll,
                    currentTab = AppTab.RESULTS,
                    snackbarMessage = if (state.appLanguage == "ar")
                        "تم استخراج ${carved.size} ملفات بصلاحيات سيزوكي بنجاح!"
                    else
                        "Salvaged ${carved.size} files via Shizuku elevated shell!"
                )
            }
        }
    }

    fun startScan(mode: StorageScanner.ScanMode, customUri: Uri? = null) {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                scanMode = mode,
                scanStats = ScanStats(isScanning = true, scanPhase = "Starting Scan...", scanPhaseAr = "بدء الفحص...")
            )
        }

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = StorageScanner.performScan(
                    context = getApplication(),
                    mode = mode,
                    customUri = customUri,
                    onProgress = { stats ->
                        _uiState.update { it.copy(scanStats = stats) }
                    }
                )

                _uiState.update {
                    it.copy(
                        allFiles = results,
                        scanStats = it.scanStats.copy(
                            isScanning = false,
                            scanPhase = "Scan Complete",
                            scanPhaseAr = "اكتمل الفحص"
                        ),
                        currentTab = if (results.isNotEmpty()) AppTab.RESULTS else AppTab.SCANNER,
                        snackbarMessage = if (it.appLanguage == "ar")
                            "تم العثور على ${results.size} ملف قابل للاستعادة"
                        else
                            "Discovered ${results.size} recoverable files"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        scanStats = it.scanStats.copy(
                            isScanning = false,
                            scanPhase = "Scan Interrupted",
                            scanPhaseAr = "تم إيقاف الفحص"
                        ),
                        snackbarMessage = "Scan error: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        usbJob?.cancel()
        _uiState.update {
            it.copy(
                isUsbCarving = false,
                scanStats = it.scanStats.copy(
                    isScanning = false,
                    scanPhase = "Scan Stopped",
                    scanPhaseAr = "تم إيقاف الفحص"
                )
            )
        }
        refreshUsbAndShizuku()
    }

    fun toggleFileSelection(fileId: String) {
        _uiState.update { state ->
            val updated = state.allFiles.map {
                if (it.id == fileId) it.copy(isSelected = !it.isSelected) else it
            }
            state.copy(allFiles = updated)
        }
    }

    fun selectAllFiles(select: Boolean) {
        _uiState.update { state ->
            val updated = state.allFiles.map { it.copy(isSelected = select) }
            state.copy(allFiles = updated)
        }
    }

    fun selectOnlyRepairable() {
        _uiState.update { state ->
            val updated = state.allFiles.map {
                it.copy(isSelected = it.healthState == FileHealthState.REPAIRABLE)
            }
            state.copy(allFiles = updated)
        }
    }

    fun inspectFile(file: RescuedFile?) {
        _uiState.update { it.copy(inspectingFile = file) }
    }

    fun setRepairTarget(file: RescuedFile?) {
        _uiState.update {
            it.copy(
                repairTargetFile = file,
                repairReport = null,
                currentTab = AppTab.REPAIR
            )
        }
    }

    fun rescueSingleFile(file: RescuedFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val rescued = FileRescueManager.rescueFile(getApplication(), file)
            _uiState.update { state ->
                val updatedAll = state.allFiles.map { if (it.id == file.id) rescued else it }
                val updatedSalvaged = (state.salvagedFiles.filterNot { it.id == file.id } + rescued)
                state.copy(
                    allFiles = updatedAll,
                    salvagedFiles = updatedSalvaged,
                    inspectingFile = if (state.inspectingFile?.id == file.id) rescued else state.inspectingFile,
                    snackbarMessage = if (state.appLanguage == "ar")
                        "تم حفظ الملف في OpenFileRescue/${file.category.folderName}"
                    else
                        "Saved file to OpenFileRescue/${file.category.folderName}"
                )
            }
        }
    }

    fun rescueSelectedFiles() {
        val selected = _uiState.value.allFiles.filter { it.isSelected }
        if (selected.isEmpty()) return

        _uiState.update {
            it.copy(
                isBatchRescuing = true,
                batchCurrent = 0,
                batchTotal = selected.size
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val rescuedList = FileRescueManager.batchRescue(
                context = getApplication(),
                files = selected,
                onProgress = { cur, tot, _ ->
                    _uiState.update { it.copy(batchCurrent = cur, batchTotal = tot) }
                }
            )

            FileRescueManager.generateRecoveryReport(getApplication(), rescuedList)

            _uiState.update { state ->
                val rescuedMap = rescuedList.associateBy { it.id }
                val updatedAll = state.allFiles.map { rescuedMap[it.id] ?: it }
                val updatedSalvaged = (state.salvagedFiles.filterNot { rescuedMap.containsKey(it.id) } + rescuedList)

                state.copy(
                    allFiles = updatedAll,
                    salvagedFiles = updatedSalvaged,
                    isBatchRescuing = false,
                    currentTab = AppTab.SALVAGED,
                    snackbarMessage = if (state.appLanguage == "ar")
                        "تمت استعادة ${rescuedList.size} ملفات وتصنيفها بنجاح!"
                    else
                        "Successfully rescued and categorized ${rescuedList.size} files!"
                )
            }
        }
    }

    fun exportAllSalvagedToDownloads() {
        val files = _uiState.value.salvagedFiles
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val count = FileRescueManager.exportAllToPublicDownloads(getApplication(), files)
            _uiState.update { state ->
                state.copy(
                    snackbarMessage = if (state.appLanguage == "ar")
                        "تم نسخ وفهرسة $count ملف في مجلد التنزيلات العام (Downloads/OpenFileRescue)!"
                    else
                        "Exported & indexed $count files into Downloads/OpenFileRescue!"
                )
            }
        }
    }

    fun exportAndShareAllSalvagedAsZip() {
        val files = _uiState.value.salvagedFiles
        if (files.isEmpty()) return
        _uiState.update { it.copy(isExportingZip = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val zipFile = FileRescueManager.createZipArchive(context, files)
            _uiState.update { state ->
                state.copy(
                    isExportingZip = false,
                    snackbarMessage = if (zipFile != null) {
                        if (state.appLanguage == "ar")
                            "تم إنشاء أرشيف ZIP: ${zipFile.name} (${FileRescueManager.formatFileSize(zipFile.length())})"
                        else
                            "Created ZIP archive: ${zipFile.name} (${FileRescueManager.formatFileSize(zipFile.length())})"
                    } else {
                        if (state.appLanguage == "ar") "تعذر إنشاء ملف ZIP" else "Failed to create ZIP archive"
                    }
                )
            }
            if (zipFile != null) {
                FileRescueManager.shareFileViaChooser(context, zipFile, "application/zip")
            }
        }
    }

    fun executeRepair(target: RescuedFile) {
        _uiState.update { it.copy(isRepairing = true, repairReport = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val rescueDir = FileRescueManager.getCategorySubdirectory(getApplication(), target.category)
            val report = FileRepairEngine.repairFile(getApplication(), target, rescueDir)

            _uiState.update { state ->
                val updatedAll = state.allFiles.map {
                    if (it.id == target.id) it.copy(healthState = FileHealthState.EXCELLENT, healthScore = 95) else it
                }
                state.copy(
                    isRepairing = false,
                    repairReport = report,
                    allFiles = updatedAll,
                    snackbarMessage = if (report.isSuccess) {
                        if (state.appLanguage == "ar") "تم إصلاح الملف وحفظه بنجاح!" else "File successfully repaired and saved!"
                    } else {
                        report.message
                    }
                )
            }
            loadExistingSalvagedFiles()
        }
    }

    fun pickCustomFileForRepair(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                var name = "picked_file"
                var size = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                    }
                }

                var headerBytes = ByteArray(0)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArray(minOf(size.toInt().coerceAtLeast(512), 1024))
                    val read = stream.read(buf)
                    if (read > 0) headerBytes = buf.copyOf(read)
                }

                val ext = name.substringAfterLast(".", "")
                val carve = FileCarver.analyzeBytes(headerBytes, ByteArray(0), ext, size)

                val rescuedFile = RescuedFile(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    path = uri.toString(),
                    uri = uri,
                    size = size,
                    lastModified = System.currentTimeMillis(),
                    category = carve.category,
                    mimeType = carve.mimeType,
                    detectedExtension = carve.detectedExtension,
                    originalExtension = ext,
                    healthState = carve.healthState,
                    healthScore = carve.healthScore,
                    sourceType = com.example.model.FileSourceType.SAF_CUSTOM,
                    magicSignature = carve.magicSignature,
                    isHeaderValid = carve.isHeaderValid,
                    isFooterValid = carve.isFooterValid,
                    corruptionReason = carve.corruptionReason,
                    hexSnippet = carve.hexSnippet
                )

                _uiState.update {
                    it.copy(
                        repairTargetFile = rescuedFile,
                        repairReport = null,
                        currentTab = AppTab.REPAIR
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "Could not open file: ${e.localizedMessage}") }
            }
        }
    }

    fun refreshStorageAnalysis() {
        _uiState.update { it.copy(isAnalyzingStorage = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val analysis = StorageScanner.analyzeStorage(getApplication())
            _uiState.update { it.copy(storageAnalysis = analysis, isAnalyzingStorage = false) }
        }
    }

    fun loadExistingSalvagedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = FileRescueManager.listAllSalvagedFilesOnDisk(getApplication())

            val rescued = files.map { file ->
                val carve = FileCarver.inspectFile(file)
                RescuedFile(
                    id = file.absolutePath,
                    name = file.name,
                    path = file.absolutePath,
                    uri = null,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    category = carve.category,
                    mimeType = carve.mimeType,
                    detectedExtension = carve.detectedExtension,
                    originalExtension = file.extension,
                    healthState = carve.healthState,
                    healthScore = carve.healthScore,
                    sourceType = com.example.model.FileSourceType.DEEP_CARVED,
                    magicSignature = carve.magicSignature,
                    isRescued = true,
                    rescuedDestinationPath = file.absolutePath,
                    hexSnippet = carve.hexSnippet
                )
            }
            _uiState.update { it.copy(salvagedFiles = rescued) }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}

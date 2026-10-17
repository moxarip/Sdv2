package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.FileRescueManager
import com.example.engine.StorageScanner
import com.example.ui.RescueUiState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanAccentDark
import com.example.ui.theme.EmeraldRescue
import com.example.ui.theme.RoseCorrupted
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@Composable
fun ScannerView(
    uiState: RescueUiState,
    onStartScan: (StorageScanner.ScanMode, Uri?) -> Unit,
    onStopScan: () -> Unit,
    onNavigateToResults: () -> Unit,
    onNavigateToUsbOtg: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAr = uiState.appLanguage == "ar"
    val stats = uiState.scanStats

    // SAF folder picker launcher
    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            onStartScan(StorageScanner.ScanMode.SAF_CUSTOM, treeUri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Radar pulse visualizer
        ScannerRadar(
            isScanning = stats.isScanning,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Slate800, Slate900)
                        )
                    )
                    .border(2.dp, if (stats.isScanning) CyanAccent else Slate700, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (stats.isScanning) Icons.Default.HourglassTop else Icons.Default.Memory,
                        contentDescription = "Engine Icon",
                        tint = if (stats.isScanning) CyanAccent else Slate400,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = if (stats.isScanning) "${(stats.progress * 100).toInt()}%" else "IDLE",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (stats.isScanning) CyanAccent else Slate400
                        )
                    )
                }
            }
        }

        // Live phase ticker
        Text(
            text = if (isAr) stats.scanPhaseAr else stats.scanPhase,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (stats.isScanning) CyanAccent else MaterialTheme.colorScheme.onBackground
            ),
            textAlign = TextAlign.Center
        )

        if (stats.currentScanningPath.isNotEmpty()) {
            Text(
                text = stats.currentScanningPath,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = Slate400
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                textAlign = TextAlign.Center
            )
        }

        // Animated progress bar
        if (stats.isScanning) {
            LinearProgressIndicator(
                progress = { stats.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = CyanAccent,
                trackColor = Slate800
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Real-time metrics counters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                title = if (isAr) "الملفات المفحوصة" else "Files Scanned",
                value = stats.filesScanned.toString(),
                subtitle = if (isAr) "ملف" else "items",
                tint = CyanAccent,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = if (isAr) "ملفات للاستعادة" else "Discovered",
                value = stats.filesFound.toString(),
                subtitle = if (isAr) "مرشحة للإنقاذ" else "recoverable",
                tint = EmeraldRescue,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = if (isAr) "حجم البيانات" else "Processed",
                value = FileRescueManager.formatFileSize(stats.bytesScanned),
                subtitle = if (isAr) "مفحوصة" else "read",
                tint = Color(0xFFF59E0B),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Scan control buttons
        if (stats.isScanning) {
            Button(
                onClick = onStopScan,
                colors = ButtonDefaults.buttonColors(containerColor = RoseCorrupted),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("stop_scan_button")
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isAr) "إيقاف الفحص الآن" else "Abort Scan",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        } else {
            // Mode Select Cards
            Text(
                text = if (isAr) "اختر طريقة استعادة البيانات" else "Choose Recovery Mode",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = if (isAr) TextAlign.End else TextAlign.Start
            )

            ScanModeCard(
                title = if (isAr) "فحص سريع وذكي (Quick Scan)" else "Smart Quick Scan",
                description = if (isAr)
                    "يفحص وسائط النظام، سلة المهملات، ومجلدات الذاكرة المؤقتة المصغرة"
                else
                    "Scans media index, recycle bin, app caches, and thumbnail caches",
                icon = Icons.Default.Bolt,
                accentColor = CyanAccent,
                testTag = "quick_scan_card",
                onClick = { onStartScan(StorageScanner.ScanMode.QUICK, null) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            ScanModeCard(
                title = if (isAr) "فحص القطاعات العميقة (Deep Carving)" else "Deep Carving Scanner",
                description = if (isAr)
                    "تحليل بصمات الملفات (Magic Bytes) للصور والفيديوهات والمستندات المحذوفة أو المجهولة"
                else
                    "Bit-level signature carving for deleted, hidden, or corrupted photos & files",
                icon = Icons.Default.Search,
                accentColor = EmeraldRescue,
                testTag = "deep_scan_card",
                onClick = { onStartScan(StorageScanner.ScanMode.DEEP, null) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            ScanModeCard(
                title = if (isAr) "استخراج بطاقة SD عبر USB / سيزوكي (تجاوز الفرمتة)" else "USB-OTG / SD Card & Shizuku (Bypass Format)",
                description = if (isAr)
                    "اتصال مباشر بقطاعات الـ USB التالفة واستخدام صلاحيات سيزوكي لاستخراج الملفات دون الحاجة لفرمتة البطاقة"
                else
                    "Direct SCSI raw sector carving & Shizuku privileges for unmounted USB cards that ask to format",
                icon = Icons.Default.Usb,
                accentColor = AmberWarning,
                testTag = "otg_shizuku_mode_card",
                onClick = onNavigateToUsbOtg
            )
        }

        // Shortcut to results if we have files
        if (uiState.allFiles.isNotEmpty() && !stats.isScanning) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onNavigateToResults,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("view_results_button")
            ) {
                Text(
                    text = if (isAr)
                        "عرض نتائج الفحص (${uiState.allFiles.size} ملف) ←"
                    else
                        "View Discovered Files (${uiState.allFiles.size}) →",
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(color = Slate400),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    fontFamily = FontFamily.Monospace
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = Slate400)
            )
        }
    }
}

@Composable
private fun ScanModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Slate400,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                )
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Start",
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

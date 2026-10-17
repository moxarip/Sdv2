package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.FileRescueManager
import com.example.model.StorageAnalysis
import com.example.ui.RescueUiState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldRescue
import com.example.ui.theme.RoseCorrupted
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@Composable
fun StorageHealthView(
    uiState: RescueUiState,
    onRefreshAnalysis: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAr = uiState.appLanguage == "ar"
    val analysis = uiState.storageAnalysis

    val usedRatio = if (analysis.totalBytes > 0) {
        (analysis.usedBytes.toFloat() / analysis.totalBytes).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Storage Overview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = "Storage",
                            tint = CyanAccent,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isAr) "مساحة التخزين الرئيسية" else "Internal Storage Capacity",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    if (uiState.isAnalyzingStorage) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = CyanAccent, strokeWidth = 2.dp)
                    } else {
                        Button(
                            onClick = onRefreshAnalysis,
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("refresh_storage_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = if (isAr) "تحديث" else "Refresh", fontSize = 11.sp, color = CyanAccent)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Storage progress bar
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (usedRatio > 0.85f) RoseCorrupted else CyanAccent,
                    trackColor = Slate800
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAr)
                            "المستخدم: ${FileRescueManager.formatFileSize(analysis.usedBytes)} (${(usedRatio * 100).toInt()}%)"
                        else
                            "Used: ${FileRescueManager.formatFileSize(analysis.usedBytes)} (${(usedRatio * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall.copy(color = Slate400)
                    )
                    Text(
                        text = if (isAr)
                            "المتاح: ${FileRescueManager.formatFileSize(analysis.freeBytes)}"
                        else
                            "Free: ${FileRescueManager.formatFileSize(analysis.freeBytes)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = EmeraldRescue,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grid of Health & Residual Sectors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HealthMetricBox(
                title = if (isAr) "مخلفات الكاش" else "Cache Artifacts",
                value = FileRescueManager.formatFileSize(analysis.cacheBytes),
                desc = if (isAr) "ملفات مؤقتة قابلة للإنقاذ" else "Carvable temporary caches",
                icon = Icons.Default.CleaningServices,
                tint = AmberWarning,
                modifier = Modifier.weight(1f)
            )

            HealthMetricBox(
                title = if (isAr) "سلة المحذوفات" else "Recycle Remnants",
                value = FileRescueManager.formatFileSize(analysis.trashBytes),
                desc = if (isAr) "قطاعات .trashed بالنظام" else "Items in trash sector",
                icon = Icons.Default.Delete,
                tint = RoseCorrupted,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HealthMetricBox(
                title = if (isAr) "مجلدات فارغة" else "Empty Folders",
                value = "${analysis.emptyFoldersCount}",
                desc = if (isAr) "أدلة فارغة في التخزين" else "Orphaned directory nodes",
                icon = Icons.Default.FolderSpecial,
                tint = Color(0xFFA855F7),
                modifier = Modifier.weight(1f)
            )

            HealthMetricBox(
                title = if (isAr) "الوسائط التقديرية" else "Media Volume",
                value = FileRescueManager.formatFileSize(analysis.mediaBytes),
                desc = if (isAr) "صور وفيديوهات مسجلة" else "Indexed pictures & videos",
                icon = Icons.Default.Memory,
                tint = CyanAccent,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Safety Tip
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (isAr) "💡 نصيحة أمان لاستعادة البيانات:" else "💡 Data Recovery Best Practice:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = CyanAccent)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isAr)
                        "تجنب تثبيت تطبيقات ثقيلة أو تحميل ملفات كبيرة بعد حذف ملفاتك المهمة، لمنع الكتابة فوق القطاعات التي تحتوي على البيانات القابلة للإنقاذ."
                    else
                        "Avoid installing heavy apps or downloading large files after accidentally deleting data to prevent sector overwriting before carving.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Slate400, lineHeight = 16.sp)
                )
            }
        }
    }
}

@Composable
private fun HealthMetricBox(
    title: String,
    value: String,
    desc: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, style = MaterialTheme.typography.labelMedium.copy(color = Slate400))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    fontFamily = FontFamily.Monospace
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = desc, style = MaterialTheme.typography.labelSmall.copy(color = Slate400, fontSize = 10.sp))
        }
    }
}

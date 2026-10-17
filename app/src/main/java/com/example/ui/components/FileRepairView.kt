package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.engine.FileRescueManager
import com.example.model.FileHealthState
import com.example.model.RescuedFile
import com.example.ui.RescueUiState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldRescue
import com.example.ui.theme.RoseCorrupted
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import java.io.File

@Composable
fun FileRepairView(
    uiState: RescueUiState,
    onPickFileUri: (Uri) -> Unit,
    onExecuteRepair: (RescuedFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val isAr = uiState.appLanguage == "ar"
    val context = LocalContext.current
    val targetFile = uiState.repairTargetFile
    val report = uiState.repairReport

    // SAF file picker for choosing ANY file to repair
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onPickFileUri(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Workshop banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AmberWarning.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Healing,
                        contentDescription = "Repair Tool",
                        tint = AmberWarning,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = if (isAr) "ورشة إصلاح وترميم الملفات التالفة" else "Corrupted File Repair Workshop",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isAr)
                            "إصلاح هيدر الصور المكسورة، إضافة وسوم النهاية (EOF)، وتصحيح الامتدادات المجهولة"
                        else
                            "Reconstruct damaged JPEG/PNG headers, fix missing EOF bounds, and normalize raw formats",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Select file button
        OutlinedButton(
            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("pick_file_to_repair_button")
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = "Pick", tint = CyanAccent)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isAr) "اختيار ملف تالف من الهاتف للفحص والإصلاح" else "Pick Any File From Phone to Repair",
                fontWeight = FontWeight.SemiBold,
                color = CyanAccent
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Selected Target File Card
        if (targetFile != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Slate800, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (isAr) "الملف المراد إصلاحه:" else "Target File Selected:",
                        style = MaterialTheme.typography.labelMedium.copy(color = Slate400)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = targetFile.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InfoItem(
                            label = if (isAr) "الامتداد الأصلي" else "Original Ext",
                            value = ".${targetFile.originalExtension.ifEmpty { "none" }}"
                        )
                        InfoItem(
                            label = if (isAr) "البصمة المكتشفة" else "Detected Magic",
                            value = targetFile.magicSignature.take(14)
                        )
                        InfoItem(
                            label = if (isAr) "الحالة" else "Health",
                            value = "${targetFile.healthScore}%",
                            color = if (targetFile.healthScore > 70) AmberWarning else RoseCorrupted
                        )
                    }

                    if (targetFile.corruptionReason != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = AmberWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = targetFile.corruptionReason,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = AmberWarning,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { onExecuteRepair(targetFile) },
                        enabled = !uiState.isRepairing,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("execute_repair_button")
                    ) {
                        if (uiState.isRepairing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAr) "جارٍ ترميم البيانات..." else "Repairing Bitstream...",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(Icons.Default.Build, contentDescription = "Repair", tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAr) "بدء عملية الإصلاح والترميم" else "Execute Automatic Repair",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            // Placeholder when no target is chosen
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = "No file",
                        tint = Slate700,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isAr)
                            "لم يتم تحديد ملف بعد\nاختر ملفاً من الزر أعلاه أو اضغط 'إصلاح' من قائمة نتائج الفحص"
                        else
                            "No file selected yet.\nPick a file above or select 'Repair' from the scan results list.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Slate400,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        }

        // Repair Report Card
        if (report != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (report.isSuccess) EmeraldRescue else RoseCorrupted, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (report.isSuccess) Icons.Default.TaskAlt else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (report.isSuccess) EmeraldRescue else RoseCorrupted,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (report.isSuccess)
                                (if (isAr) "اكتمل الإصلاح بنجاح!" else "Repair Succeeded!")
                            else
                                (if (isAr) "فشل الإصلاح" else "Repair Failed"),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (report.isSuccess) EmeraldRescue else RoseCorrupted
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = report.message,
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
                    )

                    if (report.fixesApplied.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isAr) "الإصلاحات المطبقة على البنية:" else "Applied Bitstream Patches:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Slate400)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        report.fixesApplied.forEach { fix ->
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldRescue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = fix,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = EmeraldRescue,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        }
                    }

                    if (report.repairedPath != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isAr) "مسار الحفظ: ${report.repairedPath}" else "Saved to: ${report.repairedPath}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Slate400,
                                fontSize = 11.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Share / Open actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    shareRepairedFile(context, report.repairedPath)
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = CyanAccent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isAr) "مشاركة" else "Share", color = CyanAccent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    color: Color = Slate400
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = Slate400))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        )
    }
}

private fun shareRepairedFile(context: android.content.Context, path: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Repaired File"))
    } catch (_: Exception) {
        // Fallback or ignore
    }
}

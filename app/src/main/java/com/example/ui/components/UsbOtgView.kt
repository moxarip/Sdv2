package com.example.ui.components

import android.hardware.usb.UsbDevice
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.DetectedStorageVolume
import com.example.engine.ShizukuStatus
import com.example.engine.UsbDeviceInfo
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
fun UsbOtgView(
    uiState: RescueUiState,
    onRequestUsbPermission: (UsbDevice) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onStartRawUsbCarve: (UsbDevice, Int) -> Unit,
    onStartShizukuCarve: (String) -> Unit,
    onRefresh: () -> Unit,
    onStopScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAr = uiState.appLanguage == "ar"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Explanatory Banner: "Why does Android ask to format?"
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = AmberWarning,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isAr)
                            "لماذا يطلب الهاتف فرمتة بطاقة الـ SD / الـ USB؟"
                        else
                            "Why Does Android Request Formatting the SD/USB?",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = AmberWarning
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isAr)
                        "عند تلف جدول الأقسام أو استخدام تنسيق غير مدعوم من أندرويد (مثل NTFS أو ملفات مكسورة)، يرفض النظام تحميل الذاكرة ويطلب تهيئتها مما يدمر بياناتك.\n" +
                        "✅ يوفر التطبيق حلاً مباشراً عبر الاتصال بقطاعات الـ USB الخام (SCSI BOT) ودعم صلاحيات سيزوكي (Shizuku) لقراءة الملفات مباشرة بدون فورمات!"
                    else
                        "When an SD card has corrupted partition tables or an unsupported filesystem (NTFS, EXT4, RAW), Android cannot mount it and demands formatting.\n" +
                        "✅ OpenFileRescue bypasses Android's filesystem mount by reading raw SCSI sectors directly over USB Host and Shizuku privileges without formatting or data loss!",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Slate400,
                        lineHeight = 16.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live USB-OTG Carving Progress if running
        if (uiState.isUsbCarving) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CyanAccent, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = Slate900)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = CyanAccent,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isAr) "استرجاع مباشر من القطاعات الخام..." else "Direct Sector Carving...",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = CyanAccent
                                )
                            )
                        }

                        // Rescued files counter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(EmeraldRescue.copy(alpha = 0.2f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isAr) "المنقذة: ${uiState.usbRescuedCount}" else "Rescued: ${uiState.usbRescuedCount}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = EmeraldRescue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Progress Bar & Percentage
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { uiState.usbCarveProgressPercent.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = CyanAccent,
                            trackColor = Slate800
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${(uiState.usbCarveProgressPercent * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = CyanAccent,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = uiState.usbCarveStatus,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onStopScan,
                        colors = ButtonDefaults.buttonColors(containerColor = RoseCorrupted),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("stop_usb_carve_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isAr) "إيقاف الاسترجاع" else "Stop Recovery", color = Color.White)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Section 1: Connected USB OTG Storage Devices
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, contentDescription = "USB", tint = CyanAccent, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isAr) "أجهزة الـ USB وبطاقات SD المتصلة" else "Connected USB OTG Devices",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = CyanAccent)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.connectedUsbDevices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.SdCard, contentDescription = null, tint = Slate700, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isAr)
                            "لم يتم اكتشاف وحدة USB أو قارئ بطاقات حالياً\nتأكد من تفعيل خيار OTG في إعدادات هاتفك وتوصيل القارئ بمنفذ الشحن ثم اضغط 'تحديث'"
                        else
                            "No USB or card reader detected yet.\nEnable 'OTG' in phone settings, plug into charging port, and tap refresh.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Slate400,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("scan_corrupted_usb_empty_button")
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAr) "فحص USB تالف / استرجاع خام" else "Scan Corrupted USB / Raw Carve",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            uiState.connectedUsbDevices.forEach { usbInfo ->
                UsbDeviceItem(
                    usbInfo = usbInfo,
                    isAr = isAr,
                    isCarving = uiState.isUsbCarving,
                    onRequestPermission = { onRequestUsbPermission(usbInfo.device) },
                    onStartCarve = { onStartRawUsbCarve(usbInfo.device, 256) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Section 2: Shizuku (سيزوكي) Elevated Permissions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = "Shizuku", tint = Color(0xFFA855F7), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isAr) "صلاحيات سيزوكي (Shizuku Elevated)" else "Shizuku System Integration",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            val statusColor = when (uiState.shizukuStatus) {
                ShizukuStatus.READY -> EmeraldRescue
                ShizukuStatus.PERMISSION_NEEDED -> AmberWarning
                ShizukuStatus.NOT_INSTALLED_OR_RUNNING -> RoseCorrupted
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isAr) uiState.shizukuStatus.labelAr else uiState.shizukuStatus.labelEn,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Slate800, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (isAr)
                        "تتيح صلاحيات سيزوكي للتطبيق الوصول كـ Root/ADB إلى مسارات النظام المحمية مثل /mnt/media_rw و /dev/block لاستخراج الملفات من بطاقات الذاكرة التي يرفض النظام الوصول لها."
                    else
                        "Shizuku allows elevated ADB/Root permissions to read protected system block nodes (/mnt/media_rw, /dev/block) bypassing Android sandbox restrictions.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Slate400, fontSize = 12.sp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (uiState.shizukuStatus == ShizukuStatus.PERMISSION_NEEDED) {
                    Button(
                        onClick = onRequestShizukuPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA855F7)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("request_shizuku_permission_button")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = "Key", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAr) "منح صلاحيات سيزوكي للتطبيق الآن" else "Grant Shizuku Permission",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (uiState.shizukuStatus == ShizukuStatus.READY) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldRescue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAr) "تم تفعيل صلاحيات سيزوكي بنجاح!" else "Shizuku authorization active!",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = EmeraldRescue,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    if (uiState.elevatedBlockNodes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isAr) "الأجهزة والكتل المكتشفة:" else "Detected Block Nodes:",
                            style = MaterialTheme.typography.labelSmall.copy(color = Slate400, fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        uiState.elevatedBlockNodes.forEach { node ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = node,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = CyanAccent,
                                        fontSize = 11.sp
                                    )
                                )
                                OutlinedButton(
                                    onClick = { onStartShizukuCarve(node) },
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(if (isAr) "فحص واستخراج" else "Carve", fontSize = 10.sp, color = CyanAccent)
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onStartShizukuCarve("/mnt/media_rw") },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderShared, contentDescription = null, tint = CyanAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAr) "فحص مجلدات /mnt/media_rw عبر سيزوكي" else "Scan /mnt/media_rw via Shizuku",
                                color = CyanAccent,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (isAr)
                            "تطبيق Shizuku غير مثبت أو لم يتم تشغيله. يمكنك تشغيل Shizuku عبر تصحيح أخطاء اللاسلكي (Wireless Debugging) لتفعيل الصلاحيات المتقدمة."
                        else
                            "Shizuku service is not active. Start Shizuku via Wireless Debugging or Root to enable advanced block recovery.",
                        style = MaterialTheme.typography.bodySmall.copy(color = Slate400, fontSize = 11.sp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Section 3: Android Storage Volume Inspector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = "Volumes", tint = Slate400, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isAr) "حالة وحدات التخزين بنظام الهاتف" else "System Storage Volume States",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        uiState.detectedVolumes.forEach { vol ->
            VolumeStateCard(vol = vol, isAr = isAr)
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun UsbDeviceItem(
    usbInfo: UsbDeviceInfo,
    isAr: Boolean,
    isCarving: Boolean,
    onRequestPermission: () -> Unit,
    onStartCarve: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (usbInfo.hasPermission) CyanAccent.copy(alpha = 0.5f) else AmberWarning.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyanAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Usb, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = usbInfo.deviceName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = usbInfo.vendorName,
                            style = MaterialTheme.typography.bodySmall.copy(color = Slate400, fontSize = 11.sp)
                        )
                    }
                }

                if (usbInfo.isMassStorage) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(EmeraldRescue.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isAr) "شريحة تخزين" else "Mass Storage",
                            style = MaterialTheme.typography.labelSmall.copy(color = EmeraldRescue, fontSize = 10.sp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!usbInfo.hasPermission) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("grant_usb_perm_button")
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAr) "طلب إذن الاتصال بوحدة الـ USB" else "Request USB Permission",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onStartCarve,
                    enabled = !isCarving,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("start_raw_usb_carve_button")
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAr) "فحص USB تالف / استرجاع خام" else "Scan Corrupted USB / Raw Carve",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeStateCard(vol: DetectedStorageVolume, isAr: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = vol.description,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = "State: ${vol.state} ${if (vol.uuid != null) "• UUID: ${vol.uuid}" else ""}",
                    style = MaterialTheme.typography.labelSmall.copy(color = Slate400, fontSize = 10.sp)
                )
            }

            if (vol.requiresFormatWarning) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(RoseCorrupted.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isAr) "غير محمل / يطلب تهيئة" else "Unmounted / Format Required",
                        style = MaterialTheme.typography.labelSmall.copy(color = RoseCorrupted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    )
                }
            } else if (vol.isMounted) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(EmeraldRescue.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isAr) "محمل وجاهز" else "Mounted",
                        style = MaterialTheme.typography.labelSmall.copy(color = EmeraldRescue, fontSize = 10.sp)
                    )
                }
            }
        }
    }
}

package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.FileInspectionDialog
import com.example.ui.components.FileRepairView
import com.example.ui.components.ResultsView
import com.example.ui.components.SalvagedFilesView
import com.example.ui.components.ScannerView
import com.example.ui.components.StorageHealthView
import com.example.ui.components.UsbOtgView
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldRescue
import com.example.ui.theme.RoseCorrupted
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainRescueScreen(
    viewModel: RescueViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAr = uiState.appLanguage == "ar"
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle snackbar messages
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // BackHandler to return to SCANNER tab if on secondary tabs
    BackHandler(enabled = uiState.currentTab != AppTab.SCANNER) {
        viewModel.setTab(AppTab.SCANNER)
    }

    val layoutDir = if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyanAccent.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Logo",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "OpenFileRescue",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                )
                                Text(
                                    text = if (isAr) "استعادة وترميم الملفات" else "Open Source Data Recovery",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = Slate400,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    },
                    actions = {
                        // Language switcher
                        Surface(
                            onClick = { viewModel.toggleLanguage() },
                            shape = RoundedCornerShape(8.dp),
                            color = Slate800,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("language_toggle_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = "Language",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isAr) "EN" else "عربي",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = CyanAccent
                                    )
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Slate900
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Slate900,
                    tonalElevation = 8.dp
                ) {
                    // 1. Scanner Tab
                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.SCANNER,
                        onClick = { viewModel.setTab(AppTab.SCANNER) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.currentTab == AppTab.SCANNER) Icons.Default.Radar else Icons.Outlined.Radar,
                                contentDescription = "Scanner"
                            )
                        },
                        label = {
                            Text(
                                text = if (isAr) "الفحص" else "Scan",
                                fontSize = 11.sp,
                                fontWeight = if (uiState.currentTab == AppTab.SCANNER) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanAccent,
                            selectedTextColor = CyanAccent,
                            indicatorColor = CyanAccent.copy(alpha = 0.2f),
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400
                        ),
                        modifier = Modifier.testTag("nav_scanner")
                    )

                    // 2. USB-OTG & Shizuku Tab
                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.USB_OTG,
                        onClick = { viewModel.setTab(AppTab.USB_OTG) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (uiState.connectedUsbDevices.isNotEmpty()) {
                                        Badge(
                                            containerColor = AmberWarning,
                                            contentColor = Color.Black
                                        ) {
                                            Text(
                                                text = uiState.connectedUsbDevices.size.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (uiState.currentTab == AppTab.USB_OTG) Icons.Default.Usb else Icons.Outlined.Usb,
                                    contentDescription = "USB & Shizuku"
                                )
                            }
                        },
                        label = {
                            Text(
                                text = if (isAr) "USB / سيزوكي" else "USB / SD",
                                fontSize = 11.sp,
                                fontWeight = if (uiState.currentTab == AppTab.USB_OTG) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AmberWarning,
                            selectedTextColor = AmberWarning,
                            indicatorColor = AmberWarning.copy(alpha = 0.2f),
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400
                        ),
                        modifier = Modifier.testTag("nav_usb_otg")
                    )

                    // 3. Results Tab
                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.RESULTS,
                        onClick = { viewModel.setTab(AppTab.RESULTS) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (uiState.allFiles.isNotEmpty()) {
                                        Badge(
                                            containerColor = CyanAccent,
                                            contentColor = Color.Black
                                        ) {
                                            Text(
                                                text = if (uiState.allFiles.size > 99) "99+" else uiState.allFiles.size.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (uiState.currentTab == AppTab.RESULTS) Icons.AutoMirrored.Filled.ListAlt else Icons.AutoMirrored.Outlined.ListAlt,
                                    contentDescription = "Results"
                                )
                            }
                        },
                        label = {
                            Text(
                                text = if (isAr) "النتائج" else "Results",
                                fontSize = 11.sp,
                                fontWeight = if (uiState.currentTab == AppTab.RESULTS) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanAccent,
                            selectedTextColor = CyanAccent,
                            indicatorColor = CyanAccent.copy(alpha = 0.2f),
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400
                        ),
                        modifier = Modifier.testTag("nav_results")
                    )

                    // 3. Repair Tab
                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.REPAIR,
                        onClick = { viewModel.setTab(AppTab.REPAIR) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.currentTab == AppTab.REPAIR) Icons.Default.Build else Icons.Outlined.Build,
                                contentDescription = "Repair"
                            )
                        },
                        label = {
                            Text(
                                text = if (isAr) "الإصلاح" else "Repair",
                                fontSize = 11.sp,
                                fontWeight = if (uiState.currentTab == AppTab.REPAIR) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AmberWarning,
                            selectedTextColor = AmberWarning,
                            indicatorColor = AmberWarning.copy(alpha = 0.2f),
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400
                        ),
                        modifier = Modifier.testTag("nav_repair")
                    )

                    // 4. Storage Health Tab
                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.STORAGE,
                        onClick = { viewModel.setTab(AppTab.STORAGE) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.currentTab == AppTab.STORAGE) Icons.Default.Storage else Icons.Outlined.Storage,
                                contentDescription = "Storage"
                            )
                        },
                        label = {
                            Text(
                                text = if (isAr) "التخزين" else "Storage",
                                fontSize = 11.sp,
                                fontWeight = if (uiState.currentTab == AppTab.STORAGE) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanAccent,
                            selectedTextColor = CyanAccent,
                            indicatorColor = CyanAccent.copy(alpha = 0.2f),
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400
                        ),
                        modifier = Modifier.testTag("nav_storage")
                    )

                    // 5. Salvaged Tab
                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.SALVAGED,
                        onClick = { viewModel.setTab(AppTab.SALVAGED) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (uiState.salvagedFiles.isNotEmpty()) {
                                        Badge(
                                            containerColor = EmeraldRescue,
                                            contentColor = Color.Black
                                        ) {
                                            Text(
                                                text = uiState.salvagedFiles.size.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (uiState.currentTab == AppTab.SALVAGED) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle,
                                    contentDescription = "Salvaged"
                                )
                            }
                        },
                        label = {
                            Text(
                                text = if (isAr) "المنقذة" else "Salvaged",
                                fontSize = 11.sp,
                                fontWeight = if (uiState.currentTab == AppTab.SALVAGED) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EmeraldRescue,
                            selectedTextColor = EmeraldRescue,
                            indicatorColor = EmeraldRescue.copy(alpha = 0.2f),
                            unselectedIconColor = Slate400,
                            unselectedTextColor = Slate400
                        ),
                        modifier = Modifier.testTag("nav_salvaged")
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Crossfade(targetState = uiState.currentTab, label = "TabCrossfade") { tab ->
                    when (tab) {
                        AppTab.SCANNER -> {
                            ScannerView(
                                uiState = uiState,
                                onStartScan = { mode, uri -> viewModel.startScan(mode, uri) },
                                onStopScan = { viewModel.stopScan() },
                                onNavigateToResults = { viewModel.setTab(AppTab.RESULTS) },
                                onNavigateToUsbOtg = { viewModel.setTab(AppTab.USB_OTG) }
                            )
                        }

                        AppTab.USB_OTG -> {
                            UsbOtgView(
                                uiState = uiState,
                                onRequestUsbPermission = { viewModel.requestUsbPermission(it) },
                                onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                                onStartRawUsbCarve = { device, mb -> viewModel.startRawUsbCarve(device, mb) },
                                onStartShizukuCarve = { path -> viewModel.startShizukuCarve(path) },
                                onRefresh = { viewModel.refreshUsbAndShizuku() },
                                onStopScan = { viewModel.stopScan() }
                            )
                        }

                        AppTab.RESULTS -> {
                            ResultsView(
                                uiState = uiState,
                                onSearchChange = { viewModel.setSearchQuery(it) },
                                onCategoryFilter = { viewModel.setCategoryFilter(it) },
                                onHealthFilter = { viewModel.setHealthFilter(it) },
                                onToggleSelect = { viewModel.toggleFileSelection(it) },
                                onSelectAll = { viewModel.selectAllFiles(it) },
                                onSelectOnlyRepairable = { viewModel.selectOnlyRepairable() },
                                onInspectFile = { viewModel.inspectFile(it) },
                                onRescueSingle = { viewModel.rescueSingleFile(it) },
                                onRepairTarget = { viewModel.setRepairTarget(it) },
                                onRescueSelected = { viewModel.rescueSelectedFiles() }
                            )
                        }

                        AppTab.REPAIR -> {
                            FileRepairView(
                                uiState = uiState,
                                onPickFileUri = { viewModel.pickCustomFileForRepair(it) },
                                onExecuteRepair = { viewModel.executeRepair(it) }
                            )
                        }

                        AppTab.STORAGE -> {
                            StorageHealthView(
                                uiState = uiState,
                                onRefreshAnalysis = { viewModel.refreshStorageAnalysis() }
                            )
                        }

                        AppTab.SALVAGED -> {
                            SalvagedFilesView(
                                uiState = uiState,
                                onRefresh = { viewModel.loadExistingSalvagedFiles() }
                            )
                        }
                    }
                }

                // File Inspection & Hex Dump Dialog
                uiState.inspectingFile?.let { file ->
                    FileInspectionDialog(
                        file = file,
                        isAr = isAr,
                        onDismiss = { viewModel.inspectFile(null) },
                        onRescue = {
                            viewModel.rescueSingleFile(file)
                        },
                        onRepair = {
                            viewModel.inspectFile(null)
                            viewModel.setRepairTarget(file)
                        }
                    )
                }
            }
        }
    }
}

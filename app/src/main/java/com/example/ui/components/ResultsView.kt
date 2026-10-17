package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.engine.FileRescueManager
import com.example.model.FileCategory
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultsView(
    uiState: RescueUiState,
    onSearchChange: (String) -> Unit,
    onCategoryFilter: (FileCategory?) -> Unit,
    onHealthFilter: (FileHealthState?) -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onSelectOnlyRepairable: () -> Unit,
    onInspectFile: (RescuedFile) -> Unit,
    onRescueSingle: (RescuedFile) -> Unit,
    onRepairTarget: (RescuedFile) -> Unit,
    onRescueSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAr = uiState.appLanguage == "ar"

    val filteredFiles = remember(
        uiState.allFiles,
        uiState.selectedCategory,
        uiState.selectedHealth,
        uiState.searchQuery
    ) {
        uiState.allFiles.filter { file ->
            val matchCat = uiState.selectedCategory == null || file.category == uiState.selectedCategory
            val matchHealth = uiState.selectedHealth == null || file.healthState == uiState.selectedHealth
            val matchQuery = uiState.searchQuery.isBlank() ||
                    file.name.contains(uiState.searchQuery, ignoreCase = true) ||
                    file.detectedExtension.contains(uiState.searchQuery, ignoreCase = true) ||
                    file.magicSignature.contains(uiState.searchQuery, ignoreCase = true)
            matchCat && matchHealth && matchQuery
        }
    }

    val selectedCount = uiState.allFiles.count { it.isSelected }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search Input
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("search_files_input"),
                placeholder = {
                    Text(
                        text = if (isAr) "بحث بالاسم، الامتداد أو البصمة..." else "Search by name, extension, magic byte...",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Slate400)
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Slate400)
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Slate400)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = Slate700
                )
            )

            // Category Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedCategory == null,
                        onClick = { onCategoryFilter(null) },
                        label = {
                            Text(if (isAr) "الكل (${uiState.allFiles.size})" else "All (${uiState.allFiles.size})")
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanAccent,
                            selectedLabelColor = Color.Black
                        )
                    )
                }

                items(FileCategory.values()) { cat ->
                    val count = uiState.allFiles.count { it.category == cat }
                    if (count > 0 || uiState.selectedCategory == cat) {
                        FilterChip(
                            selected = uiState.selectedCategory == cat,
                            onClick = {
                                onCategoryFilter(if (uiState.selectedCategory == cat) null else cat)
                            },
                            label = {
                                Text("${if (isAr) cat.labelAr else cat.label} ($count)")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = getCategoryIcon(cat),
                                    contentDescription = cat.name,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanAccent,
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }
            }

            // Quick Selection / Health actions bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isAr) "النتائج: ${filteredFiles.size}" else "Found: ${filteredFiles.size}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onSelectAll(selectedCount != uiState.allFiles.size) },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = if (selectedCount == uiState.allFiles.size)
                                (if (isAr) "إلغاء الكل" else "Deselect")
                            else
                                (if (isAr) "تحديد الكل" else "Select All"),
                            fontSize = 12.sp,
                            color = CyanAccent
                        )
                    }

                    val repairableCount = uiState.allFiles.count { it.healthState == FileHealthState.REPAIRABLE }
                    if (repairableCount > 0) {
                        TextButton(
                            onClick = onSelectOnlyRepairable,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                text = if (isAr) "تحديد التالف ($repairableCount)" else "Repairable ($repairableCount)",
                                fontSize = 12.sp,
                                color = AmberWarning
                            )
                        }
                    }
                }
            }

            // Empty state or Files List
            if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FilterAlt,
                            contentDescription = "Empty",
                            tint = Slate400,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isAr) "لا توجد ملفات تطابق معايير الفلترة" else "No files match active filter",
                            style = MaterialTheme.typography.titleSmall.copy(color = Slate400)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = if (selectedCount > 0) 76.dp else 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredFiles, key = { it.id }) { file ->
                        FileCardItem(
                            file = file,
                            isAr = isAr,
                            onToggleSelect = { onToggleSelect(file.id) },
                            onInspect = { onInspectFile(file) },
                            onRescueSingle = { onRescueSingle(file) },
                            onRepair = { onRepairTarget(file) }
                        )
                    }
                }
            }
        }

        // Bottom Sticky Batch Rescue Action
        AnimatedVisibility(
            visible = selectedCount > 0,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Slate900,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isAr) "المحدد: $selectedCount ملف" else "$selectedCount files selected",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        val totalSelectedBytes = uiState.allFiles.filter { it.isSelected }.sumOf { it.size }
                        Text(
                            text = FileRescueManager.formatFileSize(totalSelectedBytes),
                            style = MaterialTheme.typography.bodySmall.copy(color = Slate400)
                        )
                    }

                    Button(
                        onClick = onRescueSelected,
                        enabled = !uiState.isBatchRescuing,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldRescue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("rescue_selected_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Rescue", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAr) "استعادة وحفظ المحدد" else "Rescue Selected",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileCardItem(
    file: RescuedFile,
    isAr: Boolean,
    onToggleSelect: () -> Unit,
    onInspect: () -> Unit,
    onRescueSingle: () -> Unit,
    onRepair: () -> Unit
) {
    val context = LocalContext.current
    val healthColor = when (file.healthState) {
        FileHealthState.EXCELLENT -> EmeraldRescue
        FileHealthState.GOOD -> CyanAccent
        FileHealthState.REPAIRABLE -> AmberWarning
        FileHealthState.CORRUPTED -> RoseCorrupted
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (file.isSelected) CyanAccent else Slate800,
                RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox for selection
            Checkbox(
                checked = file.isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = CyanAccent,
                    uncheckedColor = Slate400
                ),
                modifier = Modifier.testTag("select_file_${file.id.take(6)}")
            )

            // Thumbnail / Category Icon Box
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Slate800),
                contentAlignment = Alignment.Center
            ) {
                if (file.category == FileCategory.PHOTO && (file.uri != null || (file.path.isNotEmpty() && File(file.path).exists()))) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(file.uri ?: File(file.path))
                            .crossfade(true)
                            .build(),
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = getCategoryIcon(file.category),
                        contentDescription = file.category.name,
                        tint = healthColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Health badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(healthColor.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${file.healthScore}% ${if (isAr) file.healthState.labelAr else file.healthState.label}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = healthColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }

                    // Magic Signature badge
                    if (file.magicSignature.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Slate800)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = file.magicSignature.take(16),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Slate400,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = FileRescueManager.formatFileSize(file.size),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall.copy(color = Slate700)
                    )
                    Text(
                        text = if (isAr) file.sourceType.labelAr else file.sourceType.label,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Slate400,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Action Buttons Column
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Inspect Hex Dump button
                IconButton(
                    onClick = onInspect,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Inspect",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // If repairable, show repair shortcut; else show quick save
                if (file.healthState == FileHealthState.REPAIRABLE) {
                    IconButton(
                        onClick = onRepair,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Repair",
                            tint = AmberWarning,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onRescueSingle,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (file.isRescued) Icons.Default.CheckCircle else Icons.Default.Save,
                            contentDescription = "Save",
                            tint = if (file.isRescued) EmeraldRescue else Slate400,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

fun getCategoryIcon(category: FileCategory): ImageVector {
    return when (category) {
        FileCategory.PHOTO -> Icons.Default.Image
        FileCategory.VIDEO -> Icons.Default.Movie
        FileCategory.AUDIO -> Icons.Default.AudioFile
        FileCategory.DOCUMENT -> Icons.Default.Description
        FileCategory.DATABASE -> Icons.Default.Save
        FileCategory.ARCHIVE -> Icons.Default.Archive
        FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

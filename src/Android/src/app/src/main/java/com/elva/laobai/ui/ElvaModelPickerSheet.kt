/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Plain-data description of a model as it should appear in the picker UI.
 * The NavGraph builds these entries from ModelManagerUiState so this composable
 * stays decoupled from any ViewModel.
 */
data class ModelPickerEntry(
    val id: String,
    val displayName: String,
    val sizeLabel: String,
    val status: ModelPickerStatus,
)

enum class ModelPickerStatus {
    READY,        // downloaded and initialized
    DOWNLOADED,   // downloaded but not initialized
    NOT_DOWNLOADED,
    DOWNLOADING,
    FAILED,
}

/**
 * A modal bottom sheet listing all available models for elderly users.
 *
 * - Tap a READY / DOWNLOADED model → triggers [onSelectModel].
 * - Tap a NOT_DOWNLOADED / FAILED model → triggers [onDownloadModel].
 * - DOWNLOADING entries are non-interactive (show progress).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElvaModelPickerSheet(
    models: List<ModelPickerEntry>,
    selectedModelId: String,
    onSelectModel: (ModelPickerEntry) -> Unit,
    onDownloadModel: (ModelPickerEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "选择 AI 模型",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "点击模型下载或切换",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (models.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无可用模型",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(models, key = { it.id }) { entry ->
                        ModelPickerRow(
                            entry = entry,
                            isSelected = entry.id == selectedModelId,
                            onSelect = { onSelectModel(entry) },
                            onDownload = { onDownloadModel(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    entry: ModelPickerEntry,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val (statusText, statusColor, statusIcon) = statusVisuals(entry.status)

    val canDownload = entry.status == ModelPickerStatus.NOT_DOWNLOADED ||
        entry.status == ModelPickerStatus.FAILED
    val canSelect = entry.status == ModelPickerStatus.READY ||
        entry.status == ModelPickerStatus.DOWNLOADED
    val clickAction: () -> Unit =
        when {
            canDownload -> onDownload
            canSelect -> onSelect
            else -> { -> }
        }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canDownload || canSelect, onClick = clickAction),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Status icon (left)
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (entry.status) {
                    ModelPickerStatus.DOWNLOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            // Name + size
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.sizeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = CircleShape,
                            ),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Selected checkmark
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "当前使用中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else if (canDownload) {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = "下载",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun statusVisuals(status: ModelPickerStatus): Triple<String, androidx.compose.ui.graphics.Color, ImageVector> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        ModelPickerStatus.READY ->
            Triple("已就绪", scheme.primary, Icons.Rounded.CheckCircle)
        ModelPickerStatus.DOWNLOADED ->
            Triple("已下载", scheme.tertiary, Icons.Rounded.CheckCircle)
        ModelPickerStatus.NOT_DOWNLOADED ->
            Triple("点击下载", scheme.onSurfaceVariant, Icons.Rounded.CloudDownload)
        ModelPickerStatus.DOWNLOADING ->
            Triple("下载中...", scheme.primary, Icons.Rounded.CloudDownload)
        ModelPickerStatus.FAILED ->
            Triple("下载失败", scheme.error, Icons.Rounded.ErrorOutline)
    }
}
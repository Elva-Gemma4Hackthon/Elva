/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Simple settings screen for elderly users.
 *
 * Contains:
 *  - TTS (voice playback) on/off
 *  - Accessibility service status + jump to system settings
 *  - "管理 AI 模型" — opens an in-page bottom sheet ([ElvaModelPickerSheet])
 *    to download or switch the active model. This avoids bouncing the user
 *    to the unfriendly Google AI Edge Gallery manager screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElvaSettingsScreen(
    ttsEnabled: Boolean,
    onToggleTts: () -> Unit,
    isAccessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    // --- Model picker wiring ---
    availableModels: List<ModelPickerEntry>,
    selectedModelId: String,
    selectedModelLabel: String,
    onSelectModel: (ModelPickerEntry) -> Unit,
    onDownloadModel: (ModelPickerEntry) -> Unit,
    onBack: () -> Unit,
) {
    var showModelSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("声音")
            SettingsCard {
                SettingsRowWithSwitch(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    title = "语音播报",
                    subtitle = if (ttsEnabled) "开启：老白的回复会读出来" else "关闭：只显示文字",
                    checked = ttsEnabled,
                    onCheckedChange = { onToggleTts() },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            SectionTitle("辅助功能")
            SettingsCard {
                SettingsRowWithStatus(
                    icon = Icons.Rounded.Accessibility,
                    title = "无障碍服务",
                    subtitle = "开启后才能帮您操作手机（填表、打电话等）",
                    statusText = if (isAccessibilityEnabled) "已开启" else "未开启",
                    statusIsGood = isAccessibilityEnabled,
                    onClick = onOpenAccessibilitySettings,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            SectionTitle("AI 模型")
            SettingsCard {
                SettingsRowWithArrow(
                    icon = Icons.Rounded.Memory,
                    title = "AI 模型",
                    subtitle = selectedModelLabel,
                    onClick = { showModelSheet = true },
                )
            }
        }
    }

    if (showModelSheet) {
        ElvaModelPickerSheet(
            models = availableModels,
            selectedModelId = selectedModelId,
            onSelectModel = {
                onSelectModel(it)
                showModelSheet = false
            },
            onDownloadModel = {
                onDownloadModel(it)
                // Keep the sheet open so user sees the download progress.
            },
            onDismiss = { showModelSheet = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsRowWithSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun SettingsRowWithStatus(
    icon: ImageVector,
    title: String,
    subtitle: String,
    statusText: String,
    statusIsGood: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (statusIsGood)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (statusIsGood)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = if (statusIsGood)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SettingsRowWithArrow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
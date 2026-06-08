/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.ui.theme.customColors

/** Model initialization state for UI banner. */
enum class ModelState { LOADING, READY, NOT_DOWNLOADED, ERROR }

/**
 * The main voice-first home screen for Elva LaoBai.
 * Designed for elderly users: large buttons, high contrast, simple layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElvaVoiceScreen(
    isListening: Boolean = false,
    recognizedText: String = "",
    responseText: String = "",
    isThinking: Boolean = false,
    isExecuting: Boolean = false,
    executionStatus: String? = null,
    onMicClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    ttsEnabled: Boolean = true,
    onToggleTts: () -> Unit = {},
    modelErrorMessage: String? = null,
    // Form filling state (Case 1)
    isFormFilling: Boolean = false,
    formTemplateName: String? = null,
    formProgress: String? = null,
    // Health consultation state (Case 2)
    isHealthConsultation: Boolean = false,
    healthTriageStage: String? = null,
    healthTriageQuestion: String? = null,
    // Accessibility service status
    isAccessibilityEnabled: Boolean = true,
    onOpenAccessibilitySettings: () -> Unit = {},
    // Quick action callback for direct text injection
    onQuickAction: (String) -> Unit = {},
    // Model state for UI banner
    modelState: ModelState = ModelState.LOADING,
    modelName: String = "",
    onNavigateToModelManager: () -> Unit = {},
) {
    val context = LocalContext.current
    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            audioPermissionGranted = granted
            if (granted) {
                onMicClick()
            }
        }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "老白",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTts) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Toggle voice",
                            tint = if (ttsEnabled)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ===== Status / Response Area =====
            // ===== Model Status Banner =====
            when (modelState) {
                ModelState.LOADING -> {
                    StatusBanner(
                        text = "AI 模型加载中，请稍候...",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                ModelState.NOT_DOWNLOADED -> {
                    StatusBanner(
                        text = "尚未下载 AI 模型。",
                        containerColor = MaterialTheme.customColors.errorContainerColor,
                        contentColor = MaterialTheme.customColors.errorTextColor,
                        action = {
                            TextButton(onClick = onNavigateToModelManager) {
                                Text("去下载")
                            }
                        },
                    )
                }
                ModelState.ERROR -> {
                    StatusBanner(
                        text = "AI 模型加载失败，使用基础模式。",
                        supportingText = modelErrorMessage,
                        containerColor = MaterialTheme.customColors.warningContainerColor,
                        contentColor = MaterialTheme.customColors.warningTextColor,
                    )
                }
                ModelState.READY -> { /* No banner when model is ready */ }
            }

            // Accessibility service warning banner (Task 12)
            if (!isAccessibilityEnabled) {
                StatusBanner(
                    text = "无障碍服务未开启，代操作等功能不可用。",
                    containerColor = MaterialTheme.customColors.warningContainerColor,
                    contentColor = MaterialTheme.customColors.warningTextColor,
                    action = {
                        TextButton(onClick = onOpenAccessibilitySettings) {
                            Text("去开启")
                        }
                    },
                )
            }

            // Form filling progress bar (Case 1)
            if (isFormFilling && formTemplateName != null) {
                StatusBanner(
                    text = "正在填写：$formTemplateName",
                    supportingText = formProgress,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // Health consultation progress (Case 2)
            if (isHealthConsultation) {
                StatusBanner(
                    text = "健康咨询中",
                    supportingText = healthTriageStage?.let { "阶段：$it" },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isExecuting && executionStatus != null -> {
                        Text(
                            text = executionStatus,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                    isThinking -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "老白在想想...",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Lao Bai is thinking...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    responseText.isNotEmpty() -> {
                        ResponseCard(
                            text = responseText,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    recognizedText.isNotEmpty() -> {
                        ResponseCard(
                            text = "\"$recognizedText\"",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "按下按钮，跟老白说话",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the button to talk to Lao Bai",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isListening) {
                            Box(
                                modifier = Modifier
                                    .size(168.dp * pulseScale)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                        shape = CircleShape,
                                    )
                            )
                        }
                        IconButton(
                            onClick = {
                                if (audioPermissionGranted) {
                                    onMicClick()
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(128.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isListening)
                                    MaterialTheme.customColors.recordButtonBgColor
                                else
                                    MaterialTheme.colorScheme.primary,
                            ),
                            shape = CircleShape,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Press to speak",
                                modifier = Modifier.size(52.dp),
                                tint = Color.White,
                            )
                        }
                    }

                    Text(
                        text = if (isListening) "正在听您说话..." else "按下说话",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                text = "常用操作",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )

            val chips = listOf(
                "\uD83C\uDFE5 我不舒服" to "小白，我不舒服",
                "\uD83D\uDCCB 帮我填表" to "帮我填表",
                "\uD83D\uDCCA 看病挂号" to "帮我挂号",
                "\uD83D\uDCDE 打电话" to "给儿子打电话",
                "\uD83D\uDDBC 看照片" to "看看照片",
                "\uD83D\uDD52 现在几点" to "现在几点",
            )
            val columns = 3
            val chipRows = chips.chunked(columns)
            chipRows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = if (rowIndex == chipRows.lastIndex) 24.dp else 8.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (label, action) ->
                        QuickChip(
                            label = label,
                            onClick = { onQuickAction(action) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Fill empty cells if last row is incomplete
                    repeat(columns - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun StatusBanner(
    text: String,
    containerColor: Color,
    contentColor: Color,
    supportingText: String? = null,
    action: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) {
                leading()
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    fontWeight = FontWeight.Medium,
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }
            if (action != null) {
                action()
            }
        }
    }
}

@Composable
private fun ResponseCard(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            textAlign = TextAlign.Center,
            color = contentColor,
        )
    }
}

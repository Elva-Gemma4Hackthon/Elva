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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "Lao Bai",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "老白",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTts) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = "Toggle voice",
                            tint = if (ttsEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            // ===== Status / Response Area =====
            // ===== Model Status Banner =====
            when (modelState) {
                ModelState.LOADING -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE3F2FD),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF1565C0),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "AI 模型加载中，请稍候...",
                                fontSize = 16.sp,
                                color = Color(0xFF1565C0),
                            )
                        }
                    }
                }
                ModelState.NOT_DOWNLOADED -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFEBEE),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "⚠️ 尚未下载 AI 模型。",
                                fontSize = 16.sp,
                                color = Color(0xFFC62828),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onNavigateToModelManager) {
                                Text("去下载", color = Color(0xFFC62828))
                            }
                        }
                    }
                }
                ModelState.ERROR -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF8E1),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "AI 模型加载失败，使用基础模式。",
                                fontSize = 16.sp,
                                color = Color(0xFFF57F17),
                            )
                            if (!modelErrorMessage.isNullOrBlank()) {
                                Text(
                                    text = modelErrorMessage,
                                    fontSize = 14.sp,
                                    color = Color(0xFF8D6E63),
                                )
                            }
                        }
                    }
                }
                ModelState.READY -> { /* No banner when model is ready */ }
            }

            // Accessibility service warning banner (Task 12)
            if (!isAccessibilityEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "⚠️ 无障碍服务未开启，部分功能不可用。请在设置中开启。",
                            fontSize = 16.sp,
                            color = Color(0xFFE65100),
                        )
                    }
                }
            }

            // Form filling progress bar (Case 1)
            if (isFormFilling && formTemplateName != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "📝 正在填写: $formTemplateName",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (formProgress != null) {
                            Text(
                                text = formProgress,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }

            // Health consultation progress (Case 2)
            if (isHealthConsultation) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "🏥 健康咨询中",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        if (healthTriageStage != null) {
                            Text(
                                text = "阶段: $healthTriageStage",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isExecuting && executionStatus != null -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = executionStatus,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    isThinking -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "老白在想想...",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Lao Bai is thinking...",
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    responseText.isNotEmpty() -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Text(
                                text = responseText,
                                fontSize = 26.sp,
                                lineHeight = 36.sp,
                                modifier = Modifier.padding(24.dp),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    recognizedText.isNotEmpty() -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Text(
                                text = "\"$recognizedText\"",
                                fontSize = 26.sp,
                                lineHeight = 36.sp,
                                modifier = Modifier.padding(24.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "按下按钮，跟老白说话",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the button to talk to Lao Bai",
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // ===== Big Mic Button =====
            Box(contentAlignment = Alignment.Center) {
                // Pulse ring when listening
                if (isListening) {
                    Box(
                        modifier = Modifier
                            .size(180.dp * pulseScale)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
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
                    modifier = Modifier.size(160.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isListening)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary,
                    ),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Press to speak",
                        modifier = Modifier.size(72.dp),
                        tint = Color.White,
                    )
                }
            }

            // ===== Hint Text =====
            Text(
                text = if (isListening) "正在听您说话..." else "按下说话",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            // ===== Quick Action Chips =====
            // Grid layout: 3 columns, equal-width chips for tidy alignment.
            val chips = listOf(
                "\uD83C\uDFE5 我不舒服" to "小白，我不舒服",
                "\uD83D\uDCCB 帮我填表" to "帮我填表",
                "\uD83D\uDCCA 看病挂号" to "帮我挂号",
                "\uD83D\uDCDE 打电话" to "给儿子打电话",
                "\uD83D\uDDBC 看照片" to "看看照片",
                "\uD83D\uDD52 现在几点" to "现在几点",
            )
            val columns = 3
            chips.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = if (rowIndex == chips.chunked(columns).lastIndex) 24.dp else 8.dp
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
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

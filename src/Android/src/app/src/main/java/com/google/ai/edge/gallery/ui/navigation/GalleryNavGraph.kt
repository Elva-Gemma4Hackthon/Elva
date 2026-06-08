/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */

package com.google.ai.edge.gallery.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val TAG = "ElvaNavGraph"
private const val ROUTE_ELVA_VOICE = "elva_voice"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ROUTE_USER_MEMORY = "user_memory"

@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> modelManagerViewModel.setAppInForeground(foreground = true)
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> modelManagerViewModel.setAppInForeground(foreground = false)
        else -> Unit
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(
    navController = navController,
    startDestination = ROUTE_ELVA_VOICE,
    modifier = modifier,
  ) {
    composable(route = ROUTE_ELVA_VOICE) {
      val voiceViewModel: com.elva.laobai.ui.ElvaVoiceViewModel = hiltViewModel()
      val uiState by voiceViewModel.uiState.collectAsState()
      val context = LocalContext.current
      val screenLifecycleOwner = LocalLifecycleOwner.current
      var isAccessibilityEnabled by remember {
        mutableStateOf(com.elva.laobai.accessibility.ElvaAccessibilityService.isRunning())
      }

      DisposableEffect(screenLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
            isAccessibilityEnabled =
              com.elva.laobai.accessibility.ElvaAccessibilityService.isRunning()
          }
        }
        screenLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { screenLifecycleOwner.lifecycle.removeObserver(observer) }
      }

      LaunchedEffect(
        modelManagerUiState.modelDownloadStatus,
        modelManagerUiState.modelImportingUpdateTrigger,
        modelManagerUiState.tasks,
        modelManagerUiState.selectedModel.name,
      ) {
        val bridge = com.elva.laobai.inference.ElvaInferenceBridge
        if (bridge.state.value.isInitializing) {
          return@LaunchedEffect
        }
        val downloadedModels = modelManagerViewModel.getAllDownloadedModels()
        val selectedModel = modelManagerUiState.selectedModel
        val targetModel = when {
          selectedModel.name.isNotEmpty() && downloadedModels.any { it.name == selectedModel.name } ->
            selectedModel
          else ->
            downloadedModels.firstOrNull {
              it.name.contains("gemma-4-e4b", ignoreCase = true)
            } ?: downloadedModels.firstOrNull {
              it.name.contains("gemma-4-e2b", ignoreCase = true)
            } ?: downloadedModels.firstOrNull {
              it.name.contains("gemma", ignoreCase = true)
            }
        }
        if (targetModel != null) {
          if (selectedModel.name != targetModel.name) {
            modelManagerViewModel.selectModel(targetModel)
          }
          if (bridge.state.value.isModelReady && bridge.state.value.modelName == targetModel.name) {
            return@LaunchedEffect
          }
          Log.d(TAG, "Initializing Elva with model: ${targetModel.name}")
          bridge.initialize(
            model = targetModel,
            systemPrompt = com.elva.laobai.inference.ElvaFunctions.buildSystemPromptFragment(),
            context = context,
            onReady = { Log.d(TAG, "Elva model ready: ${targetModel.name}") },
          )
        }
      }

      val bridgeState by com.elva.laobai.inference.ElvaInferenceBridge.state.collectAsState()
      val downloadedModels =
        remember(
          modelManagerUiState.modelDownloadStatus,
          modelManagerUiState.modelImportingUpdateTrigger,
          modelManagerUiState.tasks,
        ) {
          modelManagerViewModel.getAllDownloadedModels()
        }
      val modelState = when {
        bridgeState.isModelReady -> com.elva.laobai.ui.ModelState.READY
        bridgeState.isInitializing -> com.elva.laobai.ui.ModelState.LOADING
        downloadedModels.isEmpty() -> com.elva.laobai.ui.ModelState.NOT_DOWNLOADED
        else -> com.elva.laobai.ui.ModelState.ERROR
      }

      com.elva.laobai.ui.ElvaVoiceScreen(
        isListening = uiState.isListening,
        recognizedText = uiState.recognizedText,
        responseText = uiState.responseText,
        isThinking = uiState.isThinking,
        isExecuting = uiState.isExecuting,
        executionStatus = uiState.executionStatus,
        onMicClick = { voiceViewModel.toggleListening() },
        onSettingsClick = { navController.navigate(ROUTE_USER_MEMORY) },
        ttsEnabled = uiState.ttsEnabled,
        onToggleTts = { voiceViewModel.toggleTts() },
        modelErrorMessage = bridgeState.lastError,
        isFormFilling = uiState.isFormFilling,
        formTemplateName = uiState.formTemplateName,
        formProgress = uiState.formProgress,
        isHealthConsultation = uiState.isHealthConsultation,
        healthTriageStage = uiState.healthTriageStage,
        healthTriageQuestion = uiState.healthTriageQuestion,
        isAccessibilityEnabled = isAccessibilityEnabled,
        onOpenAccessibilitySettings = {
          com.elva.laobai.accessibility.AccessibilitySettingsNavigator
            .openElvaAccessibilitySettings(context)
        },
        onQuickAction = { text -> voiceViewModel.processQuickAction(text) },
        modelState = modelState,
        modelName = bridgeState.modelName,
        onNavigateToModelManager = { navController.navigate(ROUTE_MODEL_MANAGER) },
      )
    }

    composable(route = ROUTE_USER_MEMORY) {
      com.elva.laobai.ui.UserMemorySettingsScreen(
        onBack = { navController.popBackStack() },
      )
    }

    composable(route = ROUTE_MODEL_MANAGER) {
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        navigateUp = { navController.popBackStack() },
        onModelSelected = { _, model ->
          modelManagerViewModel.selectModel(model)
          navController.popBackStack()
        },
        onBenchmarkClicked = {},
      )
    }
  }
}

/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.elva.laobai.accessibility.AccessibilitySettingsNavigator
import com.elva.laobai.accessibility.ElvaAccessibilityService
import com.elva.laobai.ui.ElvaSettingsScreen
import com.elva.laobai.ui.ElvaVoiceScreen
import com.elva.laobai.ui.ElvaVoiceViewModel
import com.elva.laobai.ui.ModelPickerEntry
import com.elva.laobai.ui.ModelPickerStatus
import com.elva.laobai.ui.ModelState
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.isLegacyTasks
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val TAG = "GalleryNav"
private const val ROUTE_VOICE = "elva_voice"
private const val ROUTE_SETTINGS = "elva_settings"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ROUTE_MODEL = "route_model"

@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current

  // Track app foreground/background state for model-download policies.
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
    startDestination = ROUTE_VOICE,
    modifier = modifier,
  ) {
    // Elva LaoBai voice-first home screen (the actual app start destination).
    composable(route = ROUTE_VOICE) {
      val elvaVoiceViewModel: ElvaVoiceViewModel = hiltViewModel()
      val elvaVoiceUiState by elvaVoiceViewModel.uiState.collectAsState()
      val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
      val context = LocalContext.current

      // Derive the model loading state from ModelManagerViewModel so the home screen
      // can show an appropriate banner (downloading / initializing / ready / error / etc).
      val selectedModel = modelManagerUiState.selectedModel
      val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
      val modelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]

      val isDownloading =
        modelDownloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS ||
          modelDownloadStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED ||
          modelDownloadStatus?.status == ModelDownloadStatusType.UNZIPPING

      val (modelState, modelError) =
        when {
          // Model selected + ready
          modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED ->
            ModelState.READY to null
          // Initializing (loading onto device)
          modelInitStatus?.status == ModelInitializationStatusType.INITIALIZING ->
            ModelState.LOADING to null
          // Initialization failed
          modelInitStatus?.status == ModelInitializationStatusType.ERROR ->
            ModelState.ERROR to modelInitStatus.error.ifEmpty { null }
          // Downloading model file
          isDownloading -> ModelState.DOWNLOADING to null
          // Downloaded but not yet initialized — show loading banner briefly
          modelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED ->
            ModelState.LOADING to null
          // No model selected yet (allowlist still loading or user hasn't picked one)
          selectedModel.name.isEmpty() -> ModelState.LOADING to null
          // Fallback: model not downloaded
          else -> ModelState.NOT_DOWNLOADED to null
        }

      // ---- Auto-initialize the selected model once it's downloaded ----
      val selectedTask =
        remember(selectedModel.name, modelManagerUiState.tasks) {
          modelManagerUiState.tasks.firstOrNull { task ->
            task.models.any { it.name == selectedModel.name }
          }
        }

      LaunchedEffect(selectedModel.name, modelDownloadStatus?.status, selectedTask?.id) {
        val task = selectedTask
        val model = selectedModel
        if (
          task != null &&
            model.name.isNotEmpty() &&
            modelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED &&
            modelInitStatus?.status != ModelInitializationStatusType.INITIALIZING &&
            modelInitStatus?.status != ModelInitializationStatusType.INITIALIZED
        ) {
          Log.d(TAG, "Auto-initializing model '${model.name}' on home screen")
          modelManagerViewModel.initializeModel(
            context = context,
            task = task,
            model = model,
          )
        }
      }

      // ---- Real-time accessibility-service status ----
      var isAccessibilityEnabled by remember {
        mutableStateOf(ElvaAccessibilityService.isRunning())
      }
      DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
            isAccessibilityEnabled = ElvaAccessibilityService.isRunning()
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
      }

      ElvaVoiceScreen(
        // Voice recognition / chat state
        isListening = elvaVoiceUiState.isListening,
        recognizedText = elvaVoiceUiState.recognizedText,
        responseText = elvaVoiceUiState.responseText,
        isThinking = elvaVoiceUiState.isThinking,
        isExecuting = elvaVoiceUiState.isExecuting,
        executionStatus = elvaVoiceUiState.executionStatus,
        onMicClick = elvaVoiceViewModel::toggleListening,
        onSettingsClick = {
          navController.navigate(ROUTE_SETTINGS)
        },
        // TTS
        ttsEnabled = elvaVoiceUiState.ttsEnabled,
        onToggleTts = elvaVoiceViewModel::toggleTts,
        // Form filling state (Case 1)
        isFormFilling = elvaVoiceUiState.isFormFilling,
        formTemplateName = elvaVoiceUiState.formTemplateName,
        formProgress = elvaVoiceUiState.formProgress,
        // Health consultation state (Case 2)
        isHealthConsultation = elvaVoiceUiState.isHealthConsultation,
        healthTriageStage = elvaVoiceUiState.healthTriageStage,
        healthTriageQuestion = elvaVoiceUiState.healthTriageQuestion,
        // Accessibility service status (live)
        isAccessibilityEnabled = isAccessibilityEnabled,
        onOpenAccessibilitySettings = {
          AccessibilitySettingsNavigator.openElvaAccessibilitySettings(context)
        },
        // Quick action chip taps (no microphone needed)
        onQuickAction = elvaVoiceViewModel::processQuickAction,
        // Model state for UI banner
        modelState = modelState,
        modelName = selectedModel.name,
        modelErrorMessage = modelError,
        // "查看详情" — jump to settings page where the user can open the model picker.
        onNavigateToModelManager = {
          navController.navigate(ROUTE_SETTINGS)
        },
      )
    }

    // Elva LaoBai settings screen — TTS toggle, accessibility status, model picker sheet.
    composable(route = ROUTE_SETTINGS) {
      val elvaVoiceViewModel: ElvaVoiceViewModel = hiltViewModel()
      val elvaVoiceUiState by elvaVoiceViewModel.uiState.collectAsState()
      val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
      val context = LocalContext.current

      var isAccessibilityEnabled by remember {
        mutableStateOf(ElvaAccessibilityService.isRunning())
      }
      DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
            isAccessibilityEnabled = ElvaAccessibilityService.isRunning()
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
      }

      val selectedModel = modelManagerUiState.selectedModel

      // Flatten all tasks/models into picker entries, sorted so the currently
      // selected model appears first and downloaded/ready models are next.
      val pickerEntries =
        remember(modelManagerUiState) {
          buildModelPickerEntries(modelManagerUiState)
        }
      val selectedLabel =
        pickerEntries.firstOrNull { it.id == selectedModel.name }?.let { it.displayName }
          ?: if (selectedModel.name.isEmpty()) "未选择" else selectedModel.name

      ElvaSettingsScreen(
        ttsEnabled = elvaVoiceUiState.ttsEnabled,
        onToggleTts = elvaVoiceViewModel::toggleTts,
        isAccessibilityEnabled = isAccessibilityEnabled,
        onOpenAccessibilitySettings = {
          AccessibilitySettingsNavigator.openElvaAccessibilitySettings(context)
        },
        availableModels = pickerEntries,
        selectedModelId = selectedModel.name,
        selectedModelLabel = selectedLabel,
        onSelectModel = { entry ->
          // Find the model in tasks and select it.
          val match = findModelById(modelManagerUiState, entry.id)
          if (match != null) {
            Log.d(TAG, "User selected model: ${match.model.name}")
            modelManagerViewModel.selectModel(match.model)
          }
        },
        onDownloadModel = { entry ->
          val match = findModelById(modelManagerUiState, entry.id)
          if (match != null) {
            Log.d(TAG, "User requested download for model: ${match.model.name}")
            modelManagerViewModel.downloadModel(task = match.task, model = match.model)
          }
        },
        onBack = { navController.popBackStack() },
      )
    }

    composable(route = ROUTE_MODEL_MANAGER) {
      // Kept for legacy / debug access (deep links etc.). The user-facing flow
      // for elderly users is now the ElvaSettingsScreen model picker sheet.
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        navigateUp = { navController.popBackStack() },
        onModelSelected = { task, model ->
          modelManagerViewModel.selectModel(model)
          navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
        },
        onBenchmarkClicked = {},
      )
    }

    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}",
      arguments =
        listOf(
          navArgument("taskId") { type = NavType.StringType },
          navArgument("modelName") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
      val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
      val modelName = backStackEntry.arguments?.getString("modelName") ?: return@composable

      val model = modelManagerViewModel.getModelByName(modelName) ?: return@composable
      val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId) ?: return@composable

      modelManagerViewModel.selectModel(model)

      if (isLegacyTasks(customTask.task.id)) {
        customTask.MainScreen(
          data =
            CustomTaskDataForBuiltinTask(
              modelManagerViewModel = modelManagerViewModel,
              onNavUp = { navController.popBackStack() },
            )
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private data class ModelMatch(val task: com.google.ai.edge.gallery.data.Task, val model: Model)

private fun findModelById(
  state: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState,
  modelId: String,
): ModelMatch? {
  for (task in state.tasks) {
    for (m in task.models) {
      if (m.name == modelId) return ModelMatch(task, m)
    }
  }
  return null
}

/** Build the picker entry list from the model manager UI state. */
private fun buildModelPickerEntries(
  state: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState,
): List<ModelPickerEntry> {
  val out = mutableListOf<ModelPickerEntry>()
  for (task in state.tasks) {
    for (m in task.models) {
      val downloadStatus = state.modelDownloadStatus[m.name]
      val initStatus = state.modelInitializationStatus[m.name]
      val status =
        when {
          initStatus?.status == ModelInitializationStatusType.INITIALIZED ->
            ModelPickerStatus.READY
          downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED ->
            ModelPickerStatus.DOWNLOADED
          downloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS ||
            downloadStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED ||
            downloadStatus?.status == ModelDownloadStatusType.UNZIPPING ->
            ModelPickerStatus.DOWNLOADING
          downloadStatus?.status == ModelDownloadStatusType.FAILED ->
            ModelPickerStatus.FAILED
          else -> ModelPickerStatus.NOT_DOWNLOADED
        }
      out +=
        ModelPickerEntry(
          id = m.name,
          displayName = m.displayName.ifEmpty { m.name },
          sizeLabel = humanReadableSize(m.sizeInBytes),
          status = status,
        )
    }
  }
  // Sort: selected → ready → downloaded → downloading → not_downloaded → failed.
  val rank: (ModelPickerStatus) -> Int = {
    when (it) {
      ModelPickerStatus.READY -> 0
      ModelPickerStatus.DOWNLOADED -> 1
      ModelPickerStatus.DOWNLOADING -> 2
      ModelPickerStatus.NOT_DOWNLOADED -> 3
      ModelPickerStatus.FAILED -> 4
    }
  }
  val selectedId = state.selectedModel.name
  return out.sortedWith(
    compareBy(
      { it.id != selectedId },
      { rank(it.status) },
      { it.displayName },
    )
  )
}

private fun humanReadableSize(bytes: Long): String {
  if (bytes <= 0L) return "大小未知"
  val mb = bytes / 1024.0 / 1024.0
  return when {
    mb >= 1024.0 -> String.format("%.1f GB", mb / 1024.0)
    mb >= 1.0 -> String.format("%.0f MB", mb)
    else -> String.format("%.0f KB", bytes / 1024.0)
  }
}
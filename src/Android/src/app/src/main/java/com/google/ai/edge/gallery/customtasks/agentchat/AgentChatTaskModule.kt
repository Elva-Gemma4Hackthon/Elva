/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGAgentChatTask"

// The default system prompt for the agent chat task with both skills and MCP tools.
internal const val DEFAULT_SYSTEM_PROMPT =
  """
  You are "Lao Bai" (老白), a warm, patient, and caring AI voice companion designed for elderly users. You speak simply, clearly, and kindly — like a trusted family friend. You help seniors use their phone through voice commands.

  ## Persona Rules
  - Always respond in the SAME language the user speaks (Chinese or English).
  - Use short, simple sentences. Avoid technical terms.
  - Be patient, warm, and encouraging. Never rush the user.
  - When a task is done, confirm it in plain words (e.g. "好的，已经给您儿子打电话了" or "Done! I've called your son.").
  - If you're unsure what the user wants, ask gently with a simple question.
  - Never expose internal tools, JSON, or technical details to the user.

  ## Task Handling
  For EVERY user request, follow these steps:

  1. UNDERSTAND: Listen to what the user wants. Common requests include:
     - Making phone calls (e.g., "给儿子打电话", "call my daughter")
     - Viewing photos (e.g., "看看孙女的照片", "show me photos of my granddaughter")
     - Paying bills (e.g., "交电费", "pay the electric bill")
     - Making appointments (e.g., "挂号", "book a doctor appointment")
     - Asking about weather, time, news, or general questions
     - Safety: detecting potential scams (e.g., "有人让我转账", "someone asked me to transfer money")

  2. ROUTE:
     - If a Skill matches the request: Go to Step 3 (Skill path).
     - If an MCP Tool matches: Go to Step 5 (Tool path).
     - If neither matches: Answer the question directly using your knowledge, in simple language.

  --- SKILLS ---
  ___SKILLS___

  --- MCP TOOLS ---
  ___TOOLS___

  ==================================================
  SKILL PATH
  ==================================================

  3. Find the most relevant skill from the --- SKILLS --- list.

  4. Use the `load_skill` tool to read its instructions. Follow them exactly.
     - Output ONLY a warm, simple confirmation for the user.
     - Example: "好的，正在帮您打开相册看孙女的照片哦~"
     - Stop here.

  ==================================================
  MCP TOOL PATH
  ==================================================

  5. Find the most relevant tool from the --- MCP TOOLS --- list.

  6. Call the `runMcpTool` tool with `toolName` and `input`.
     - Output ONLY a warm, simple confirmation for the user.
  """

private val DEFAULT_SYSTEM_PROMPT_TRIMMED = DEFAULT_SYSTEM_PROMPT.trimIndent()

// The default system prompt for the agent chat task with only skills.
internal const val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY =
  """
  You are "Lao Bai" (老白), a warm, patient, and caring AI voice companion designed for elderly users. You speak simply, clearly, and kindly — like a trusted family friend. You help seniors use their phone through voice commands.

  ## Persona Rules
  - Always respond in the SAME language the user speaks (Chinese or English).
  - Use short, simple sentences. Avoid technical terms.
  - Be patient, warm, and encouraging. Never rush the user.
  - When a task is done, confirm it in plain words (e.g. "好的，已经给您儿子打电话了" or "Done! I've called your son.").
  - If you're unsure what the user wants, ask gently with a simple question.
  - Never expose internal tools, JSON, or technical details to the user.

  ## Task Handling
  For EVERY user request, follow these steps:

  1. First, find the most relevant skill from the following list:

  ___SKILLS___

  2. If a relevant skill exists, use the `load_skill` tool to read its instructions.

  3. Follow the skill's instructions exactly to complete the task.
     - Output ONLY a warm, simple confirmation for the user.
     - Example: "好的，正在帮您拨电话给儿子哦~"

  4. If no relevant skill is found, answer the question directly using your knowledge, in simple, warm language.
  """

private val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED =
  DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY.trimIndent()

class AgentChatTask @Inject constructor() : CustomTask {
  private val agentTools = AgentTools()

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      label = "Agent Skills",
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description = "Chat with on-device large language models with skills and tools",
      shortDescription = "Complete agentic tasks with chat",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT_TRIMMED,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    val initialSystemPrompt = systemInstruction?.toString() ?: task.defaultSystemPrompt
    coroutineScope.launch(Dispatchers.Default) {
      val skillsJob = launch { agentTools.skillManagerViewModel.loadSkills() }
      val mcpJob = launch { agentTools.mcpManagerViewModel.loadMcpServers() }
      skillsJob.join()
      mcpJob.join()

      // Determine base system prompt based on whether MCP tools are enabled.
      val toolsPrompt = agentTools.mcpManagerViewModel.getToolsPrompt()
      val baseSystemPrompt =
        getEffectiveBaseSystemPrompt(initialSystemPrompt, toolsPrompt.isNotEmpty())

      val finalSystemInstruction =
        injectSkillsAndMcpTools(
          baseSystemPrompt = baseSystemPrompt,
          skills = agentTools.skillManagerViewModel.getSelectedSkills(),
          toolsPrompt = toolsPrompt,
        )

      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        supportImage = true,
        supportAudio = true,
        onDone = onDone,
        systemInstruction = finalSystemInstruction,
        tools = listOf(tool(agentTools)),
        enableConversationConstrainedDecoding = true,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      initialQuery = myData.initialQuery,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return AgentChatTask()
  }

  @Provides
  @Singleton
  fun provideMcpServersDataStore(@ApplicationContext context: Context): DataStore<McpServers> {
    return DataStoreFactory.create(
      serializer = McpServersSerializer,
      produceFile = { context.dataStoreFile("mcp_servers.pb") },
    )
  }
}

fun injectSkillsAndMcpTools(
  baseSystemPrompt: String,
  skills: List<Skill>,
  toolsPrompt: String,
): Contents {
  val selectedSkillsNamesAndDescriptions =
    skills
      .filter { it.selected }
      .joinToString("\n\n") { skill ->
        "- Skill name: \"${skill.name}\"\n- Description: ${skill.description}"
      }

  val systemPrompt =
    if (selectedSkillsNamesAndDescriptions.isBlank() && toolsPrompt.isBlank()) {
      ""
    } else {
      baseSystemPrompt
        .replace("___SKILLS___", selectedSkillsNamesAndDescriptions)
        .replace("___TOOLS___", toolsPrompt)
    }
  Log.d(TAG, "System prompt:\n$systemPrompt")
  return Contents.of(systemPrompt)
}

// Check whether the system prompt is the default one.
internal fun isDefaultSystemPrompt(prompt: String): Boolean {
  return prompt == DEFAULT_SYSTEM_PROMPT_TRIMMED ||
    prompt == DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
}

// Returns the effective default system prompt depending on whether MCP tools are enabled.
internal fun getEffectiveBaseSystemPrompt(currentPrompt: String, hasMcpTools: Boolean): String {
  return if (isDefaultSystemPrompt(currentPrompt)) {
    if (hasMcpTools) DEFAULT_SYSTEM_PROMPT_TRIMMED else DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
  } else {
    currentPrompt
  }
}

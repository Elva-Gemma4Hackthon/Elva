package com.elva.laobai.model

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class ElvaModelTask @Inject constructor() : CustomTask {
    override val task: Task =
        Task(
            id = BuiltInTaskId.LLM_CHAT,
            label = "Elva 模型",
            category = Category.LLM,
            models = mutableListOf(),
            description = "用于老白两个核心 case 的本地模型下载、导入与管理。",
            shortDescription = "老白模型管理",
        )

    override fun initializeModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        systemInstruction: Contents?,
        onDone: (String) -> Unit,
    ) {
        onDone("")
    }

    override fun cleanUpModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: () -> Unit,
    ) {
        onDone()
    }

    @Composable
    override fun MainScreen(data: Any) {
        Box(modifier = Modifier.fillMaxSize())
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ElvaModelTaskModule {
    @Provides
    @IntoSet
    fun provideTask(): CustomTask = ElvaModelTask()
}

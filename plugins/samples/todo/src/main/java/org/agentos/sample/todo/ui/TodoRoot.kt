package org.agentos.sample.todo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.agentos.sample.todo.R

private const val NEW = "new"
private const val EDIT = "edit:"

/**
 * 根：列表页 ⇄ 详情 / 新建页（一个简单的返回栈，旋转和进程重建后还在），底部提示条（完成 / 删除的“撤销”）统一在这里。
 * 返回栈里存的是字符串：`new` 是新建页，`edit:<id>` 是某条的详情页；栈空就是列表。
 */
@Composable
fun TodoRoot(vm: TodoViewModel, onLanguage: () -> Unit) {
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var stack by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val route = stack.lastOrNull()
    // 上一次组合时的栈深：比较它就知道是往里进还是往回退（决定转场方向）
    val previousDepth = remember { mutableIntStateOf(0) }
    val depth = stack.size
    SideEffect { previousDepth.intValue = depth }

    fun push(r: String) { stack = ArrayList(stack + r) }
    fun pop() { stack = ArrayList(stack.dropLast(1)) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            // 每个提示条各自一个协程：新的来了就顶掉旧的
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val message: String
                var action: String? = resources.getString(R.string.snack_undo)
                when (event) {
                    is UndoEvent.Completed -> message = resources.getString(R.string.snack_completed, event.before.title)
                    is UndoEvent.Reopened -> message = resources.getString(R.string.snack_reopened, event.before.title)
                    is UndoEvent.Deleted -> {
                        val subtasks = event.items.size - 1
                        message = if (subtasks > 0) {
                            resources.getQuantityString(R.plurals.snack_deleted_with_subtasks, subtasks, event.items.first().title, subtasks)
                        } else {
                            resources.getString(R.string.snack_deleted, event.items.first().title)
                        }
                    }
                    UndoEvent.Failed -> {
                        message = resources.getString(R.string.snack_failed)
                        action = null
                    }
                }
                if (snackbar.showSnackbar(message = message, actionLabel = action, duration = androidx.compose.material3.SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    vm.undo(event)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding()) },
        floatingActionButton = {
            if (route == null) {
                ExtendedFloatingActionButton(
                    onClick = { push(NEW) },
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.action_new), maxLines = 1) },
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = route,
            modifier = Modifier.padding(padding).background(MaterialTheme.colorScheme.background),
            transitionSpec = {
                if (depth > previousDepth.intValue) {
                    (slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn()) togetherWith (fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 8 }))
                } else {
                    (slideInHorizontally(initialOffsetX = { -it / 8 }) + fadeIn()) togetherWith (fadeOut() + slideOutHorizontally(targetOffsetX = { it / 4 }))
                }
            },
            label = "route",
        ) { r ->
            when {
                r == null -> ListScreen(vm, onOpen = { push(EDIT + it) }, onLanguage = onLanguage, modifier = Modifier.background(MaterialTheme.colorScheme.background))
                r == NEW -> EditorScreen(vm, todoId = null, onClose = ::pop, onOpenTodo = { push(EDIT + it) })
                else -> EditorScreen(vm, todoId = r.removePrefix(EDIT), onClose = ::pop, onOpenTodo = { push(EDIT + it) })
            }
        }
    }
}

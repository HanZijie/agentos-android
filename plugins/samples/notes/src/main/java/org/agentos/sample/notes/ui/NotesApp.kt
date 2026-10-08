package org.agentos.sample.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import org.agentos.sample.notes.R
import org.agentos.sample.notes.ui.editor.EditorScreen
import org.agentos.sample.notes.ui.home.HomeScreen
import org.agentos.sample.notes.ui.schedule.SchedulePanel
import org.agentos.sample.notes.ui.search.SearchScreen

/** 根：导航栈（首页 → 搜索 / 编辑页）+ 全局提示条。页面之间用横向滑动 + 淡入淡出过渡。 */
@Composable
fun NotesApp(vm: NotesViewModel) {
    val resources = LocalResources.current
    val stack by vm.stack.collectAsState()
    val home by vm.home.collectAsState()
    val search by vm.search.collectAsState()
    val grid by vm.gridMode.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // 一次性事件 → 提示条（撤销）
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            when (event) {
                is UiEvent.Trashed -> {
                    val r = snackbar.showSnackbar(resources.getString(R.string.snack_trashed), resources.getString(R.string.action_undo), duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undo(event.id)
                }
                is UiEvent.Archived -> {
                    val r = snackbar.showSnackbar(resources.getString(R.string.snack_archived), resources.getString(R.string.action_undo), duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undo(event.id)
                }
                is UiEvent.Unarchived -> snackbar.showSnackbar(resources.getString(R.string.snack_unarchived), duration = SnackbarDuration.Short)
                UiEvent.Restored -> snackbar.showSnackbar(resources.getString(R.string.snack_restored), duration = SnackbarDuration.Short)
                UiEvent.Deleted -> snackbar.showSnackbar(resources.getString(R.string.snack_deleted), duration = SnackbarDuration.Short)
                is UiEvent.TrashEmptied -> snackbar.showSnackbar(resources.getString(R.string.snack_trash_emptied), duration = SnackbarDuration.Short)
                is UiEvent.Failed -> snackbar.showSnackbar(resources.getString(R.string.snack_error, event.message), duration = SnackbarDuration.Short)
            }
        }
    }

    // 首页以外的返回由各页面自己的 BackHandler 处理（编辑页要先检查冲突）；搜索页在这里
    BackHandler(enabled = stack.last() is Screen.Search) { vm.back() }

    AnimatedContent(
        targetState = stack.last(),
        modifier = Modifier.fillMaxSize(),
        contentKey = { screen ->
            when (screen) {
                Screen.Home -> "home"
                Screen.Search -> "search"
                is Screen.Editor -> "editor-${screen.token}"
            }
        },
        transitionSpec = {
            if (depth(targetState) > depth(initialState)) {
                (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 10 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(tween(260)) { -it / 10 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(160)))
            }
        },
        label = "screens",
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(vm, home, grid, snackbar)
            Screen.Search -> SearchScreen(vm, search)
            is Screen.Editor -> {
                // 取打开这一屏时的会话：退出动画期间 vm.editor 已经清空，但这一屏还要画完
                val session = remember(screen.token) { vm.editor.value }
                if (session != null) EditorScreen(vm, session, search.tags, snackbar)
            }
        }
    }

    // “让 AgentOS 安排”的底部面板：放在这一层，旋转屏幕、退出编辑页都不影响进行中的一轮
    SchedulePanel(vm.schedule)
}

private fun depth(screen: Screen): Int = when (screen) {
    Screen.Home -> 0
    Screen.Search -> 1
    is Screen.Editor -> 2
}

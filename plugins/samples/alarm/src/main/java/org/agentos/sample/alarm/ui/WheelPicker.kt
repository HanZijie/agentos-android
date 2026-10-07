package org.agentos.sample.alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.agentos.sample.alarm.ui.theme.TimeNumeralStyle

/**
 * 滚轮选择器：吸附滚动，中间一行是选中项，两侧渐隐缩小。
 * [cyclic] 为 true 时首尾相接（小时 / 分钟）；false 时有头有尾（上午 / 下午）。
 * 只在手指滚动时回调 [onSelectedChange]；初始位置取自 [selected]。
 */
@Composable
fun WheelPicker(
    count: Int,
    selected: Int,
    onSelectedChange: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    rows: Int = 5,
    itemHeight: Dp = 60.dp,
    width: Dp = 92.dp,
    cyclic: Boolean = true,
) {
    val repeats = if (cyclic) 400 else 1
    val total = count * repeats
    val startIndex = if (cyclic) (repeats / 2) * count + selected else selected
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = rememberSnapFlingBehavior(listState)
    val haptic = LocalHapticFeedback.current
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val onChange = rememberUpdatedState(onSelectedChange)
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState, count) {
        var last = -1
        snapshotFlow {
            val centered = listState.firstVisibleItemIndex +
                if (listState.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            centered.coerceIn(0, total - 1) % count
        }
            .distinctUntilChanged()
            .collect { value ->
                if (last != -1) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                last = value
                onChange.value(value)
            }
    }

    Box(modifier.width(width).height(itemHeight * rows), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemHeight * (rows / 2)),
        ) {
            items(total) { index ->
                val distance = remember(index) {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                        if (item == null) {
                            3f
                        } else {
                            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            abs(item.offset + item.size / 2f - viewportCenter) / item.size
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        // 点一下可见的数字就滚到它
                        .clickable(interactionSource = null, indication = null) {
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                        .graphicsLayer {
                            val d = distance.value.coerceIn(0f, 2.5f)
                            alpha = (1f - d * 0.38f).coerceIn(0.12f, 1f)
                            val s = 1f - d * 0.12f
                            scaleX = s
                            scaleY = s
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(index % count),
                        style = TimeNumeralStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

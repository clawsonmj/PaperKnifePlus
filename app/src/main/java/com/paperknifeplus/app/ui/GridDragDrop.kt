package com.paperknifeplus.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Long-press drag-to-reorder for a vertical lazy grid. The grid must not use
 * horizontal content padding (pad the grid itself instead), so pointer and item
 * coordinates line up.
 */
@Composable
fun rememberGridDragDropState(
    gridState: LazyGridState,
    onDragStart: () -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit
): GridDragDropState {
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val state = remember(gridState) {
        GridDragDropState(gridState, scope, { currentOnDragStart() }) { from, to -> currentOnMove(from, to) }
    }
    LaunchedEffect(state) {
        while (true) {
            val distance = state.scrollChannel.receive()
            gridState.scrollBy(distance)
        }
    }
    return state
}

class GridDragDropState internal constructor(
    private val state: LazyGridState,
    private val scope: CoroutineScope,
    private val onDragStart: () -> Unit,
    private val onMove: (Int, Int) -> Unit
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    internal val scrollChannel = Channel<Float>(Channel.CONFLATED)

    private var draggedDelta by mutableStateOf(Offset.Zero)
    private var initialOffset by mutableStateOf(Offset.Zero)

    internal val draggingItemOffset: Offset
        get() = draggingItemInfo?.let { initialOffset + draggedDelta - it.offset.toOffset() } ?: Offset.Zero

    private val draggingItemInfo: LazyGridItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    internal var previousIndex by mutableStateOf<Int?>(null)
        private set
    internal val previousOffset = Animatable(Offset.Zero, Offset.VectorConverter)

    private fun LazyGridItemInfo.contains(point: Offset): Boolean =
        point.x.toInt() in offset.x..(offset.x + size.width) &&
            point.y.toInt() in offset.y..(offset.y + size.height)

    internal fun onDragStart(pointer: Offset) {
        // Item offsets are relative to the content start, which sits below any top content padding.
        val point = pointer.copy(y = pointer.y + state.layoutInfo.viewportStartOffset)
        state.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(point) }?.also {
            draggingItemIndex = it.index
            initialOffset = it.offset.toOffset()
            onDragStart()
        }
    }

    internal fun onDragInterrupted() {
        if (draggingItemIndex != null) {
            previousIndex = draggingItemIndex
            val startOffset = draggingItemOffset
            scope.launch {
                previousOffset.snapTo(startOffset)
                previousOffset.animateTo(
                    Offset.Zero,
                    spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Offset.VisibilityThreshold)
                )
                previousIndex = null
            }
        }
        draggedDelta = Offset.Zero
        draggingItemIndex = null
        initialOffset = Offset.Zero
    }

    internal fun onDrag(amount: Offset) {
        draggedDelta += amount
        val dragging = draggingItemInfo ?: return
        val start = dragging.offset.toOffset() + draggingItemOffset
        val size = dragging.size.toSize()
        val end = start + Offset(size.width, size.height)
        val middle = start + Offset(size.width / 2f, size.height / 2f)

        val target = state.layoutInfo.visibleItemsInfo.find { it.index != dragging.index && it.contains(middle) }
        if (target != null) {
            val from = dragging.index
            val to = target.index
            // The grid pins its first visible item by key; counter that so the view doesn't jump.
            val pinned = when (state.firstVisibleItemIndex) {
                to -> from
                from -> to
                else -> null
            }
            draggingItemIndex = to
            if (pinned != null) {
                scope.launch {
                    state.scrollToItem(pinned, state.firstVisibleItemScrollOffset)
                    onMove(from, to)
                }
            } else {
                onMove(from, to)
            }
        } else {
            val overscroll = when {
                draggedDelta.y > 0 -> (end.y - state.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                draggedDelta.y < 0 -> (start.y - state.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrollChannel.trySend(overscroll)
        }
    }
}

fun Modifier.dragContainer(dragDropState: GridDragDropState): Modifier =
    pointerInput(dragDropState) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> dragDropState.onDragStart(offset) },
            onDrag = { change, amount ->
                change.consume()
                dragDropState.onDrag(amount)
            },
            onDragEnd = { dragDropState.onDragInterrupted() },
            onDragCancel = { dragDropState.onDragInterrupted() }
        )
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyGridItemScope.DraggableItem(
    dragDropState: GridDragDropState,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit
) {
    val dragging = index == dragDropState.draggingItemIndex
    val placement = when {
        dragging -> Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationX = dragDropState.draggingItemOffset.x
                translationY = dragDropState.draggingItemOffset.y
            }
        index == dragDropState.previousIndex -> Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationX = dragDropState.previousOffset.value.x
                translationY = dragDropState.previousOffset.value.y
            }
        else -> Modifier.animateItemPlacement()
    }
    Box(modifier = modifier.then(placement), propagateMinConstraints = true) {
        content(dragging)
    }
}

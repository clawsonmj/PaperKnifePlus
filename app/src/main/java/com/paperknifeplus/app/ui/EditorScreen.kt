package com.paperknifeplus.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.EditorEvent
import com.paperknifeplus.app.EditorViewModel
import com.paperknifeplus.app.PasswordRequest
import com.paperknifeplus.app.pdf.PageItem
import com.paperknifeplus.app.pdf.PdfWorkspace
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.min

private val PdfMime = arrayOf("application/pdf")
private val ImageMime = arrayOf("image/*")
private const val MaxRenderWidth = 2400
private const val MaxZoom = 4f

/** What to do once the user agrees to throw away unsaved edits. */
private sealed interface PendingAction {
    object Close : PendingAction
    data class Open(val uri: Uri) : PendingAction
}

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    incomingPdf: Uri?,
    onIncomingHandled: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val snackbar = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var density by rememberSaveable { mutableIntStateOf(0) }
    // Reading view shows the pages full width, one after another; otherwise the organizer grid.
    var reading by rememberSaveable { mutableStateOf(false) }
    val readerState = rememberLazyListState()

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addImages(uris)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.save(uri)
    }

    fun perform(action: PendingAction) {
        when (action) {
            PendingAction.Close -> vm.closeDocument()
            is PendingAction.Open -> vm.open(action.uri)
        }
    }

    fun guarded(action: PendingAction) {
        if (vm.dirty) pendingAction = action else perform(action)
    }

    LaunchedEffect(incomingPdf) {
        if (incomingPdf != null) {
            onIncomingHandled()
            // A PDF handed over by another app is most likely meant to be read first.
            if (!vm.dirty) reading = true
            guarded(PendingAction.Open(incomingPdf))
        }
    }

    LaunchedEffect(vm) {
        vm.events.receiveAsFlow().collect { event ->
            when (event) {
                is EditorEvent.ScrollTo -> launch { gridState.animateScrollToItem(event.index) }
                // Each snackbar gets its own job so a pending one never holds up the next event.
                is EditorEvent.Message -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.text)
                }
                is EditorEvent.Deleted -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val label = if (event.count == 1) "Deleted 1 page" else "Deleted ${event.count} pages"
                    val result = snackbar.showSnackbar(label, actionLabel = "UNDO", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
                }
                is EditorEvent.Saved -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(
                        "Saved ${event.name}", actionLabel = "OPEN", duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val view = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(event.uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(Intent.createChooser(view, null))
                        } catch (e: ActivityNotFoundException) {
                            snackbar.showSnackbar("No app can open PDFs")
                        }
                    }
                }
            }
        }
    }

    val hasDocument = vm.documentName != null
    fun showReader(show: Boolean) {
        if (show == reading) return
        // Carry the position across so the same page stays in view.
        val target = if (show) {
            vm.pages.indexOfFirst { it.id in vm.selection }.takeIf { it >= 0 } ?: gridState.firstVisibleItemIndex
        } else {
            readerState.firstVisibleItemIndex
        }
        if (show) vm.clearSelection()
        reading = show
        scope.launch {
            if (show) readerState.scrollToItem(target) else gridState.scrollToItem(target)
        }
    }

    BackHandler(enabled = hasDocument) {
        when {
            reading -> showReader(false)
            vm.selection.isNotEmpty() -> vm.clearSelection()
            else -> guarded(PendingAction.Close)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (hasDocument) {
                EditorTopBar(
                    vm = vm,
                    accent = accent,
                    reading = reading,
                    onClose = { guarded(PendingAction.Close) },
                    onToggleReading = { showReader(!reading) },
                    onToggleDensity = { density = (density + 1) % 3 },
                    onSave = {
                        val base = (vm.documentName ?: "Document").removeSuffix(".pdf").removeSuffix(".PDF")
                        saver.launch(if (vm.dirty && base != "Untitled") "$base-edited.pdf" else "$base.pdf")
                    }
                )
            }
        },
        bottomBar = {
            if (hasDocument && !reading) {
                EditorBottomBar(vm = vm, accent = accent, onAddImage = { imagePicker.launch(ImageMime) })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!hasDocument) {
                HomeContent(
                    accent = accent,
                    onOpen = { pdfPicker.launch(PdfMime) },
                    onStartFromImage = { imagePicker.launch(ImageMime) }
                )
            } else if (vm.pages.isEmpty()) {
                EmptyDocument()
            } else if (reading) {
                Reader(vm = vm, listState = readerState)
            } else {
                val widthDp = LocalConfiguration.current.screenWidthDp
                val columns = when (density) {
                    1 -> widthDp / 120
                    2 -> widthDp / 400
                    else -> widthDp / 175
                }.coerceAtLeast(1)

                val dragState = rememberGridDragDropState(
                    gridState,
                    onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onMove = vm::move
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).dragContainer(dragState),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(vm.pages, key = { _, page -> page.id }) { index, page ->
                        DraggableItem(dragState, index) { dragging ->
                            PageTile(
                                workspace = vm.workspace,
                                page = page,
                                number = index + 1,
                                selected = page.id in vm.selection,
                                dragging = dragging,
                                accent = accent,
                                // A long-press that never moves still ends in a "click"; ignore it.
                                onClick = { if (dragState.draggingItemIndex == null) vm.toggle(page.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    vm.busy?.let { BusyOverlay(it, accent) }

    vm.passwordRequest?.let { request ->
        PasswordDialog(
            request = request,
            accent = accent,
            enabled = vm.busy == null,
            onSubmit = { vm.open(request.uri, it) },
            onDismiss = vm::cancelPassword
        )
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Discard changes?", fontWeight = FontWeight.Black) },
            text = { Text("Your edits to ${vm.documentName ?: "this document"} haven't been saved.", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { pendingAction = null; perform(action) }) {
                    Text("DISCARD", color = accent, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("KEEP EDITING", color = Color.Gray, fontWeight = FontWeight.Black)
                }
            }
        )
    }
}

@Composable
private fun EditorTopBar(
    vm: EditorViewModel,
    accent: Color,
    reading: Boolean,
    onClose: () -> Unit,
    onToggleReading: () -> Unit,
    onToggleDensity: () -> Unit,
    onSave: () -> Unit
) {
    val selecting = vm.selection.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (selecting) vm.clearSelection() else onClose() }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = if (selecting) "Clear selection" else "Close document",
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (selecting) "${vm.selection.size} selected" else vm.documentName.orEmpty(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val count = vm.pages.size
                Text(
                    text = (if (count == 1) "1 PAGE" else "$count PAGES") + if (vm.dirty) " • EDITED" else "",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = accent,
                    letterSpacing = 1.sp
                )
            }
            if (selecting) {
                TextButton(onClick = vm::selectAll, enabled = vm.selection.size < vm.pages.size) {
                    Text("SELECT ALL", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            } else {
                if (!reading) {
                    IconButton(onClick = onToggleDensity) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Change thumbnail size", modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = onToggleReading) {
                    if (reading) {
                        Icon(Icons.Filled.GridView, contentDescription = "Organize pages", modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Read", modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onSave,
                enabled = vm.pages.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                Text("SAVE", fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            }
        }
    }
}

@Composable
private fun EditorBottomBar(vm: EditorViewModel, accent: Color, onAddImage: () -> Unit) {
    val selecting = vm.selection.isNotEmpty()
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Text(
            text = if (selecting) "ACTIONS APPLY TO THE SELECTED PAGES" else "TAP TO SELECT • HOLD AND DRAG TO REORDER",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BarAction(Icons.Filled.AddPhotoAlternate, "ADD IMAGE", true, accent, onAddImage)
                BarAction(Icons.Filled.ArrowUpward, "MOVE UP", selecting && vm.canShiftSelection(-1), accent) {
                    vm.shiftSelection(-1)
                }
                BarAction(Icons.Filled.ArrowDownward, "MOVE DOWN", selecting && vm.canShiftSelection(1), accent) {
                    vm.shiftSelection(1)
                }
                BarAction(Icons.Filled.RotateLeft, "ROTATE L", selecting, accent) { vm.rotateSelection(-90) }
                BarAction(Icons.Filled.RotateRight, "ROTATE R", selecting, accent) { vm.rotateSelection(90) }
                BarAction(Icons.Filled.DeleteOutline, "DELETE", selecting, accent, vm::deleteSelection)
            }
        }
    }
}

@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val tint = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else tint
        )
    }
}

@Composable
private fun PageTile(
    workspace: PdfWorkspace,
    page: PageItem,
    number: Int,
    selected: Boolean,
    dragging: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val isDark = MaterialTheme.colorScheme.background == Color.Black
    val tileColor = when {
        selected -> accent.copy(alpha = if (isDark) 0.22f else 0.12f)
        isDark -> Color(0xFF1C1C1E)
        else -> Color(0xFFEFEFF1)
    }
    Box(
        modifier = Modifier
            .aspectRatio(0.76f)
            .shadow(if (dragging) 12.dp else 0.dp, shape)
            .clip(shape)
            .background(tileColor)
            .border(
                BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
                ),
                shape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        PageThumbnail(
            workspace = workspace,
            page = page,
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 34.dp)
        )
        Text(
            text = number.toString(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .clip(CircleShape)
                .background(if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        )
        if (selected) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Draws the page upright-fitted inside the available box, animating any rotation the user applied. */
@Composable
private fun PageThumbnail(
    workspace: PdfWorkspace,
    page: PageItem,
    modifier: Modifier = Modifier,
    renderScale: Int = 1,
    onLoaded: (ImageBitmap) -> Unit = {}
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxWidth = constraints.maxWidth
        val boxHeight = constraints.maxHeight
        // Round the render width up so small layout changes reuse the cached bitmap.
        val renderWidth = (((boxWidth + 95) / 96 * 96).coerceAtLeast(96) * renderScale).coerceAtMost(MaxRenderWidth)

        var bitmap by remember(page.source) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(page.source, renderWidth) {
            workspace.thumbnail(page.source, renderWidth)?.let {
                val loaded = it.asImageBitmap()
                bitmap = loaded
                onLoaded(loaded)
            }
        }

        val angle by animateFloatAsState(page.rotation.toFloat(), label = "rotation")
        val image = bitmap
        if (image != null) {
            val sideways = page.rotation % 180 != 0
            val targetScale = if (sideways) {
                min(boxWidth.toFloat() / image.height, boxHeight.toFloat() / image.width)
            } else {
                min(boxWidth.toFloat() / image.width, boxHeight.toFloat() / image.height)
            }
            val scale by animateFloatAsState(targetScale, label = "scale")
            with(LocalDensity.current) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .requiredSize((image.width * scale).toDp(), (image.height * scale).toDp())
                        .rotate(angle)
                        .shadow(2.dp)
                        .background(Color.White)
                )
            }
        }
    }
}

/**
 * Continuous reading view of the document as it currently stands, edits included.
 * Pinch or double-tap to zoom; one finger scrolls, and pans sideways when zoomed.
 */
@Composable
private fun Reader(vm: EditorViewModel, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background == Color.Black
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth.toFloat()

        // Zooms to [target] while keeping the content under [anchor] where it is.
        fun zoomTo(target: Float, anchor: Offset) {
            val next = target.coerceIn(1f, MaxZoom)
            if (next == zoom) return
            val scrollBy = anchor.y / zoom - anchor.y / next
            offsetX = (anchor.x - (anchor.x - offsetX) * next / zoom).coerceIn(width - width * next, 0f)
            zoom = next
            scope.launch { listState.scrollBy(scrollBy) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(width) {
                    detectTapGestures(onDoubleTap = { zoomTo(if (zoom > 1f) 1f else 2.5f, it) })
                }
                .pointerInput(width) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            // Seen before the list, so a pinch never turns into a scroll.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                val pan = event.calculatePan()
                                zoomTo(zoom * event.calculateZoom(), event.calculateCentroid(useCurrent = false))
                                offsetX = (offsetX + pan.x).coerceIn(width - width * zoom, 0f)
                                scope.launch { listState.scrollBy(-pan.y / zoom) }
                                event.changes.forEach { it.consume() }
                            } else if (pressed == 1 && zoom > 1f) {
                                // Left unconsumed: the list still handles the vertical part.
                                offsetX = (offsetX + event.changes.first().positionChange().x)
                                    .coerceIn(width - width * zoom, 0f)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offsetX
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.pages, key = { it.id }) { page ->
                // Width over height of the upright page; a portrait sheet until the real one is known.
                var ratio by remember(page.source) { mutableFloatStateOf(0.707f) }
                val sideways = page.rotation % 180 != 0
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (sideways) 1f / ratio else ratio)
                        .background(if (isDark) Color(0xFF1C1C1E) else Color(0xFFEFEFF1))
                ) {
                    PageThumbnail(
                        workspace = vm.workspace,
                        page = page,
                        modifier = Modifier.fillMaxSize(),
                        renderScale = if (zoom > 1.4f) 2 else 1,
                        onLoaded = { ratio = it.width.toFloat() / it.height }
                    )
                }
            }
        }

        val current by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2 / zoom
                val item = info.visibleItemsInfo.lastOrNull { it.offset <= middle } ?: info.visibleItemsInfo.firstOrNull()
                (item?.index ?: 0) + 1
            }
        }
        Text(
            text = "$current / ${vm.pages.size}",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 5.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
    }
}

@Composable
private fun HomeContent(accent: Color, onOpen: () -> Unit, onStartFromImage: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background == Color.Black
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(Modifier.size(28.dp), partColor = if (isDark) Color.White else Color.Black)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("PaperKnife Lite", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                Text("PAGE ORGANIZER", fontSize = 10.sp, fontWeight = FontWeight.Black, color = accent, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)), RoundedCornerShape(32.dp))
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp).alpha(0.25f), tint = accent)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Tap to open a PDF",
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = if (isDark) Color.LightGray else Color.DarkGray
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "REORDER • ROTATE • DELETE • ADD IMAGES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onStartFromImage,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "START FROM IMAGES",
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun EmptyDocument() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No pages left", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "ADD AN IMAGE OR UNDO THE DELETE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun BusyOverlay(message: String, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
            // Swallow touches while work is in progress.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = accent)
            Spacer(Modifier.height(24.dp))
            AnimatedContent(message, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "busy") {
                Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    request: PasswordRequest,
    accent: Color,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember(request.uri) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password required", fontWeight = FontWeight.Black) },
        text = {
            Column {
                Text(
                    "${request.name} is protected. The copy you save from here won't be.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = request.wrongPassword,
                    supportingText = if (request.wrongPassword) {
                        { Text("That password didn't work") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty() && enabled) onSubmit(password) }),
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = enabled && password.isNotEmpty()) {
                Text("UNLOCK", color = accent, fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.Gray, fontWeight = FontWeight.Black) }
        }
    )
}

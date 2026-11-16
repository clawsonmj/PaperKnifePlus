package com.paperknifeplus.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperknifeplus.app.pdf.OpenResult
import com.paperknifeplus.app.pdf.PageItem
import com.paperknifeplus.app.pdf.PageSource
import com.paperknifeplus.app.pdf.PdfWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

data class PasswordRequest(val uri: Uri, val name: String, val wrongPassword: Boolean)

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data class Deleted(val count: Int) : EditorEvent
    data class Saved(val uri: Uri, val name: String) : EditorEvent
    data class ScrollTo(val index: Int) : EditorEvent
}

/** Holds the open document and every edit made to it until it is saved. */
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val workspace = PdfWorkspace(app)

    val pages = mutableStateListOf<PageItem>()
    var selection by mutableStateOf(emptySet<Long>())
        private set

    /** Name of the open document, or null while nothing is open. */
    var documentName by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set

    /** Message for the blocking progress overlay, or null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
        private set

    val events = Channel<EditorEvent>(Channel.BUFFERED)

    private var nextId = 0L
    private var beforeDelete: List<PageItem>? = null

    private fun launchBusy(message: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = message
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.trySend(EditorEvent.Message(e.message ?: "Something went wrong"))
            } catch (e: OutOfMemoryError) {
                events.trySend(EditorEvent.Message("Not enough memory for this file"))
            } catch (e: LinkageError) {
                // Raised for PDF features whose support code is left out of this build.
                events.trySend(EditorEvent.Message("This kind of PDF isn't supported"))
            } finally {
                busy = null
            }
        }
    }

    fun open(uri: Uri, password: String? = null) = launchBusy("Opening…") {
        val name = workspace.displayName(uri)?.takeIf { it.isNotBlank() } ?: "Document.pdf"
        when (val result = workspace.open(uri, password)) {
            is OpenResult.Opened -> {
                passwordRequest = null
                beforeDelete = null
                pages.clear()
                pages.addAll(List(result.pageCount) { PageItem(nextId++, PageSource.Pdf(it)) })
                selection = emptySet()
                documentName = name
                dirty = false
            }
            OpenResult.NeedsPassword -> {
                passwordRequest = PasswordRequest(uri, name, wrongPassword = password != null)
            }
        }
    }

    fun cancelPassword() {
        passwordRequest = null
        workspace.discardPending()
    }

    fun closeDocument() = launchBusy("Closing…") {
        workspace.clear()
        pages.clear()
        selection = emptySet()
        documentName = null
        dirty = false
        beforeDelete = null
    }

    /** Adds one page per image after the last selected page, or at the end. */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchBusy(if (uris.size == 1) "Adding image…" else "Adding ${uris.size} images…") {
            if (documentName == null) workspace.clear()
            val added = mutableListOf<PageItem>()
            var failure: String? = null
            for (uri in uris) {
                try {
                    added += PageItem(nextId++, workspace.importImage(uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message
                }
            }
            if (added.isNotEmpty()) {
                val insertAt = pages.indexOfLast { it.id in selection }.let { if (it < 0) pages.size else it + 1 }
                pages.addAll(insertAt, added)
                if (documentName == null) documentName = "Untitled.pdf"
                dirty = true
                events.trySend(EditorEvent.ScrollTo(insertAt))
            }
            failure?.let { events.trySend(EditorEvent.Message(it)) }
        }
    }

    fun save(target: Uri) = launchBusy("Saving…") {
        workspace.save(pages.toList(), target)
        dirty = false
        events.trySend(EditorEvent.Saved(target, workspace.displayName(target) ?: "PDF"))
    }

    fun toggle(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun selectAll() {
        selection = pages.mapTo(HashSet()) { it.id }
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in pages.indices || to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
        dirty = true
    }

    /** Shifts every selected page one place earlier ([step] = -1) or later (+1), keeping their order. */
    fun shiftSelection(step: Int) {
        val order = if (step < 0) pages.indices else pages.indices.reversed()
        var moved = false
        for (i in order) {
            val j = i + step
            if (j in pages.indices && pages[i].id in selection && pages[j].id !in selection) {
                val page = pages[i]
                pages[i] = pages[j]
                pages[j] = page
                moved = true
            }
        }
        if (moved) dirty = true
    }

    fun canShiftSelection(step: Int): Boolean = pages.indices.any { i ->
        val j = i + step
        j in pages.indices && pages[i].id in selection && pages[j].id !in selection
    }

    fun rotateSelection(degrees: Int) {
        if (selection.isEmpty()) return
        for (i in pages.indices) {
            val page = pages[i]
            if (page.id in selection) pages[i] = page.copy(rotation = page.rotation + degrees)
        }
        dirty = true
    }

    fun deleteSelection() {
        val count = pages.count { it.id in selection }
        if (count == 0) return
        beforeDelete = pages.toList()
        pages.removeAll { it.id in selection }
        selection = emptySet()
        dirty = true
        events.trySend(EditorEvent.Deleted(count))
    }

    fun undoDelete() {
        val snapshot = beforeDelete ?: return
        beforeDelete = null
        // Keep edits made since the delete: only put the removed pages back.
        val current = pages.associateBy { it.id }
        val restored = snapshot.map { current[it.id] ?: it }
        val added = pages.filter { page -> snapshot.none { it.id == page.id } }
        pages.clear()
        pages.addAll(restored + added)
    }

    override fun onCleared() {
        workspace.close()
    }
}

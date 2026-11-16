package com.paperknifeplus.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/** Where a page in the working document comes from. */
sealed interface PageSource {
    /** A page of the PDF that was opened. */
    data class Pdf(val index: Int) : PageSource

    /**
     * An image added by the user, already copied into the app cache.
     *
     * [width]/[height] are the stored pixel dimensions (before [exifRotation]).
     * [jpegComponents] is 1 or 3 when the file is a JPEG that can be embedded
     * byte-for-byte, otherwise 0 (the image is decoded and re-encoded on save).
     */
    data class Image(
        val file: File,
        val width: Int,
        val height: Int,
        val exifRotation: Int,
        val jpegComponents: Int
    ) : PageSource
}

/**
 * One page in the editor. [rotation] is the extra clockwise rotation the user
 * applied, in degrees. It is a multiple of 90 and deliberately not normalized
 * so the UI can animate through it.
 */
data class PageItem(val id: Long, val source: PageSource, val rotation: Int = 0)

sealed interface OpenResult {
    data class Opened(val pageCount: Int) : OpenResult
    object NeedsPassword : OpenResult
}

/**
 * Owns the working copy of the open PDF plus any added images, renders
 * thumbnails and writes the edited document.
 *
 * Thumbnails use the platform [PdfRenderer]; PDFBox is only touched to
 * decrypt a protected file and to save.
 */
class PdfWorkspace(context: Context) {
    private val app = context.applicationContext
    private val workDir = File(app.cacheDir, "work").apply {
        deleteRecursively()
        mkdirs()
    }
    private val sourceFile = File(workDir, "source.pdf")
    private val pendingFile = File(workDir, "pending.pdf")

    private val renderMutex = Mutex()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pendingUri: Uri? = null
    private var imageCounter = 0

    private val thumbnails = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Copies [uri] into the workspace and makes it the current document.
     * The previous document stays intact unless this returns [OpenResult.Opened].
     */
    suspend fun open(uri: Uri, password: String?): OpenResult = withContext(Dispatchers.IO) {
        if (pendingUri != uri || !pendingFile.exists()) {
            pendingUri = null
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't read this file")
            input.use { source -> pendingFile.outputStream().use { source.copyTo(it) } }
            pendingUri = uri
        }

        if (password != null) {
            val decrypted = File(workDir, "decrypted.pdf")
            try {
                PDDocument.load(pendingFile, password, memorySetting()).use { doc ->
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(decrypted)
                }
            } catch (e: InvalidPasswordException) {
                decrypted.delete()
                return@withContext OpenResult.NeedsPassword
            }
            pendingFile.delete()
            decrypted.renameTo(pendingFile)
        }

        val pfd = ParcelFileDescriptor.open(pendingFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val newRenderer = try {
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            pfd.close()
            return@withContext OpenResult.NeedsPassword
        } catch (e: Exception) {
            pfd.close()
            discardPending()
            throw IOException("This file isn't a PDF that can be opened")
        }

        // Renaming keeps the already-open descriptor valid.
        renderMutex.withLock {
            closeRenderer()
            workDir.listFiles()?.forEach { if (it != pendingFile) it.delete() }
            pendingFile.renameTo(sourceFile)
            pendingUri = null
            descriptor = pfd
            renderer = newRenderer
            thumbnails.evictAll()
        }
        OpenResult.Opened(newRenderer.pageCount)
    }

    fun discardPending() {
        pendingUri = null
        pendingFile.delete()
    }

    /** Drops the current document so a new one can be started from images. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            closeRenderer()
            workDir.listFiles()?.forEach { it.delete() }
            pendingUri = null
            thumbnails.evictAll()
        }
    }

    private fun closeRenderer() {
        try { renderer?.close() } catch (_: Exception) {}
        try { descriptor?.close() } catch (_: Exception) {}
        renderer = null
        descriptor = null
    }

    /** Copies an image into the workspace and reads what is needed to place it on a page. */
    suspend fun importImage(uri: Uri): PageSource.Image = withContext(Dispatchers.IO) {
        val file = File(workDir, "image_${imageCounter++}")
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't read this image")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            throw IOException("${displayName(uri) ?: "That file"} isn't a supported image")
        }

        var rotation = 0
        var components = 0
        if (bounds.outMimeType == "image/jpeg") {
            rotation = try {
                when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                    else -> 0
                }
            } catch (e: Exception) {
                0
            }
            val header = try { FileInputStream(file).use { readJpegHeader(it) } } catch (e: Exception) { null }
            if (header != null && header.width == bounds.outWidth && header.height == bounds.outHeight &&
                (header.components == 1 || header.components == 3)
            ) {
                components = header.components
            }
        }
        PageSource.Image(file, bounds.outWidth, bounds.outHeight, rotation, components)
    }

    /** Renders an upright thumbnail about [widthPx] wide, or null if the page can't be drawn. */
    suspend fun thumbnail(source: PageSource, widthPx: Int): Bitmap? {
        val key = when (source) {
            is PageSource.Pdf -> "p${source.index}@$widthPx"
            is PageSource.Image -> "${source.file.name}@$widthPx"
        }
        thumbnails.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                when (source) {
                    is PageSource.Pdf -> renderPdfPage(source.index, widthPx)
                    is PageSource.Image -> decodeImage(source, widthPx, rotate = true)
                }
            } catch (e: OutOfMemoryError) {
                thumbnails.evictAll()
                null
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }
        if (bitmap != null) thumbnails.put(key, bitmap)
        return bitmap
    }

    private suspend fun renderPdfPage(index: Int, widthPx: Int): Bitmap? = renderMutex.withLock {
        val r = renderer ?: return null
        if (index >= r.pageCount) return null
        r.openPage(index).use { page ->
            val width = widthPx.coerceAtLeast(1)
            val height = (width.toLong() * page.height / page.width.coerceAtLeast(1))
                .coerceIn(1L, width * 4L).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    private fun decodeImage(source: PageSource.Image, targetWidth: Int, rotate: Boolean): Bitmap? {
        val sideways = rotate && source.exifRotation % 180 != 0
        val storedTarget = if (sideways) targetWidth * source.height / source.width.coerceAtLeast(1) else targetWidth
        var sample = 1
        while (source.width / (sample * 2) >= storedTarget) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(source.file.path, options) ?: return null
        if (!rotate || source.exifRotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(source.exifRotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /**
     * Writes the pages in [items] order to [target].
     *
     * The opened PDF is edited in place (its page tree is rebuilt) so document
     * metadata survives; pages that were removed simply drop out of the tree.
     * The result is written to a temp file first so a failed save can never
     * leave a half-written file at [target].
     */
    suspend fun save(items: List<PageItem>, target: Uri) = withContext(Dispatchers.IO) {
        require(items.isNotEmpty()) { "There are no pages to save" }
        val output = File(workDir, "output.pdf")
        try {
            val doc = if (sourceFile.exists()) PDDocument.load(sourceFile, "", memorySetting()) else PDDocument()
            doc.use {
                doc.isAllSecurityToBeRemoved = true
                val originals = doc.pages.toList()
                val reference = originals.firstOrNull()?.let { first ->
                    val box = first.cropBox
                    if (first.rotation % 180 != 0) PDRectangle(box.height, box.width) else PDRectangle(box.width, box.height)
                } ?: PDRectangle.A4

                val pages = items.map { item ->
                    when (val source = item.source) {
                        is PageSource.Pdf -> originals[source.index].also { page ->
                            // Pin down anything inherited from intermediate tree nodes,
                            // because the page is about to be re-parented.
                            val mediaBox = page.mediaBox
                            val cropBox = page.cropBox
                            val resources = page.resources
                            val rotation = page.rotation
                            page.mediaBox = mediaBox
                            page.cropBox = cropBox
                            if (resources != null) page.resources = resources
                            page.rotation = normalize(rotation + item.rotation)
                        }
                        is PageSource.Image -> imagePage(doc, source, reference).also { page ->
                            page.rotation = normalize(source.exifRotation + item.rotation)
                        }
                    }
                }

                val root = doc.pages.cosObject
                val kids = COSArray()
                pages.forEach { page ->
                    page.cosObject.setItem(COSName.PARENT, root)
                    kids.add(page.cosObject)
                }
                root.setItem(COSName.KIDS, kids)
                root.setInt(COSName.COUNT, kids.size())

                doc.save(output)
            }

            val resolver = app.contentResolver
            val stream = try {
                resolver.openOutputStream(target, "wt")
            } catch (e: Exception) {
                resolver.openOutputStream(target, "w")
            } ?: throw IOException("Can't write to the chosen location")
            stream.use { out -> FileInputStream(output).use { it.copyTo(out) } }
        } finally {
            output.delete()
        }
    }

    /** Builds a page the size of the document's pages with the image fitted and centered. */
    private fun imagePage(doc: PDDocument, source: PageSource.Image, reference: PDRectangle): PDPage {
        val shortSide = min(reference.width, reference.height)
        val longSide = max(reference.width, reference.height)
        val sideways = source.exifRotation % 180 != 0
        val shownLandscape = if (sideways) source.height > source.width else source.width > source.height
        // The media box is in stored orientation; the page's rotation turns it upright.
        val boxLandscape = shownLandscape != sideways
        val boxWidth = if (boxLandscape) longSide else shortSide
        val boxHeight = if (boxLandscape) shortSide else longSide

        val image = if (source.jpegComponents != 0) {
            FileInputStream(source.file).use { stream ->
                PDImageXObject(
                    doc, stream, COSName.DCT_DECODE, source.width, source.height, 8,
                    if (source.jpegComponents == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
                )
            }
        } else {
            val bitmap = decodeImage(source, MAX_EMBED_SIDE.coerceAtMost(source.width), rotate = false)
                ?: throw IOException("An added image could not be read")
            try {
                if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap)
                else JPEGFactory.createFromImage(doc, bitmap, 0.9f)
            } finally {
                bitmap.recycle()
            }
        }

        val scale = min(boxWidth / source.width, boxHeight / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val page = PDPage(PDRectangle(boxWidth, boxHeight))
        PDPageContentStream(doc, page).use { content ->
            content.drawImage(image, (boxWidth - drawWidth) / 2, (boxHeight - drawHeight) / 2, drawWidth, drawHeight)
        }
        return page
    }

    private fun memorySetting() = MemoryUsageSetting.setupTempFileOnly().setTempDir(workDir)

    fun close() {
        closeRenderer()
        workDir.deleteRecursively()
    }

    private class JpegHeader(val width: Int, val height: Int, val components: Int)

    companion object {
        private const val MAX_EMBED_SIDE = 3000

        private fun normalize(degrees: Int) = ((degrees % 360) + 360) % 360

        /** Reads size and channel count from the first start-of-frame marker, or null if there is none. */
        private fun readJpegHeader(raw: InputStream): JpegHeader? {
            val input = raw.buffered()
            if (input.read() != 0xFF || input.read() != 0xD8) return null
            while (true) {
                var marker = input.read()
                while (marker != 0xFF) {
                    if (marker < 0) return null
                    marker = input.read()
                }
                while (marker == 0xFF) marker = input.read()
                if (marker < 0) return null
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue
                if (marker == 0xD9 || marker == 0xDA) return null
                val length = (input.read() shl 8) or input.read()
                if (length < 2) return null
                val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isFrame) {
                    input.read() // sample precision
                    val height = (input.read() shl 8) or input.read()
                    val width = (input.read() shl 8) or input.read()
                    val components = input.read()
                    // Only baseline and progressive frames are safe to embed as-is.
                    return if (components < 0 || marker > 0xC2) null else JpegHeader(width, height, components)
                }
                var remaining = (length - 2).toLong()
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped <= 0) {
                        if (input.read() < 0) return null
                        remaining--
                    } else {
                        remaining -= skipped
                    }
                }
            }
        }
    }
}

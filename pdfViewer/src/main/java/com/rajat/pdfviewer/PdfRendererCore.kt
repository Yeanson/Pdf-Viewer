    package com.rajat.pdfviewer

    import android.annotation.SuppressLint
    import android.annotation.TargetApi
    import android.content.Context
    import android.graphics.Bitmap
    import android.graphics.Bitmap.CompressFormat
    import android.graphics.BitmapFactory
    import android.graphics.Color
    import android.graphics.pdf.PdfRenderer
    import android.os.Build
    import android.os.ParcelFileDescriptor
    import android.util.Log
    import android.util.LruCache
    import android.util.Size
    import com.rajat.pdfviewer.util.CommonUtils
    import com.rajat.pdfviewer.util.CommonUtils.Companion.calculateDynamicPrefetchCount
    import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
    import com.tom_roush.pdfbox.pdmodel.PDDocument
    import com.tom_roush.pdfbox.rendering.ImageType
    import com.tom_roush.pdfbox.rendering.PDFRenderer
    import kotlinx.coroutines.*
    import java.io.File
    import java.io.FileOutputStream
    import java.nio.file.Files
    import java.nio.file.Paths


    /**
     * Created by Rajat on 11,July,2020
     */

    @SuppressLint("ObsoleteSdkInt")
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    internal class PdfRendererCore(
        private val context: Context,
        private val renderType: RenderType,
        pdfFile: File
    ) {
        companion object {
            private const val CACHE_PATH = "___pdf___cache___"
        }

        //pdf-box
        private lateinit var pdfboxDocument: PDDocument
        private lateinit var pdfboxRenderer: PDFRenderer
        private lateinit var tempBitmap: Bitmap

        private var pdfRenderer: PdfRenderer? = null
        private val memoryCache: LruCache<Int, Bitmap>

        init {
            PDFBoxResourceLoader.init(context)
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt() // in KB
            val cacheSize = maxMemory / 8 // Use 1/8th of available memory for cache
            memoryCache = object : LruCache<Int, Bitmap>(cacheSize) {
                override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024 // size in KB
            }
            val safeFile = File(sanitizeFilePath(pdfFile.path))
            // Proceed with safeFile
            openPdfFile(safeFile)
            initCache()
        }

        private fun sanitizeFilePath(filePath: String): String {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val path = Paths.get(filePath)
                    if (Files.exists(path)) {
                        filePath
                    } else {
                        "" // Return a default safe path or handle the error
                    }
                }else{
                    filePath
                }
            } catch (e: Exception) {
                "" // Handle the exception and return a safe default path
            }
        }

        private fun initCache() {
            val cacheDir = File(context.cacheDir, CACHE_PATH)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            cacheDir.mkdirs()
        }
        private fun getBitmapFromCache(pageNo: Int): Bitmap? {
            return memoryCache.get(pageNo) ?: decodeBitmapFromDiskCache(pageNo)
        }
        private fun decodeBitmapFromDiskCache(pageNo: Int): Bitmap? {
            val loadPath = File(File(context.cacheDir, CACHE_PATH), pageNo.toString())
            if (!loadPath.exists()) return null

            return BitmapFactory.decodeFile(loadPath.absolutePath).also {
                if (it != null) {
                    addBitmapToMemoryCache(pageNo, it)
                }
            }
        }
        private fun addBitmapToMemoryCache(key: Int, bitmap: Bitmap) {
            if (memoryCache.get(key) == null) {
                memoryCache.put(key, bitmap)
            }
        }
        private fun writeBitmapToCache(pageNo: Int, bitmap: Bitmap, shouldCache: Boolean = true) {
            if (!shouldCache) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val savePath = File(File(context.cacheDir, CACHE_PATH), pageNo.toString())
                    FileOutputStream(savePath).use { fos ->
                        bitmap.compress(CompressFormat.JPEG, 100, fos) // Compress as JPEG
                    }
                } catch (e: Exception) {
                    Log.e("PdfRendererCore", "Error writing bitmap to cache: ${e.message}")
                }
            }
        }
        fun pageExistInCache(pageNo: Int): Boolean {
            val loadPath = File(File(context.cacheDir, CACHE_PATH), pageNo.toString())
            return loadPath.exists()
        }

        fun prefetchPages(currentPage: Int, width: Int, height: Int) {
                val dynamicPrefetchCount = calculateDynamicPrefetchCount(context, pdfRenderer!!)
                val prefetchRange = (currentPage - dynamicPrefetchCount)..(currentPage + dynamicPrefetchCount)
                prefetchRange.forEach { pageNo ->
                    if (pageNo in 0 until getPageCount() && !pageExistInCache(pageNo)) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val bitmap = CommonUtils.Companion.BitmapPool.getBitmap(width, height)
                            renderPage(pageNo, bitmap) { success, _, renderedBitmap ->
                                if (success) {
                                    writeBitmapToCache(pageNo, renderedBitmap ?: bitmap, shouldCache = true)
                                } else {
                                    CommonUtils.Companion.BitmapPool.recycleBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
        }
        private fun openPdfFile(pdfFile: File) {
            val fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor)

            if (renderType == RenderType.PDFBOX) {
                pdfboxDocument = PDDocument.load(pdfFile)
                pdfboxRenderer = PDFRenderer(pdfboxDocument)
            }
        }

        fun getPageCount(): Int = pdfRenderer?.pageCount ?: 0
        fun renderPage(pageNo: Int, bitmap: Bitmap, onBitmapReady: ((success: Boolean, pageNo: Int, bitmap: Bitmap?) -> Unit)? = null) {
            if (pageNo >= getPageCount()) {
                onBitmapReady?.invoke(false, pageNo, null)
                return
            }
            val cachedBitmap = getBitmapFromCache(pageNo)
            if (cachedBitmap != null) {
                CoroutineScope(Dispatchers.Main).launch { onBitmapReady?.invoke(true, pageNo, cachedBitmap) }
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                synchronized(this@PdfRendererCore) {
                    pdfRenderer?.openPage(pageNo)?.use { pdfPage ->
                        try {
                            if (renderType == RenderType.PDFBOX) {
                                tempBitmap = pdfboxRenderer.renderImage(pageNo, 2F, ImageType.RGB)
                                CommonUtils.Companion.BitmapPool.copyBitmap(tempBitmap, bitmap)
                            }else {
                                bitmap.eraseColor(Color.WHITE) // Clear the bitmap with white color
                                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                            addBitmapToMemoryCache(pageNo, bitmap)
                            CoroutineScope(Dispatchers.IO).launch { writeBitmapToCache(pageNo, bitmap) }
                            CoroutineScope(Dispatchers.Main).launch { onBitmapReady?.invoke(true, pageNo, bitmap) }
                        } catch (e: Exception) {
                            CoroutineScope(Dispatchers.Main).launch { onBitmapReady?.invoke(false, pageNo, null) }
                        }
                    }
                }
            }
        }

        fun getPageDimensions(pageNo: Int): Size {
            synchronized(this) {
                var pdfPage: PdfRenderer.Page? = null
                try {
                    pdfPage = pdfRenderer!!.openPage(pageNo)
                    return Size(pdfPage.width, pdfPage.height)
                } finally {
                    pdfPage?.close()
                }
            }
        }
        fun closePdfRender() {
            pdfRenderer?.close()
            val cacheDir = File(context.cacheDir, CACHE_PATH)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        }
    }
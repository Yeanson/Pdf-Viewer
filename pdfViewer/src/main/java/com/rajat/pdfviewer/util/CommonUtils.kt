package com.rajat.pdfviewer.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import java.util.*

class CommonUtils {
    companion object {
        fun getAvailableMemory(context: Context): Long {
            val memoryInfo = ActivityManager.MemoryInfo()
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getMemoryInfo(memoryInfo)
            return memoryInfo.availMem
        }
        fun estimatePdfComplexity(pdfRenderer: PdfRenderer): Float {
            return pdfRenderer.pageCount.toFloat()
        }
        fun calculateDynamicPrefetchCount(context: Context, pdfRenderer: PdfRenderer): Int {
            val availableMemory = getAvailableMemory(context)
            val pdfComplexity = estimatePdfComplexity(pdfRenderer)
            return when {
                availableMemory > 1024 * 1024 * 1024 && pdfComplexity < 100 -> 10 // 1GB of available memory and less than 100 pages
                availableMemory > 512 * 1024 * 1024 && pdfComplexity < 200 -> 5  // 512MB of available memory and less than 200 pages
                else -> 3 // Default fallback
            }
        }
        object BitmapPool {
            private val pool = LinkedList<Bitmap>()
            fun getBitmap(width: Int, height: Int): Bitmap {
                synchronized(pool) {
                    val iterator = pool.iterator()
                    while (iterator.hasNext()) {
                        val bitmap = iterator.next()
                        if (!bitmap.isRecycled && bitmap.width == width && bitmap.height == height) {
                            iterator.remove()
                            return bitmap
                        }
                    }
                }
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            fun recycleBitmap(bitmap: Bitmap) {
                if (!bitmap.isRecycled) {
                    synchronized(pool) {
                        // Optional: limit the pool size to avoid excessive memory use
                        if (pool.size < 10) {
                            pool.add(bitmap)
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            }

            fun copyBitmap(sourceBitmap: Bitmap, targetBitmap: Bitmap) {
                // 创建一个 Canvas 对象，将目标 Bitmap 的内容绘制到该 Canvas 上
                val canvas = Canvas(targetBitmap)
                // 创建一个 Matrix 对象，用于处理缩放和平移操作
                val matrix = Matrix()
                // 计算缩放比例
                val scaleX = targetBitmap.width.toFloat() / sourceBitmap.width
                val scaleY = targetBitmap.height.toFloat() / sourceBitmap.height
                // 设置 Matrix 进行缩放操作
                matrix.postScale(scaleX, scaleY)

                // 使用 Matrix 将源 Bitmap 的内容绘制到目标 Bitmap 上
                canvas.drawBitmap(sourceBitmap, matrix, Paint())
            }
        }

    }
}
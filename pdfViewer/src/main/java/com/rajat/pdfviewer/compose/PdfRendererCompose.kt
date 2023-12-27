package com.rajat.pdfviewer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rajat.pdfviewer.HeaderData
import com.rajat.pdfviewer.PdfRendererView
import com.rajat.pdfviewer.RenderType
import java.io.File

@Composable
fun PdfRendererViewCompose(
    url: String? = null,
    file: File? = null,
    renderType: RenderType? = null,
    headers: HeaderData = HeaderData(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    statusCallBack: PdfRendererView.StatusCallBack? = null
) {
    val lifecycleScope = lifecycleOwner.lifecycleScope

    AndroidView(
        factory = { context ->
            PdfRendererView(context).apply {
                if (statusCallBack != null) {
                    statusListener = statusCallBack
                }
                if (file != null) {
                    if (renderType != null) {
                        initWithFile(renderType,file)
                    }else {
                        initWithFile(file)
                    }
                } else if (url != null) {
                    if (renderType != null) {
                        initWithUrl(url, renderType, headers, lifecycleScope, lifecycleOwner.lifecycle)
                    }else {
                        initWithUrl(url, headers, lifecycleScope, lifecycleOwner.lifecycle)
                    }
                }
            }
        },
        update = { view ->
            // Update logic if needed
        }
    )
}
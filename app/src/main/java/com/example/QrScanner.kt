package com.example

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

@Composable
fun QrScannerView(
    modifier: Modifier = Modifier,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var hasScanned by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val preview = Preview.Builder().build()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val reader = MultiFormatReader().apply {
                    val hints = mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)
                    )
                    setHints(hints)
                }

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    if (hasScanned) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    try {
                        val plane = imageProxy.planes[0]
                        val buffer = plane.buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)

                        val source = PlanarYUVLuminanceSource(
                            data,
                            imageProxy.width,
                            imageProxy.height,
                            0, 0,
                            imageProxy.width,
                            imageProxy.height,
                            false
                        )

                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                        val result = reader.decode(binaryBitmap)
                        val text = result.text ?: ""
                        if (text.isNotEmpty() && !hasScanned) {
                            hasScanned = true
                            previewView.post {
                                onQrScanned(text)
                            }
                        }
                    } catch (e: Exception) {
                        // QR not found in this frame, continue scanning
                    } finally {
                        imageProxy.close()
                    }
                }

                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("QrScannerView", "Camera binding failed", e)
                }

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Custom modern viewfinder mask over preview
        ScannerOverlay()
    }
}

@Composable
fun ScannerOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val boxWidth = 260.dp.toPx()
        val boxHeight = 260.dp.toPx()

        val left = (width - boxWidth) / 2f
        val top = (height - boxHeight) / 2f

        val clipPath = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    rect = Rect(offset = Offset(left, top), size = Size(boxWidth, boxHeight)),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            )
        }

        clipPath(clipPath, clipOp = ClipOp.Difference) {
            drawRect(
                color = Color.Black.copy(alpha = 0.65f),
                size = Size(width, height)
            )
        }

        val markerLength = 32.dp.toPx()
        val strokeWidth = 4.dp.toPx()
        val cornerRadius = 16.dp.toPx()
        val borderColor = Color(0xFF38BDF8) // Modern cyan custom accent

        // Corner drawing helpers to render sharp overlay elements
        // Top Left
        drawLine(borderColor, Offset(left - strokeWidth/2, top + cornerRadius), Offset(left - strokeWidth/2, top + markerLength), strokeWidth)
        drawLine(borderColor, Offset(left - strokeWidth/2, top - strokeWidth/2), Offset(left + markerLength, top - strokeWidth/2), strokeWidth)

        // Top Right
        drawLine(borderColor, Offset(left + boxWidth + strokeWidth/2, top + cornerRadius), Offset(left + boxWidth + strokeWidth/2, top + markerLength), strokeWidth)
        drawLine(borderColor, Offset(left + boxWidth + strokeWidth/2, top - strokeWidth/2), Offset(left + boxWidth - markerLength, top - strokeWidth/2), strokeWidth)

        // Bottom Left
        drawLine(borderColor, Offset(left - strokeWidth/2, top + boxHeight - cornerRadius), Offset(left - strokeWidth/2, top + boxHeight - markerLength), strokeWidth)
        drawLine(borderColor, Offset(left - strokeWidth/2, top + boxHeight + strokeWidth/2), Offset(left + markerLength, top + boxHeight + strokeWidth/2), strokeWidth)

        // Bottom Right
        drawLine(borderColor, Offset(left + boxWidth + strokeWidth/2, top + boxHeight - cornerRadius), Offset(left + boxWidth + strokeWidth/2, top + boxHeight - markerLength), strokeWidth)
        drawLine(borderColor, Offset(left + boxWidth + strokeWidth/2, top + boxHeight + strokeWidth/2), Offset(left + boxWidth - markerLength, top + boxHeight + strokeWidth/2), strokeWidth)
    }
}

package com.example.ui.scanner

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun CameraScannerView(
    modifier: Modifier = Modifier,
    isTorchOn: Boolean = false,
    useFrontCamera: Boolean = false,
    onBarcodeDetected: (code: String, format: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var camera by remember { mutableStateOf<Camera?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Use COMPATIBLE (TextureView) to prevent BufferQueue abandoned errors in Compose
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Toggle torch when state changes
    LaunchedEffect(isTorchOn, camera) {
        try {
            camera?.cameraControl?.enableTorch(isTorchOn)
        } catch (e: Exception) {
            Log.e("CameraScanner", "Error toggling torch", e)
        }
    }

    // Bind and unbind camera lifecycle cleanly
    DisposableEffect(lifecycleOwner, useFrontCamera) {
        var cameraProvider: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        var currentAnalyzer: BarcodeAnalyzer? = null
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analyzer = BarcodeAnalyzer { code, format ->
                    onBarcodeDetected(code, format)
                }
                currentAnalyzer = analyzer

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, analyzer)
                    }

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraScanner", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                cameraProvider?.unbindAll()
                currentAnalyzer?.close()
            } catch (e: Exception) {
                Log.e("CameraScanner", "Camera unbind failed", e)
            }
            camera = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(offset.x, offset.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                }
            }
    ) {
        // AndroidView for CameraX PreviewView
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Modern HUD Viewfinder with animated laser line and corner brackets
        ScannerViewfinderOverlay(
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun ScannerViewfinderOverlay(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "LaserTransition")
    val laserPosition by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LaserAnimation"
    )

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val width = maxWidth
        val height = maxHeight
        val targetSize = minOf(width * 0.75f, 300.dp)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val boxPx = targetSize.toPx()
            val left = (canvasWidth - boxPx) / 2f
            val top = (canvasHeight - boxPx) / 2f
            val right = left + boxPx
            val bottom = top + boxPx
            val cornerRadius = 24.dp.toPx()

            // 1. Scrim (darkened semi-transparent overlay outside target box)
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, canvasWidth, canvasHeight))
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        radiusX = cornerRadius,
                        radiusY = cornerRadius
                    )
                )
            }
            drawPath(path, color = Color(0x99000000))

            // 2. Subtle bounding box border
            drawRoundRect(
                color = Color(0x44FFFFFF),
                topLeft = Offset(left, top),
                size = Size(boxPx, boxPx),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 3. High-contrast corner brackets (Teal / Emerald)
            val cornerLength = 32.dp.toPx()
            val strokeWidth = 4.dp.toPx()
            val bracketColor = Color(0xFF10B981)

            // Top-Left Corner
            drawLine(
                color = bracketColor,
                start = Offset(left - 2, top + cornerLength),
                end = Offset(left - 2, top + cornerRadius / 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = bracketColor,
                start = Offset(left + cornerRadius / 2, top - 2),
                end = Offset(left + cornerLength, top - 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            // Top-Right Corner
            drawLine(
                color = bracketColor,
                start = Offset(right + 2, top + cornerLength),
                end = Offset(right + 2, top + cornerRadius / 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = bracketColor,
                start = Offset(right - cornerLength, top - 2),
                end = Offset(right - cornerRadius / 2, top - 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            // Bottom-Left Corner
            drawLine(
                color = bracketColor,
                start = Offset(left - 2, bottom - cornerLength),
                end = Offset(left - 2, bottom - cornerRadius / 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = bracketColor,
                start = Offset(left + cornerRadius / 2, bottom + 2),
                end = Offset(left + cornerLength, bottom + 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            // Bottom-Right Corner
            drawLine(
                color = bracketColor,
                start = Offset(right + 2, bottom - cornerLength),
                end = Offset(right + 2, bottom - cornerRadius / 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = bracketColor,
                start = Offset(right - cornerLength, bottom + 2),
                end = Offset(right - cornerRadius / 2, bottom + 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            // 4. Moving Laser Beam
            val laserY = top + (boxPx * laserPosition)
            val laserStart = left + 12.dp.toPx()
            val laserEnd = right - 12.dp.toPx()

            // Glow brush for laser
            val laserGlowBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0x00EF4444),
                    Color(0x66EF4444),
                    Color(0xFFEF4444),
                    Color(0x66EF4444),
                    Color(0x00EF4444)
                ),
                startY = laserY - 8.dp.toPx(),
                endY = laserY + 8.dp.toPx()
            )

            drawRect(
                brush = laserGlowBrush,
                topLeft = Offset(laserStart, laserY - 8.dp.toPx()),
                size = Size(laserEnd - laserStart, 16.dp.toPx())
            )

            drawLine(
                color = Color(0xFFFF5252),
                start = Offset(laserStart, laserY),
                end = Offset(laserEnd, laserY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

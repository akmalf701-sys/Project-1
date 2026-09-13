package com.example.ui.scanner

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun CameraScannerView(
    modifier: Modifier = Modifier,
    isTorchOn: Boolean = false,
    useFrontCamera: Boolean = false,
    autoScanEnabled: Boolean = true,
    onToggleAutoScan: () -> Unit = {},
    onBarcodeDetected: (code: String, format: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var camera by remember { mutableStateOf<Camera?>(null) }
    var currentAnalyzer by remember { mutableStateOf<BarcodeAnalyzer?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // PERFORMANCE (SurfaceView) avoids TextureView OpenGL gralloc buffer leaks & SELinux rate limits
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    // Freeze Frame State (User requested shutter button to stop/freeze camera frame)
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessingFreeze by remember { mutableStateOf(false) }
    var freezeResultCode by remember { mutableStateOf<String?>(null) }
    var freezeFailedMessage by remember { mutableStateOf<String?>(null) }

    // Synchronize autoScanEnabled with analyzer
    LaunchedEffect(autoScanEnabled, currentAnalyzer) {
        currentAnalyzer?.autoScanEnabled = autoScanEnabled
    }

    // Toggle torch when state changes
    LaunchedEffect(isTorchOn, camera) {
        try {
            camera?.cameraControl?.enableTorch(isTorchOn)
        } catch (e: Exception) {
            Log.e("CameraScanner", "Error toggling torch", e)
        }
    }

    // Helper to decode barcode on a still frozen bitmap
    fun processStillImage(bmp: Bitmap) {
        frozenBitmap = bmp
        currentAnalyzer?.isPaused = true
        isProcessingFreeze = true
        freezeResultCode = null
        freezeFailedMessage = null

        val inputImage = InputImage.fromBitmap(bmp, 0)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        val stillScanner = BarcodeScanning.getClient(options)
        stillScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                isProcessingFreeze = false
                val validBarcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (validBarcode != null) {
                    val code = validBarcode.rawValue!!.trim()
                    val format = BarcodeAnalyzer.getFormatName(validBarcode.format)
                    freezeResultCode = code
                    onBarcodeDetected(code, format)
                } else {
                    freezeFailedMessage = "Barcode tidak terdeteksi pada foto ini. Pastikan garis barcode berada dalam kotak tengah dan tidak tertutup."
                }
            }
            .addOnFailureListener {
                isProcessingFreeze = false
                freezeFailedMessage = "Gagal memproses gambar bidikan."
            }
            .addOnCompleteListener {
                try {
                    stillScanner.close()
                } catch (_: Exception) {}
            }
    }

    // Function to freeze current frame and decode barcode reliably
    fun captureFrameAndScan() {
        if (frozenBitmap != null || isProcessingFreeze) return
        val bmp = previewView.bitmap
        if (bmp != null) {
            processStillImage(bmp)
        } else {
            // High-reliability ImageCapture fallback when SurfaceView bitmap is direct
            val capture = imageCapture
            if (capture != null) {
                isProcessingFreeze = true
                currentAnalyzer?.isPaused = true
                capture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            try {
                                val capturedBmp = imageProxy.toBitmap()
                                ContextCompat.getMainExecutor(context).execute {
                                    processStillImage(capturedBmp)
                                }
                            } catch (e: Exception) {
                                Log.e("CameraScanner", "ImageProxy toBitmap failed", e)
                                ContextCompat.getMainExecutor(context).execute {
                                    isProcessingFreeze = false
                                    freezeFailedMessage = "Gagal mengambil foto bidikan."
                                }
                            } finally {
                                imageProxy.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraScanner", "ImageCapture failed", exception)
                            ContextCompat.getMainExecutor(context).execute {
                                isProcessingFreeze = false
                                freezeFailedMessage = "Gagal mengambil foto dari kamera."
                            }
                        }
                    }
                )
            }
        }
    }

    // Function to unfreeze and resume live camera preview
    fun resumeLiveCamera() {
        frozenBitmap = null
        isProcessingFreeze = false
        freezeResultCode = null
        freezeFailedMessage = null
        currentAnalyzer?.isPaused = false
    }

    // Bind and unbind camera lifecycle cleanly
    DisposableEffect(lifecycleOwner, useFrontCamera) {
        var cameraProvider: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                @Suppress("DEPRECATION")
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val analyzer = BarcodeAnalyzer { code, format ->
                    onBarcodeDetected(code, format)
                }.apply {
                    this.autoScanEnabled = autoScanEnabled
                }
                currentAnalyzer = analyzer

                // 640x480 is optimal for barcode scanning, reducing memory and binder IPC traffic by 67%
                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, analyzer)
                    }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                    capture
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
            imageCapture = null
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
        // 1. AndroidView for CameraX PreviewView (Live Feed)
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Frozen Still Image Overlay (When user taps shutter button to freeze and focus clearly)
        frozenBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Bidikan Kamera Dibekukan",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Modern HUD Viewfinder (Corners & Laser)
        ScannerViewfinderOverlay(
            modifier = Modifier.fillMaxSize(),
            isFrozen = frozenBitmap != null
        )

        // 4. Frozen Status Badge at Top
        AnimatedVisibility(
            visible = frozenBitmap != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 138.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xEE0F172A),
                border = BorderStroke(1.dp, Color(0xFF10B981))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "📸 KAMERA DIJEDA (GAMBAR DIAM)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 5. Bottom Controls: Shutter Button / Freeze Controls & Mode Toggle
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (frozenBitmap == null) {
                // Live View Mode: Shutter Button & Scan Mode Chip
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mode Toggle Pill: Auto-Scan vs Manual Photo Shutter
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xBB000000),
                        border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onToggleAutoScan() }
                            .testTag("toggle_auto_scan_mode")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (autoScanEnabled) Icons.Default.Bolt else Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = if (autoScanEnabled) Color(0xFF38BDF8) else Color(0xFF34D399),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (autoScanEnabled) "Mode: Scan Otomatis (Klik ganti)" else "Mode: Tombol Foto Saja (Klik ganti)",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Big Elegant Shutter Button (Tekan untuk foto / jeda kamera)
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(3.5.dp, Color.White, CircleShape)
                            .background(if (autoScanEnabled) Color(0x44000000) else Color(0x3310B981))
                            .clickable { captureFrameAndScan() }
                            .testTag("camera_freeze_shutter_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(if (autoScanEnabled) Color.White else Color(0xFF10B981)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Foto & Jeda Kamera",
                                tint = if (autoScanEnabled) Color(0xFF0F172A) else Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Text(
                        text = if (autoScanEnabled) "Tekan untuk foto & jeda (Anti-Goyang)" else "Arahkan barcode, lalu tekan tombol foto",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Frozen Mode: Results & Resume Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isProcessingFreeze) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xF00F172A)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFF10B981),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Membaca barcode dari foto bidikan...",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else if (freezeResultCode != null) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xF0064E3B)),
                            border = BorderStroke(1.dp, Color(0xFF10B981)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "✅ Barcode Terbidik Jelas!",
                                    color = Color(0xFF6EE7B7),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = freezeResultCode!!,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { resumeLiveCamera() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF10B981),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Lanjut Bidik / Buka Kamera", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Failed to detect on still image
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xF0450A0A)),
                            border = BorderStroke(1.dp, Color(0xFFEF4444)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = Color(0xFFFCA5A5),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Barcode Belum Terbaca",
                                        color = Color(0xFFFCA5A5),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = freezeFailedMessage ?: "Pastikan barcode berada tepat di dalam kotak bidik dan tidak silau.",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { resumeLiveCamera() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color(0xFF450A0A)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Bidik Ulang (Buka Kamera)", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScannerViewfinderOverlay(
    modifier: Modifier = Modifier,
    isFrozen: Boolean = false
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
                color = if (isFrozen) Color(0x8810B981) else Color(0x44FFFFFF),
                topLeft = Offset(left, top),
                size = Size(boxPx, boxPx),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 3. High-contrast corner brackets (Teal / Emerald)
            val cornerLength = 32.dp.toPx()
            val strokeWidth = 4.dp.toPx()
            val bracketColor = if (isFrozen) Color(0xFF34D399) else Color(0xFF10B981)

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

            // 4. Laser Line
            if (!isFrozen) {
                // Moving Laser Beam
                val laserY = top + (boxPx * laserPosition)
                val laserStart = left + 12.dp.toPx()
                val laserEnd = right - 12.dp.toPx()

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
            } else {
                // Calm, static green guide line when frozen
                val laserY = top + (boxPx * 0.5f)
                val laserStart = left + 12.dp.toPx()
                val laserEnd = right - 12.dp.toPx()

                drawLine(
                    color = Color(0xFF10B981),
                    start = Offset(laserStart, laserY),
                    end = Offset(laserEnd, laserY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

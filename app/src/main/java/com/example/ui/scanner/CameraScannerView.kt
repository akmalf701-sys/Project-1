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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.ZoomIn
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var freezeOcrCandidates by remember { mutableStateOf<List<String>>(emptyList()) }

    var zoomRatio by remember { mutableFloatStateOf(1.0f) }
    var tapFocusPoint by remember { mutableStateOf<Offset?>(null) }
    val coroutineScope = rememberCoroutineScope()

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

    // Helper to decode barcode and OCR on a still frozen bitmap
    fun processStillImage(bmp: Bitmap) {
        frozenBitmap = bmp
        currentAnalyzer?.isPaused = true
        isProcessingFreeze = true
        freezeResultCode = null
        freezeFailedMessage = null
        freezeOcrCandidates = emptyList()

        val inputImage = InputImage.fromBitmap(bmp, 0)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_ITF
            )
            .build()
        val stillScanner = BarcodeScanning.getClient(options)
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        stillScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val validBarcode = barcodes.firstOrNull {
                    !it.rawValue.isNullOrBlank() && BarcodeAnalyzer.isValidBarcode(it.rawValue!!, it.format)
                }

                if (validBarcode != null) {
                    val raw = validBarcode.rawValue!!.trim()
                    val code = BarcodeAnalyzer.cleanBarcodeValue(raw, validBarcode.format)
                    val format = BarcodeAnalyzer.getFormatName(validBarcode.format)
                    freezeResultCode = code
                    onBarcodeDetected(code, format)
                    isProcessingFreeze = false
                } else {
                    // Barcode not detected, check if OCR can find a contract number candidate to suggest
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val candidates = mutableListOf<String>()
                            for (block in visionText.textBlocks) {
                                for (line in block.lines) {
                                    val words = line.text.trim().split("\\s+".toRegex())
                                    for (w in words) {
                                        val clean = w.replace("[^A-Za-z0-9]".toRegex(), "")
                                        if (clean.length >= 6) {
                                            candidates.add(clean)
                                        }
                                    }
                                }
                            }
                            val distinct = candidates.distinct().filter { c ->
                                c.length in 8..24 && (c.any { it.isDigit() })
                            }
                            freezeOcrCandidates = distinct
                            freezeFailedMessage = "Garis barcode belum terdeteksi jelas. Pastikan barcode berada di dalam kotak tengah dan gunakan tombol Zoom 1.5x / 2x."
                            isProcessingFreeze = false
                        }
                        .addOnFailureListener {
                            freezeFailedMessage = "Garis barcode belum terdeteksi jelas. Pastikan barcode berada di dalam kotak tengah."
                            isProcessingFreeze = false
                        }
                        .addOnCompleteListener {
                            try { textRecognizer.close() } catch (_: Exception) {}
                        }
                }
            }
            .addOnFailureListener {
                isProcessingFreeze = false
                freezeFailedMessage = "Gagal memproses gambar. Silakan bidik ulang."
            }
            .addOnCompleteListener {
                try { stillScanner.close() } catch (_: Exception) {}
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
        freezeOcrCandidates = emptyList()
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

                val analyzer = BarcodeAnalyzer(
                    onBarcodeDetected = { code, format ->
                        onBarcodeDetected(code, format)
                    }
                ).apply {
                    this.autoScanEnabled = autoScanEnabled
                }
                currentAnalyzer = analyzer

                // High Definition 1280x720 ensures crystal-clear barcode lines and OCR sharpness
                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
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

                val boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                    capture
                )
                camera = boundCamera
                // Re-apply zoom if set
                boundCamera.cameraControl.setZoomRatio(zoomRatio)
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
                    tapFocusPoint = offset
                    coroutineScope.launch {
                        delay(1500)
                        if (tapFocusPoint == offset) {
                            tapFocusPoint = null
                        }
                    }
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(offset.x, offset.y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
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

        // Tap-to-focus visual ring indicator
        val density = LocalDensity.current
        val ringHalfSizePx = with(density) { 30.dp.roundToPx() }
        tapFocusPoint?.let { pt ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            pt.x.toInt() - ringHalfSizePx,
                            pt.y.toInt() - ringHalfSizePx
                        )
                    }
                    .size(60.dp)
                    .border(2.dp, Color(0xFFFBBF24), CircleShape)
            )
        }

        // 4. Focus Guidance Banner at Top
        AnimatedVisibility(
            visible = frozenBitmap == null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xD00F172A),
                border = BorderStroke(1.dp, Color(0x33FFFFFF))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Arahkan kotak ke garis barcode",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 5. Frozen Status Badge at Top
        AnimatedVisibility(
            visible = frozenBitmap != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp)
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

        // 6. Bottom Controls: Zoom + Shutter Button + Results
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (frozenBitmap == null) {
                // Live View Mode: Zoom buttons + Shutter Button & Scan Mode Chip
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Zoom selector bar (1.0x, 1.5x, 2.0x) - Helps focus clearly on tiny barcode stickers
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(1.0f to "1.0x", 1.5f to "1.5x", 2.0f to "2.0x").forEach { (ratio, label) ->
                            val isSelected = (zoomRatio == ratio)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) Color(0xFF10B981) else Color(0xAA0F172A),
                                border = BorderStroke(1.dp, if (isSelected) Color.White else Color(0x33FFFFFF)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        zoomRatio = ratio
                                        camera?.cameraControl?.setZoomRatio(ratio)
                                    }
                                    .testTag("zoom_btn_$label")
                            ) {
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

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
                        text = if (autoScanEnabled) {
                            "Tekan tombol foto jika barcode sulit fokus / bergerak"
                        } else {
                            "Arahkan barcode, lalu tekan tombol foto"
                        },
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
                                    text = "Menganalisis barcode & teks label...",
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
                                    text = "✅ Kode Berhasil Terbaca!",
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

                                // If multiple numbers or contract texts were detected on the label, offer chips to switch
                                if (freezeOcrCandidates.size > 1) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Teks lain di label (klik jika ini nomor kontrak):",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 10.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        freezeOcrCandidates.filter { it != freezeResultCode }.take(3).forEach { cand ->
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0x44FFFFFF),
                                                border = BorderStroke(0.5.dp, Color(0x66FFFFFF)),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        freezeResultCode = cand
                                                        onBarcodeDetected(cand, "NOMOR_KONTRAK")
                                                    }
                                            ) {
                                                Text(
                                                    text = cand,
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                }

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
                                        text = "Kode Belum Terbaca",
                                        color = Color(0xFFFCA5A5),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = freezeFailedMessage ?: "Pastikan barcode/nomor kontrak berada di dalam kotak bidik dan gunakan tombol Zoom 1.5x/2x jika stiker kecil.",
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

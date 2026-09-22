package com.example.ui.scanner

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
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
    numericOnlyMode: Boolean = false,
    targetDigitLength: Int? = null,
    onToggleNumericOnlyMode: () -> Unit = {},
    onToggleExact10Digits: () -> Unit = {},
    onOpenDigitLengthSelector: () -> Unit = {},
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
            scaleType = PreviewView.ScaleType.FIT_CENTER
            // COMPATIBLE (TextureView) avoids SurfaceView BLASTBufferQueue abandoned errors during Compose recomposition & screen navigation
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

    // Synchronize autoScanEnabled and numericOnlyMode with analyzer
    LaunchedEffect(autoScanEnabled, currentAnalyzer) {
        currentAnalyzer?.autoScanEnabled = autoScanEnabled
    }
    LaunchedEffect(numericOnlyMode, currentAnalyzer) {
        currentAnalyzer?.numericOnlyMode = numericOnlyMode
    }
    LaunchedEffect(targetDigitLength, currentAnalyzer) {
        currentAnalyzer?.targetDigitLength = targetDigitLength
    }

    // Toggle torch when state changes
    LaunchedEffect(isTorchOn, camera) {
        try {
            camera?.cameraControl?.enableTorch(isTorchOn)
        } catch (e: Exception) {
            Log.e("CameraScanner", "Error toggling torch", e)
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
        currentAnalyzer?.resetDetectionState()
    }

    // Helper to decode barcode on a still frozen bitmap
    fun processStillImage(bmp: Bitmap) {
        frozenBitmap = bmp
        currentAnalyzer?.isPaused = true
        isProcessingFreeze = true
        freezeResultCode = null
        freezeFailedMessage = null
        freezeOcrCandidates = emptyList()

        val inputImage = InputImage.fromBitmap(bmp, 0)
        // Strictly exclude CODABAR & CODE_93 which misread label text
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
        val stillScanner = BarcodeScanning.getClient(options)
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        stillScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val validList = barcodes.filter {
                    !it.rawValue.isNullOrBlank() && BarcodeAnalyzer.isValidBarcode(it.rawValue!!, it.format)
                }

                val chosenBarcode = if (targetDigitLength != null) {
                    val reqLen = targetDigitLength
                    validList.firstOrNull {
                        val code = BarcodeAnalyzer.cleanBarcodeValue(it.rawValue ?: "", it.format)
                        code.length == reqLen && code.all { ch -> ch.isDigit() }
                    }
                } else if (numericOnlyMode) {
                    validList.firstOrNull {
                        val code = BarcodeAnalyzer.cleanBarcodeValue(it.rawValue ?: "", it.format)
                        code.isNotEmpty() && code.all { ch -> ch.isDigit() }
                    }
                } else {
                    validList.sortedByDescending {
                        val code = BarcodeAnalyzer.cleanBarcodeValue(it.rawValue ?: "", it.format)
                        if (code.isNotEmpty() && code.all { ch -> ch.isDigit() }) 1 else 0
                    }.firstOrNull()
                }

                if (chosenBarcode != null) {
                    val raw = chosenBarcode.rawValue!!.trim()
                    val code = BarcodeAnalyzer.cleanBarcodeValue(raw, chosenBarcode.format)
                    val format = BarcodeAnalyzer.getFormatName(chosenBarcode.format)
                    freezeResultCode = code
                    onBarcodeDetected(code, format)
                    isProcessingFreeze = false

                    // Auto-resume live camera after 1.8 seconds so user can scan next barcode seamlessly
                    coroutineScope.launch {
                        delay(1800)
                        if (frozenBitmap != null) {
                            resumeLiveCamera()
                        }
                    }
                } else {
                    // Check if a barcode was actually found but rejected because length != targetDigitLength
                    val mismatchBarcode = if (targetDigitLength != null) {
                        validList.firstOrNull()?.let {
                            BarcodeAnalyzer.cleanBarcodeValue(it.rawValue ?: "", it.format)
                        }
                    } else null

                    // If barcode line not decoded, search OCR exclusively for the barcode numeric string
                    // (e.g. "* 4 7 6 2 6 0 4 8 3 9 *" printed directly under the barcode)
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            var foundNumericBarcode: String? = null
                            for (block in visionText.textBlocks) {
                                for (line in block.lines) {
                                    val text = line.text.trim()
                                    val digitsOnly = text.filter { it.isDigit() }
                                    if (targetDigitLength != null) {
                                        if (digitsOnly.length == targetDigitLength) {
                                            foundNumericBarcode = digitsOnly
                                            break
                                        }
                                    } else {
                                        // Check if line looks like "* 4 7 6 2 6 0 4 8 3 9 *" or "4762604839"
                                        if (digitsOnly.length in 6..24 && (text.contains("*") || digitsOnly.length >= 8)) {
                                            foundNumericBarcode = digitsOnly
                                            break
                                        }
                                    }
                                }
                                if (foundNumericBarcode != null) break
                            }

                            if (foundNumericBarcode != null) {
                                val cleanCode = BarcodeAnalyzer.autoCorrectNearNumericCode(foundNumericBarcode)
                                freezeResultCode = cleanCode
                                onBarcodeDetected(cleanCode, "CODE_39")
                                isProcessingFreeze = false
                                coroutineScope.launch {
                                    delay(1800)
                                    if (frozenBitmap != null) {
                                        resumeLiveCamera()
                                    }
                                }
                            } else {
                                freezeFailedMessage = when {
                                    mismatchBarcode != null && targetDigitLength != null ->
                                        "Barcode ditolak: Terdeteksi ${mismatchBarcode.length} digit. Mode wajib tepat $targetDigitLength angka!"
                                    targetDigitLength != null ->
                                        "Tidak ditemukan barcode tepat $targetDigitLength angka. Posisikan barcode di dalam kotak bidik."
                                    else ->
                                        "Garis barcode belum terdeteksi jelas. Posisikan barcode di dalam kotak bidik."
                                }
                                isProcessingFreeze = false
                            }
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

    LaunchedEffect(autoScanEnabled) {
        currentAnalyzer?.autoScanEnabled = autoScanEnabled
        if (autoScanEnabled) {
            currentAnalyzer?.resetDetectionState()
        }
    }

    // Bind and unbind camera lifecycle cleanly
    DisposableEffect(lifecycleOwner, useFrontCamera) {
        var isDisposed = false
        var cameraProvider: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            if (isDisposed) {
                try {
                    cameraProviderFuture.get().unbindAll()
                } catch (_: Exception) {}
                return@addListener
            }
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                // Fixed high-resolution 16:9 aspect ratio selector to prevent automatic zooming, stretching, or sensor cropping
                val highResResolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_16_9,
                            AspectRatioStrategy.FALLBACK_RULE_AUTO
                        )
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(highResResolutionSelector)
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
                    this.numericOnlyMode = numericOnlyMode
                    this.targetDigitLength = targetDigitLength
                }
                currentAnalyzer = analyzer

                // ImageAnalysis with matching fixed high-resolution selector prevents zooming and distortion
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(highResResolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, analyzer)
                    }

                val capture = ImageCapture.Builder()
                    .setResolutionSelector(highResResolutionSelector)
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

                // Trigger center autofocus
                previewView.post {
                    try {
                        val factory = previewView.meteringPointFactory
                        val centerPoint = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
                        val action = androidx.camera.core.FocusMeteringAction.Builder(
                            centerPoint,
                            androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
                        boundCamera.cameraControl.startFocusAndMetering(action)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("CameraScanner", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            isDisposed = true
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

        // 5. Frozen Status Badge at Top or Target Digit Length Banner
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

        AnimatedVisibility(
            visible = frozenBitmap == null && targetDigitLength != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xEE042F2E),
                border = BorderStroke(1.dp, Color(0xFF14B8A6)),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenDigitLengthSelector() }
                    .testTag("banner_target_digit_active")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF2DD4BF),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "🎯 WAJIB $targetDigitLength ANGKA (Mencegah Angka Kurang/Lebih)",
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
                    // Zoom selector bar (1.0x, 1.5x, 2.0x) + Numeric-Only Mode Toggle
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

                        // Toggle Mode Hanya Angka (Mencegah salah baca teks/huruf label sembarangan)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (numericOnlyMode) Color(0xFF0D9488) else Color(0xAA0F172A),
                            border = BorderStroke(1.dp, if (numericOnlyMode) Color(0xFF5EEAD4) else Color(0x33FFFFFF)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onToggleNumericOnlyMode() }
                                .testTag("toggle_numeric_only_mode")
                        ) {
                            Text(
                                text = if (numericOnlyMode) "🔢 123 Angka ON" else "🔢 123 Angka OFF",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (numericOnlyMode) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Row: Kunci 10 Angka (User requested: Cegah kekurangan/kelebihan angka barcode)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (targetDigitLength == 10) Color(0xFF0F766E) else if (targetDigitLength != null) Color(0xFF1E293B) else Color(0xAA0F172A),
                            border = BorderStroke(1.dp, if (targetDigitLength != null) Color(0xFF2DD4BF) else Color(0x33FFFFFF)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onToggleExact10Digits() }
                                .testTag("toggle_exact_10_digits_mode")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (targetDigitLength != null) Color(0xFF5EEAD4) else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (targetDigitLength == 10) "🎯 Kunci 10 Angka: AKTIF" else if (targetDigitLength != null) "🎯 Kunci $targetDigitLength Angka: AKTIF" else "🎯 Kunci 10 Angka: OFF",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (targetDigitLength != null) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xAA0F172A),
                            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onOpenDigitLengthSelector() }
                                .testTag("btn_open_digit_selector")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Atur",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
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

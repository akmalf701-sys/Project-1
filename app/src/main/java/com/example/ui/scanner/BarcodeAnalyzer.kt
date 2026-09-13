package com.example.ui.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class BarcodeAnalyzer(
    private val onBarcodeDetected: (code: String, format: String) -> Unit
) : ImageAnalysis.Analyzer {

    // Configure scanner for all supported 1D and 2D barcode formats
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_ALL_FORMATS
        )
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    @Volatile
    private var isAnalyzing = false

    @Volatile
    private var lastAnalyzedTimestamp = 0L

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    var autoScanEnabled: Boolean = true

    // Stability filter: requires identical reading across consecutive frames for 1D barcodes
    // to completely eliminate motion blur partial misreads while products are moving
    private var candidateCode: String = ""
    private var candidateFormat: String = ""
    private var candidateCount: Int = 0
    private var candidateFirstSeenTime: Long = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // Check if paused or live scan is disabled (manual photo button mode)
        if (isPaused || !autoScanEnabled) {
            imageProxy.close()
            return
        }

        // Throttle frame analysis to prevent CPU overload and SELinux audit rate limiting (4 FPS is optimal)
        if (isAnalyzing || (now - lastAnalyzedTimestamp < 250L)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            isAnalyzing = true
            lastAnalyzedTimestamp = now
            try {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isEmpty()) {
                            if (now - candidateFirstSeenTime > 1200L) {
                                candidateCode = ""
                                candidateCount = 0
                            }
                        } else {
                            for (barcode in barcodes) {
                                val rawValue = barcode.rawValue
                                if (!rawValue.isNullOrBlank()) {
                                    val trimmed = rawValue.trim()
                                    val formatStr = getFormatName(barcode.format)

                                    val isLinear1D = when (barcode.format) {
                                        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                                        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                                        Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODABAR,
                                        Barcode.FORMAT_ITF -> true
                                        else -> false
                                    }

                                    if (!isLinear1D) {
                                        // 2D QR / DataMatrix has built-in ECC checksums
                                        onBarcodeDetected(trimmed, formatStr)
                                    } else {
                                        // 1D Barcode: Require 2 consecutive matching reads within 1200ms
                                        // to verify the image is stable and prevent motion blur corruptions
                                        val isSameCandidate = (trimmed == candidateCode) && (now - candidateFirstSeenTime < 1200L)
                                        if (isSameCandidate) {
                                            candidateCount++
                                            if (candidateCount >= 2) {
                                                onBarcodeDetected(trimmed, formatStr)
                                                candidateCode = ""
                                                candidateCount = 0
                                            }
                                        } else {
                                            candidateCode = trimmed
                                            candidateFormat = formatStr
                                            candidateCount = 1
                                            candidateFirstSeenTime = now
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .addOnFailureListener {
                        // Ignore transient frame errors
                    }
                    .addOnCompleteListener {
                        isAnalyzing = false
                        imageProxy.close()
                    }
            } catch (e: Exception) {
                isAnalyzing = false
                imageProxy.close()
            }
        } else {
            imageProxy.close()
        }
    }

    fun close() {
        try {
            scanner.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun getFormatName(format: Int): String {
            return when (format) {
                Barcode.FORMAT_CODE_128 -> "CODE_128"
                Barcode.FORMAT_CODE_39 -> "CODE_39"
                Barcode.FORMAT_CODE_93 -> "CODE_93"
                Barcode.FORMAT_CODABAR -> "CODABAR"
                Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
                Barcode.FORMAT_EAN_13 -> "EAN_13"
                Barcode.FORMAT_EAN_8 -> "EAN_8"
                Barcode.FORMAT_ITF -> "ITF"
                Barcode.FORMAT_QR_CODE -> "QR_CODE"
                Barcode.FORMAT_UPC_A -> "UPC_A"
                Barcode.FORMAT_UPC_E -> "UPC_E"
                Barcode.FORMAT_PDF417 -> "PDF417"
                Barcode.FORMAT_AZTEC -> "AZTEC"
                else -> "BARCODE"
            }
        }
    }
}

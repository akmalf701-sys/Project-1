package com.example.ui.scanner

import android.util.Log
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

    // Configure scanner strictly for standard product and contract 1D/2D barcodes
    // Exclude CODABAR and CODE_93 which easily misread printed text as barcodes
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
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
    )

    @Volatile
    private var isAnalyzing = false

    @Volatile
    private var lastAnalyzedTimestamp = 0L

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    var autoScanEnabled: Boolean = true

    // Cooldown filter to avoid rapid duplicate spam for the same code
    private var lastDetectedCode: String = ""
    private var lastDetectedTimestamp: Long = 0L

    // Stability candidate for 1D barcodes (Must match on consecutive frames)
    private var candidateCode: String = ""
    private var candidateCount: Int = 0
    private var candidateFirstSeenTime: Long = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // Check if paused or live scan is disabled
        if (isPaused || !autoScanEnabled) {
            imageProxy.close()
            return
        }

        // Throttle frame analysis (~6 FPS) for smooth performance and accurate reading
        if (isAnalyzing || (now - lastAnalyzedTimestamp < 160L)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            isAnalyzing = true
            lastAnalyzedTimestamp = now
            try {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                analyzeBarcode(image, imageProxy, now)
            } catch (e: Exception) {
                Log.e("BarcodeAnalyzer", "Analysis error", e)
                isAnalyzing = false
                imageProxy.close()
            }
        } else {
            imageProxy.close()
        }
    }

    private fun analyzeBarcode(image: InputImage, imageProxy: ImageProxy, now: Long) {
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val imgW = image.width.toFloat()
                val imgH = image.height.toFloat()

                // Find first valid barcode that passes all filters:
                // 1. Content check (No spaces or text artifacts)
                // 2. Viewfinder ROI (Must be centered inside the viewfinder box)
                val validBarcode = barcodes.firstOrNull { barcode ->
                    val raw = barcode.rawValue
                    if (raw.isNullOrBlank()) return@firstOrNull false

                    // Reject any 1D barcode containing spaces or invalid characters
                    if (!isValidBarcode(raw, barcode.format)) {
                        return@firstOrNull false
                    }

                    // Region of interest: Barcode must be centered within the viewfinder box
                    val box = barcode.boundingBox
                    if (box != null && imgW > 0 && imgH > 0) {
                        val cx = box.centerX().toFloat()
                        val cy = box.centerY().toFloat()

                        val inCenterW = cx >= (imgW * 0.12f) && cx <= (imgW * 0.88f)
                        val inCenterH = cy >= (imgH * 0.18f) && cy <= (imgH * 0.82f)

                        if (!inCenterW || !inCenterH) {
                            return@firstOrNull false
                        }
                    }

                    true
                }

                if (validBarcode != null) {
                    val raw = validBarcode.rawValue!!.trim()
                    val code = cleanBarcodeValue(raw, validBarcode.format)
                    val formatStr = getFormatName(validBarcode.format)

                    val is2D = (validBarcode.format == Barcode.FORMAT_QR_CODE || 
                                validBarcode.format == Barcode.FORMAT_DATA_MATRIX)

                    if (is2D) {
                        // 2D codes have built-in error correction / checksums
                        triggerIfAllowed(code, formatStr, now)
                    } else {
                        // 1D Barcode: Enforce strict multi-frame stability!
                        // Transient text misreads (like "W .605Y02") change every frame and never repeat.
                        // Real barcodes match identically across frames.
                        val isSameCandidate = (code == candidateCode) && (now - candidateFirstSeenTime < 800L)
                        if (isSameCandidate) {
                            candidateCount++
                            if (candidateCount >= 2) {
                                triggerIfAllowed(code, formatStr, now)
                                candidateCode = ""
                                candidateCount = 0
                            }
                        } else {
                            candidateCode = code
                            candidateCount = 1
                            candidateFirstSeenTime = now
                        }
                    }
                } else {
                    if (now - candidateFirstSeenTime > 600L) {
                        candidateCode = ""
                        candidateCount = 0
                    }
                }
            }
            .addOnCompleteListener {
                isAnalyzing = false
                imageProxy.close()
            }
    }

    private fun triggerIfAllowed(code: String, format: String, now: Long) {
        if (code.isBlank()) return
        // Prevent duplicate trigger within 2 seconds for the same code
        if (code == lastDetectedCode && (now - lastDetectedTimestamp < 2000L)) {
            return
        }
        lastDetectedCode = code
        lastDetectedTimestamp = now
        onBarcodeDetected(code, format)
    }

    fun close() {
        try {
            barcodeScanner.close()
        } catch (_: Exception) {}
    }

    companion object {
        /**
         * Validates barcode content to reject text misreads.
         * Real barcodes (Code 39, Code 128, etc.) do NOT have random spaces or isolated periods.
         */
        fun isValidBarcode(raw: String, format: Int): Boolean {
            val trimmed = raw.trim()
            if (trimmed.length < 3) return false

            // Reject any 1D barcode containing whitespace - this is the #1 cause of text misreads
            if (trimmed.contains(" ") || trimmed.contains("\t") || trimmed.contains("\n")) {
                return false
            }

            if (format == Barcode.FORMAT_CODE_39) {
                val cleaned = cleanBarcodeValue(trimmed, format)
                if (cleaned.length < 3) return false
                // Reject isolated dots or periods common in false text readings
                if (cleaned.startsWith(".") || cleaned.endsWith(".") || cleaned.contains("..")) {
                    return false
                }
            }

            return true
        }

        fun cleanBarcodeValue(raw: String, format: Int): String {
            val trimmed = raw.trim()
            return if (format == Barcode.FORMAT_CODE_39) {
                // Code 39 uses * as start/stop delimiters (e.g. *4642605902* -> 4642605902)
                trimmed.removePrefix("*").removeSuffix("*").trim()
            } else {
                trimmed
            }
        }

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

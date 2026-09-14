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

    // Support all standard 1D and 2D barcode formats
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
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

    // Cooldown filter: only prevents rapid re-trigger for the SAME code
    private var lastDetectedCode: String = ""
    private var lastDetectedTimestamp: Long = 0L

    // Stability candidate for 1D barcodes without built-in checksums (e.g. Code 39)
    // Ensures 100% character/digit accuracy across frames
    private var candidateCode: String = ""
    private var candidateCount: Int = 0
    private var candidateFirstSeenTime: Long = 0L

    fun resetDetectionState() {
        lastDetectedCode = ""
        lastDetectedTimestamp = 0L
        candidateCode = ""
        candidateCount = 0
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // Check if paused or live scan is disabled
        if (isPaused || !autoScanEnabled) {
            imageProxy.close()
            return
        }

        // Process frames smoothly (~12-15 FPS) for responsive scanning
        if (isAnalyzing || (now - lastAnalyzedTimestamp < 75L)) {
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
                // Find first valid barcode
                val validBarcode = barcodes.firstOrNull { barcode ->
                    val raw = barcode.rawValue
                    if (raw.isNullOrBlank()) return@firstOrNull false
                    isValidBarcode(raw, barcode.format)
                }

                if (validBarcode != null) {
                    val raw = validBarcode.rawValue!!.trim()
                    val code = cleanBarcodeValue(raw, validBarcode.format)
                    val formatStr = getFormatName(validBarcode.format)

                    // Formats with built-in checksums/error-correction cannot misread digits:
                    // EAN (mod-10), UPC (mod-10), Code 128 (mod-103), QR (Reed-Solomon), Data Matrix
                    val isChecksummed = (validBarcode.format == Barcode.FORMAT_EAN_13 ||
                                         validBarcode.format == Barcode.FORMAT_EAN_8 ||
                                         validBarcode.format == Barcode.FORMAT_UPC_A ||
                                         validBarcode.format == Barcode.FORMAT_UPC_E ||
                                         validBarcode.format == Barcode.FORMAT_CODE_128 ||
                                         validBarcode.format == Barcode.FORMAT_QR_CODE ||
                                         validBarcode.format == Barcode.FORMAT_DATA_MATRIX)

                    if (isChecksummed) {
                        // Trigger immediately for zero latency
                        triggerIfAllowed(code, formatStr, now)
                    } else {
                        // Non-checksummed 1D barcodes (Code 39, ITF, Codabar):
                        // Require 2 matching readings within 1200ms to eliminate 1-character misreads
                        if (code == candidateCode && (now - candidateFirstSeenTime < 1200L)) {
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
                    if (now - candidateFirstSeenTime > 900L) {
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
        // Prevent duplicate trigger for the exact same code within 1500ms cooldown.
        // Changing to a different barcode triggers immediately with ZERO delay!
        if (code == lastDetectedCode && (now - lastDetectedTimestamp < 1500L)) {
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
         * Validates barcode content to reject accidental text misreads.
         */
        fun isValidBarcode(raw: String, format: Int): Boolean {
            val trimmed = raw.trim()
            if (trimmed.length < 3) return false

            if (format == Barcode.FORMAT_CODE_39) {
                val cleaned = cleanBarcodeValue(trimmed, format)
                if (cleaned.length < 3) return false
                // Reject isolated dots or periods common in false text readings (e.g. "W .605Y02", ".605Y02.")
                if (cleaned.startsWith(".") || cleaned.endsWith(".") ||
                    cleaned.contains("..") || cleaned.contains(" .") || cleaned.contains(". ")) {
                    return false
                }
            }

            return true
        }

        fun cleanBarcodeValue(raw: String, format: Int): String {
            var trimmed = raw.trim()
            if (format == Barcode.FORMAT_CODE_39) {
                // Code 39 uses * as start/stop delimiters (e.g. *4642605902* -> 4642605902)
                trimmed = trimmed.removePrefix("*").removeSuffix("*").trim()
                if (trimmed.startsWith("*")) trimmed = trimmed.removePrefix("*").trim()
                if (trimmed.endsWith("*")) trimmed = trimmed.removeSuffix("*").trim()
            }
            return trimmed
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

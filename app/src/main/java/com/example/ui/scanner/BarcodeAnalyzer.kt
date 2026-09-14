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

    // Configure scanner strictly for standard product and contract 1D/2D barcodes.
    // Exclude CODABAR and CODE_93 which easily misread printed text (like "CCTSMG...") as false barcodes!
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
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
    )

    @Volatile
    private var isAnalyzing = false

    @Volatile
    private var lastAnalyzedTimestamp = 0L

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    var autoScanEnabled: Boolean = true

    // When true, strictly enforce numeric-only barcodes
    @Volatile
    var numericOnlyMode: Boolean = false

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
                // Filter valid barcodes
                val validList = barcodes.filter { barcode ->
                    val raw = barcode.rawValue
                    if (raw.isNullOrBlank()) false else isValidBarcode(raw, barcode.format)
                }

                // Sorting strategy:
                // 1. If numericOnlyMode is ON, only allow barcodes that resolve to pure digits.
                // 2. Prioritize barcodes that resolve to pure numbers over strings containing letters.
                val chosenBarcode = if (numericOnlyMode) {
                    validList.firstOrNull { b ->
                        val code = cleanBarcodeValue(b.rawValue ?: "", b.format)
                        code.isNotEmpty() && code.all { it.isDigit() }
                    }
                } else {
                    validList.sortedByDescending { b ->
                        val code = cleanBarcodeValue(b.rawValue ?: "", b.format)
                        if (code.isNotEmpty() && code.all { it.isDigit() }) 1 else 0
                    }.firstOrNull()
                }

                if (chosenBarcode != null) {
                    val raw = chosenBarcode.rawValue!!.trim()
                    val code = cleanBarcodeValue(raw, chosenBarcode.format)
                    val formatStr = getFormatName(chosenBarcode.format)

                    // Formats with built-in checksums/error-correction cannot misread digits:
                    // EAN (mod-10), UPC (mod-10), Code 128 (mod-103), QR (Reed-Solomon), Data Matrix
                    val isChecksummed = (chosenBarcode.format == Barcode.FORMAT_EAN_13 ||
                                         chosenBarcode.format == Barcode.FORMAT_EAN_8 ||
                                         chosenBarcode.format == Barcode.FORMAT_UPC_A ||
                                         chosenBarcode.format == Barcode.FORMAT_UPC_E ||
                                         chosenBarcode.format == Barcode.FORMAT_CODE_128 ||
                                         chosenBarcode.format == Barcode.FORMAT_QR_CODE ||
                                         chosenBarcode.format == Barcode.FORMAT_DATA_MATRIX)

                    if (isChecksummed) {
                        // Trigger immediately for zero latency
                        triggerIfAllowed(code, formatStr, now)
                    } else {
                        // Non-checksummed 1D barcodes (Code 39, ITF):
                        // Require 2 matching readings within 1200ms to eliminate single-character misreads
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

            // Exclude CODABAR and CODE_93 which easily misread text as barcodes
            if (format == Barcode.FORMAT_CODABAR || format == Barcode.FORMAT_CODE_93) {
                return false
            }

            val cleaned = cleanBarcodeValue(trimmed, format)
            if (cleaned.length < 3) return false

            // Reject strings that are pure alphabetical text (like "KONTRAK", "JATENG")
            if (cleaned.all { it.isLetter() }) {
                return false
            }

            // Reject text artifacts like "..", " .", ". "
            if (cleaned.startsWith(".") || cleaned.endsWith(".") ||
                cleaned.contains("..") || cleaned.contains(" .") || cleaned.contains(". ")) {
                return false
            }

            return true
        }

        fun cleanBarcodeValue(raw: String, format: Int): String {
            var trimmed = raw.trim()
            // Clean Code 39 and any barcode starting/ending with '*' (e.g. *4762604839* -> 4762604839)
            if (trimmed.startsWith("*") || trimmed.endsWith("*") || format == Barcode.FORMAT_CODE_39) {
                trimmed = trimmed.removePrefix("*").removeSuffix("*").trim()
                if (trimmed.startsWith("*")) trimmed = trimmed.removePrefix("*").trim()
                if (trimmed.endsWith("*")) trimmed = trimmed.removeSuffix("*").trim()
            }

            // Handle barcodes with spaces between numbers (e.g. "* 4 7 6 2 6 0 4 8 3 9 *")
            val noSpaces = trimmed.replace(" ", "").replace("\t", "")
            if (noSpaces.all { it.isDigit() || it == '-' || it == '_' } && noSpaces.any { it.isDigit() }) {
                trimmed = noSpaces
            }

            // Auto-correct near-numeric misreads (e.g. 'O' instead of '0', 'S' instead of '5')
            trimmed = autoCorrectNearNumericCode(trimmed)
            return trimmed
        }

        /**
         * Inovasi Otomatis:
         * Mengoreksi huruf yang tertukar karena refleksi kamera atau garis tipis
         * ketika barcode mayoritas adalah angka (>= 65% angka).
         */
        fun autoCorrectNearNumericCode(code: String): String {
            if (code.length < 3) return code
            val digitsCount = code.count { it.isDigit() }
            val totalCount = code.length

            if (digitsCount.toFloat() / totalCount >= 0.65f) {
                val sb = StringBuilder(totalCount)
                for (ch in code) {
                    val corrected = when (ch) {
                        'O', 'o', 'Q', 'D' -> '0'
                        'I', 'i', 'l', 'L', '|' -> '1'
                        'Z', 'z' -> '2'
                        'E', 'e' -> '3'
                        'A', 'a' -> '4'
                        'S', 's' -> '5'
                        'G', 'b' -> '6'
                        'T' -> '7'
                        'B' -> '8'
                        else -> ch
                    }
                    sb.append(corrected)
                }
                return sb.toString()
            }
            return code
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

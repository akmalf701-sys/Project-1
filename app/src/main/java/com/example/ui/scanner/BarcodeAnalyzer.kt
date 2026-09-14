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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

enum class ScannerMode {
    BARCODE,        // Scan Barcode Garis 1D & QR Code
    OCR_CONTRACT    // Scan Teks / OCR Nomor Kontrak (misal CCTSMG26020583)
}

class BarcodeAnalyzer(
    private val onBarcodeDetected: (code: String, format: String) -> Unit,
    private val onOcrTextCandidates: ((List<String>) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    // Configure scanner for all supported 1D and 2D barcode formats
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    // ML Kit Text Recognition for OCR Contract Number
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    var scannerMode: ScannerMode = ScannerMode.BARCODE

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

    // Stability candidate for 1D barcodes
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

        // Throttle frame analysis (~5 FPS) for responsive scanning with zero lag
        if (isAnalyzing || (now - lastAnalyzedTimestamp < 200L)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            isAnalyzing = true
            lastAnalyzedTimestamp = now
            try {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                if (scannerMode == ScannerMode.BARCODE) {
                    analyzeBarcode(image, imageProxy, now)
                } else {
                    analyzeOcrText(image, imageProxy, now)
                }
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
                val validBarcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (validBarcode != null) {
                    val raw = validBarcode.rawValue!!.trim()
                    // Strip asterisks for Code 39 (e.g. *4642605902* -> 4642605902)
                    val code = cleanBarcodeValue(raw, validBarcode.format)
                    val formatStr = getFormatName(validBarcode.format)

                    val isLinear1D = when (validBarcode.format) {
                        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODABAR,
                        Barcode.FORMAT_ITF -> true
                        else -> false
                    }

                    if (!isLinear1D) {
                        // 2D QR / DataMatrix has built-in ECC checksums
                        triggerIfAllowed(code, formatStr, now)
                    } else {
                        // 1D Barcode: check stability
                        val isSameCandidate = (code == candidateCode) && (now - candidateFirstSeenTime < 1000L)
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
                            // If code has good length (e.g. >= 8 chars like in contract barcodes), also allow fast single-frame match if cooldown passed
                            if (code.length >= 8 && (code != lastDetectedCode || now - lastDetectedTimestamp > 2500L)) {
                                triggerIfAllowed(code, formatStr, now)
                                candidateCode = ""
                                candidateCount = 0
                            }
                        }
                    }
                } else {
                    if (now - candidateFirstSeenTime > 800L) {
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

    private fun analyzeOcrText(image: InputImage, imageProxy: ImageProxy, now: Long) {
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val candidateList = mutableListOf<String>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        // Look for contract number candidates (e.g. CCTSMG26020583, U06326001, 4642605902)
                        // Ignore short noise words (< 4 chars)
                        val words = text.split("\\s+".toRegex())
                        for (w in words) {
                            val cleanWord = w.replace("[^A-Za-z0-9]".toRegex(), "")
                            if (cleanWord.length >= 6) {
                                candidateList.add(cleanWord)
                            }
                        }
                    }
                }

                val distinctCandidates = candidateList.distinct()
                onOcrTextCandidates?.invoke(distinctCandidates)

                // If candidate looks strongly like contract number (e.g. starts with letters followed by digits like CCTSMG26020583)
                val contractCandidate = distinctCandidates.firstOrNull { candidate ->
                    // Matches alphanumeric contract format like CCTSMG26020583
                    val hasLetter = candidate.any { it.isLetter() }
                    val hasDigit = candidate.any { it.isDigit() }
                    hasLetter && hasDigit && candidate.length in 8..24
                } ?: distinctCandidates.firstOrNull { it.length in 8..24 }

                if (contractCandidate != null) {
                    triggerIfAllowed(contractCandidate, "NOMOR_KONTRAK", now)
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
        try {
            textRecognizer.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun cleanBarcodeValue(raw: String, format: Int): String {
            val trimmed = raw.trim()
            return if (format == Barcode.FORMAT_CODE_39) {
                // Code 39 often embeds start/stop asterisks like *4642605902*
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

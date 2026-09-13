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

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // Throttle frame analysis to prevent CPU overload and SELinux audit rate limiting
        if (isAnalyzing || (now - lastAnalyzedTimestamp < 200L)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            isAnalyzing = true
            lastAnalyzedTimestamp = now
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            val formatStr = getFormatName(barcode.format)
                            onBarcodeDetected(rawValue, formatStr)
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

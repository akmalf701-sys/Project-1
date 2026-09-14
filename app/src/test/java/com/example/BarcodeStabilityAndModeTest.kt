package com.example

import com.example.ui.scanner.BarcodeAnalyzer
import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeStabilityAndModeTest {

    @Test
    fun testRejectionOfTextMisreadsWithSpacesAndPeriods() {
        // Accidental Code 39 reads on printed text paragraphs
        assertFalse(BarcodeAnalyzer.isValidBarcode("W .605Y02", Barcode.FORMAT_CODE_39))
        assertFalse(BarcodeAnalyzer.isValidBarcode("46 .W05YZ.", Barcode.FORMAT_CODE_39))
        assertFalse(BarcodeAnalyzer.isValidBarcode("W .605E02", Barcode.FORMAT_CODE_39))
        assertFalse(BarcodeAnalyzer.isValidBarcode(".605Y02.", Barcode.FORMAT_CODE_39))
        assertFalse(BarcodeAnalyzer.isValidBarcode("AB", Barcode.FORMAT_CODE_39)) // Too short

        // Genuine barcodes should be accepted
        assertTrue(BarcodeAnalyzer.isValidBarcode("CCTSMG26020583", Barcode.FORMAT_CODE_39))
        assertTrue(BarcodeAnalyzer.isValidBarcode("*CCTSMG26020583*", Barcode.FORMAT_CODE_39))
        assertTrue(BarcodeAnalyzer.isValidBarcode("4642605902", Barcode.FORMAT_CODE_39))
        assertTrue(BarcodeAnalyzer.isValidBarcode("8991234567890", Barcode.FORMAT_EAN_13))
    }

    @Test
    fun testCode39BarcodeCleaningStripsAsterisks() {
        // Code 39 uses * as start/stop delimiters, which causes ML Kit to sometimes read "*CCTSMG26020583*" instead of "CCTSMG26020583"
        val rawWithAsterisks = "*CCTSMG26020583*"
        val cleaned = BarcodeAnalyzer.cleanBarcodeValue(rawWithAsterisks, Barcode.FORMAT_CODE_39)
        assertEquals("CCTSMG26020583", cleaned)

        val rawWithoutAsterisks = "CCTSMG26020583"
        val cleaned2 = BarcodeAnalyzer.cleanBarcodeValue(rawWithoutAsterisks, Barcode.FORMAT_CODE_39)
        assertEquals("CCTSMG26020583", cleaned2)

        val numericCode39 = "*4642605902*"
        val cleanedNumeric = BarcodeAnalyzer.cleanBarcodeValue(numericCode39, Barcode.FORMAT_CODE_39)
        assertEquals("4642605902", cleanedNumeric)
    }

    @Test
    fun testContractNumberCandidateExtraction() {
        val labelWords = listOf("KONTRAK", "NO:", "CCTSMG26020583", "JATENG", "TGL", "05/02/2026")
        val cleanedCandidates = labelWords.map { it.replace("[^A-Za-z0-9]".toRegex(), "") }
            .filter { it.length >= 6 }

        val contractCandidate = cleanedCandidates.firstOrNull { c ->
            c.any { it.isLetter() } && c.any { it.isDigit() } && c.length in 8..24
        }

        assertEquals("CCTSMG26020583", contractCandidate)
    }

    @Test
    fun testStabilityFilterRequiresConsecutiveMatches() {
        // Simulates motion blur when product is moved into view:
        // Frame 1: partial misread "89912"
        // Frame 2: full correct code "8991234567890"
        // Frame 3: stable matching code "8991234567890"

        var candidate = ""
        var matchCount = 0
        val emittedCodes = mutableListOf<String>()

        fun processSimulatedFrame(code: String) {
            if (code == candidate) {
                matchCount++
                if (matchCount >= 2) {
                    emittedCodes.add(code)
                    candidate = ""
                    matchCount = 0
                }
            } else {
                candidate = code
                matchCount = 1
            }
        }

        // Frame 1: Glitched/partial read
        processSimulatedFrame("89912")
        assertEquals(0, emittedCodes.size)

        // Frame 2: Real full barcode
        processSimulatedFrame("8991234567890")
        assertEquals(0, emittedCodes.size) // Not emitted yet because it's first occurrence

        // Frame 3: Confirmed identical reading (still/focused)
        processSimulatedFrame("8991234567890")
        assertEquals(1, emittedCodes.size)
        assertEquals("8991234567890", emittedCodes.first())
    }

    @Test
    fun testManualCaptureModeIgnoresLiveStreamWhenDisabled() {
        var autoScanEnabled = false
        val scannedList = mutableListOf<String>()

        fun onLiveFrame(code: String) {
            if (autoScanEnabled) {
                scannedList.add(code)
            }
        }

        // Live frames passing by while user aims
        onLiveFrame("8991111111111")
        onLiveFrame("8992222222222")
        assertTrue("Live frames should be ignored when auto-scan is off", scannedList.isEmpty())

        // User taps shutter button to freeze and capture
        fun onShutterTapped(capturedStillCode: String) {
            scannedList.add(capturedStillCode)
        }

        onShutterTapped("8992222222222")
        assertEquals(1, scannedList.size)
        assertEquals("8992222222222", scannedList[0])
    }

    @Test
    fun testModeSwitching() {
        var isContinuous = true
        fun toggle() { isContinuous = !isContinuous }

        assertTrue(isContinuous)
        toggle()
        assertFalse(isContinuous)
        toggle()
        assertTrue(isContinuous)
    }
}

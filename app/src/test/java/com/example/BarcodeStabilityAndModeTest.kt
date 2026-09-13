package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeStabilityAndModeTest {

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

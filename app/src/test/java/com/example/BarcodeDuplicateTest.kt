package com.example

import com.example.data.BarcodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeDuplicateTest {

    @Test
    fun testDuplicateDetectionInExistingCodes() {
        val registeredItems = listOf(
            BarcodeEntity(id = 1, code = "8991234567890", format = "EAN_13", quantity = 1),
            BarcodeEntity(id = 2, code = "BRG-002", format = "CODE_128", quantity = 1)
        )
        val existingCodes = registeredItems.map { it.code }.toSet()

        // Existing barcode is flagged as duplicate
        assertTrue(existingCodes.contains("8991234567890"))
        assertTrue(existingCodes.contains("BRG-002"))

        // New barcode is not a duplicate
        assertFalse(existingCodes.contains("8999999999999"))
        assertFalse(existingCodes.contains("BRG-003"))
    }

    @Test
    fun testUniqueCodeListExcludesDuplicates() {
        val codesWithDuplicates = listOf(
            "8991234567890",
            "8991234567890",
            "BRG-002",
            "8991234567890"
        )
        val uniqueCodes = codesWithDuplicates.distinct()
        assertEquals(2, uniqueCodes.size)
        assertEquals(listOf("8991234567890", "BRG-002"), uniqueCodes)
    }
}

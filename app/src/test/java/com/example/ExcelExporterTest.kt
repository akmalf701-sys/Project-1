package com.example

import com.example.data.BarcodeEntity
import com.example.util.ExcelExporter
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcelExporterTest {

    @Test
    fun testCsvGenerationWithBomAndSemicolon() {
        val items = listOf(
            BarcodeEntity(
                id = 1,
                code = "8991234567890",
                format = "EAN_13",
                title = "Indomie Goreng",
                quantity = 5,
                note = "Dus 1"
            )
        )

        val csv = ExcelExporter.generateCsv(items, delimiter = ";")
        // Check for UTF-8 BOM
        assertTrue(csv.startsWith("\uFEFF"))
        // Check single column header
        assertTrue(csv.contains("Nomor Kontrak"))
        // Check content row contains the code
        assertTrue(csv.contains("8991234567890"))
    }

    @Test
    fun testExcelXmlGeneration() {
        val items = listOf(
            BarcodeEntity(
                id = 1,
                code = "ABC-99901",
                format = "CODE_39",
                title = "",
                quantity = 1,
                note = ""
            )
        )

        val xml = ExcelExporter.generateExcelXml(items)
        assertTrue(xml.contains("urn:schemas-microsoft-com:office:spreadsheet"))
        assertTrue(xml.contains("Nomor Kontrak"))
        assertTrue(xml.contains("ABC-99901"))
    }
}

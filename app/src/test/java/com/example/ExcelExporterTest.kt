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
        // Check headers
        assertTrue(csv.contains("Kode Barcode;Format Barcode;Nama / Deskripsi;Jumlah (Qty)"))
        // Check content row
        assertTrue(csv.contains("8991234567890;EAN_13;Indomie Goreng;5"))
    }

    @Test
    fun testExcelXmlGeneration() {
        val items = listOf(
            BarcodeEntity(
                id = 1,
                code = "ABC-99901",
                format = "CODE_128",
                title = "Barang Logistik",
                quantity = 10,
                note = "Gudang B"
            )
        )

        val xml = ExcelExporter.generateExcelXml(items)
        assertTrue(xml.contains("urn:schemas-microsoft-com:office:spreadsheet"))
        assertTrue(xml.contains("ABC-99901"))
        assertTrue(xml.contains("Barang Logistik"))
        assertTrue(xml.contains("<Data ss:Type=\"Number\">10</Data>"))
    }
}

package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.BarcodeEntity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExcelExporter {

    enum class ExportFormat(val extension: String, val mimeType: String, val displayName: String) {
        EXCEL_XML("xls", "application/vnd.ms-excel", "Excel Spreadsheet (.xls)"),
        CSV_SEMICOLON("csv", "text/csv", "CSV Excel Indonesia (Titik Koma ;)"),
        CSV_COMMA("csv", "text/csv", "CSV Standar (Koma ,)")
    }

    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fileTimestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    fun getDefaultFileName(format: ExportFormat): String {
        val stamp = fileTimestampFormatter.format(Date())
        return "Barcode_Export_$stamp.${format.extension}"
    }

    /**
     * Generate CSV content with UTF-8 BOM so Microsoft Excel recognizes UTF-8 encoding properly.
     */
    fun generateCsv(items: List<BarcodeEntity>, delimiter: String = ";"): String {
        val sb = StringBuilder()
        // UTF-8 BOM
        sb.append('\uFEFF')

        // Header
        val headers = listOf(
            "No",
            "Kode Barcode",
            "Format Barcode",
            "Nama / Deskripsi",
            "Jumlah (Qty)",
            "Tanggal Scan",
            "Waktu Scan",
            "Catatan"
        )
        sb.append(headers.joinToString(delimiter) { escapeCsv(it, delimiter) }).append("\r\n")

        // Rows
        items.forEachIndexed { index, item ->
            val date = dateFormatter.format(Date(item.timestamp))
            val time = timeFormatter.format(Date(item.timestamp))
            val row = listOf(
                (index + 1).toString(),
                item.code,
                item.format,
                item.title,
                item.quantity.toString(),
                date,
                time,
                item.note
            )
            sb.append(row.joinToString(delimiter) { escapeCsv(it, delimiter) }).append("\r\n")
        }

        return sb.toString()
    }

    private fun escapeCsv(value: String, delimiter: String): String {
        val containsSpecial = value.contains(delimiter) ||
                value.contains("\"") ||
                value.contains("\n") ||
                value.contains("\r")
        return if (containsSpecial) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    /**
     * Generates native Microsoft Excel XML Spreadsheet format (.xls)
     * Opens directly in Microsoft Excel, WPS Office, and Google Sheets with formatted headers and column widths.
     */
    fun generateExcelXml(items: List<BarcodeEntity>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<?mso-application progid=\"Excel.Sheet\"?>\n")
        sb.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"\n")
        sb.append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"\n")
        sb.append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"\n")
        sb.append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"\n")
        sb.append(" xmlns:html=\"http://www.w3.org/TR/REC-html40\">\n")

        // Styles
        sb.append(" <Styles>\n")
        sb.append("  <Style ss:ID=\"Default\" ss:Name=\"Normal\">\n")
        sb.append("   <Alignment ss:Vertical=\"Center\"/>\n")
        sb.append("   <Borders/>\n")
        sb.append("   <Font ss:FontName=\"Segoe UI\" ss:Size=\"10\" ss:Color=\"#000000\"/>\n")
        sb.append("  </Style>\n")

        // Header style (Excel emerald green with bold white text)
        sb.append("  <Style ss:ID=\"HeaderStyle\">\n")
        sb.append("   <Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\"/>\n")
        sb.append("   <Borders>\n")
        sb.append("    <Border ss:Position=\"Bottom\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"#0F5132\"/>\n")
        sb.append("   </Borders>\n")
        sb.append("   <Font ss:FontName=\"Segoe UI\" ss:Size=\"11\" ss:Color=\"#FFFFFF\" ss:Bold=\"1\"/>\n")
        sb.append("   <Interior ss:Color=\"#0F766E\" ss:Pattern=\"Solid\"/>\n")
        sb.append("  </Style>\n")

        // Text centered
        sb.append("  <Style ss:ID=\"CenteredText\">\n")
        sb.append("   <Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\"/>\n")
        sb.append("  </Style>\n")

        // Number right aligned
        sb.append("  <Style ss:ID=\"NumberStyle\">\n")
        sb.append("   <Alignment ss:Horizontal=\"Right\" ss:Vertical=\"Center\"/>\n")
        sb.append("  </Style>\n")

        // Barcode text style (monospace / preserved leading zeros)
        sb.append("  <Style ss:ID=\"BarcodeStyle\">\n")
        sb.append("   <Alignment ss:Horizontal=\"Left\" ss:Vertical=\"Center\"/>\n")
        sb.append("   <NumberFormat ss:Format=\"@\"/>\n")
        sb.append("  </Style>\n")

        sb.append(" </Styles>\n")

        // Worksheet
        sb.append(" <Worksheet ss:Name=\"Daftar Barcode\">\n")
        sb.append("  <Table ss:DefaultRowHeight=\"20\">\n")

        // Column widths
        sb.append("   <Column ss:Width=\"40\"/>\n")   // No
        sb.append("   <Column ss:Width=\"140\"/>\n")  // Barcode
        sb.append("   <Column ss:Width=\"90\"/>\n")   // Format
        sb.append("   <Column ss:Width=\"160\"/>\n")  // Nama
        sb.append("   <Column ss:Width=\"60\"/>\n")   // Qty
        sb.append("   <Column ss:Width=\"90\"/>\n")   // Tanggal
        sb.append("   <Column ss:Width=\"80\"/>\n")   // Waktu
        sb.append("   <Column ss:Width=\"150\"/>\n")  // Catatan

        // Header Row
        sb.append("   <Row ss:Height=\"26\" ss:StyleID=\"HeaderStyle\">\n")
        listOf("No", "Kode Barcode", "Format", "Nama / Deskripsi", "Qty", "Tanggal", "Waktu", "Catatan").forEach { header ->
            sb.append("    <Cell><Data ss:Type=\"String\">${escapeXml(header)}</Data></Cell>\n")
        }
        sb.append("   </Row>\n")

        // Data Rows
        items.forEachIndexed { index, item ->
            val date = dateFormatter.format(Date(item.timestamp))
            val time = timeFormatter.format(Date(item.timestamp))
            sb.append("   <Row>\n")
            sb.append("    <Cell ss:StyleID=\"CenteredText\"><Data ss:Type=\"Number\">${index + 1}</Data></Cell>\n")
            sb.append("    <Cell ss:StyleID=\"BarcodeStyle\"><Data ss:Type=\"String\">${escapeXml(item.code)}</Data></Cell>\n")
            sb.append("    <Cell ss:StyleID=\"CenteredText\"><Data ss:Type=\"String\">${escapeXml(item.format)}</Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\">${escapeXml(item.title)}</Data></Cell>\n")
            sb.append("    <Cell ss:StyleID=\"NumberStyle\"><Data ss:Type=\"Number\">${item.quantity}</Data></Cell>\n")
            sb.append("    <Cell ss:StyleID=\"CenteredText\"><Data ss:Type=\"String\">$date</Data></Cell>\n")
            sb.append("    <Cell ss:StyleID=\"CenteredText\"><Data ss:Type=\"String\">$time</Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\">${escapeXml(item.note)}</Data></Cell>\n")
            sb.append("   </Row>\n")
        }

        // Summary Total Row if items not empty
        if (items.isNotEmpty()) {
            val totalQty = items.sumOf { it.quantity }
            sb.append("   <Row ss:Height=\"22\">\n")
            sb.append("    <Cell ss:StyleID=\"CenteredText\"><Data ss:Type=\"String\">TOTAL</Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\">${items.size} item barcode</Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\"></Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\"></Data></Cell>\n")
            sb.append("    <Cell ss:StyleID=\"NumberStyle\"><Data ss:Type=\"Number\">$totalQty</Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\"></Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\"></Data></Cell>\n")
            sb.append("    <Cell><Data ss:Type=\"String\"></Data></Cell>\n")
            sb.append("   </Row>\n")
        }

        sb.append("  </Table>\n")
        sb.append(" </Worksheet>\n")
        sb.append("</Workbook>\n")
        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Writes content to an OutputStream (e.g. from SAF CreateDocument).
     */
    fun writeToStream(content: String, outputStream: OutputStream) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
            writer.flush()
        }
    }

    /**
     * Saves exported content to a temporary cache file and returns a shareable Uri via FileProvider.
     */
    fun createShareableFile(context: Context, items: List<BarcodeEntity>, format: ExportFormat): Uri {
        val fileName = getDefaultFileName(format)
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
        val file = File(exportDir, fileName)

        val content = when (format) {
            ExportFormat.EXCEL_XML -> generateExcelXml(items)
            ExportFormat.CSV_SEMICOLON -> generateCsv(items, delimiter = ";")
            ExportFormat.CSV_COMMA -> generateCsv(items, delimiter = ",")
        }

        FileOutputStream(file).use { out ->
            writeToStream(content, out)
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Triggers the Android Share Sheet to open or send the file directly to Excel, WhatsApp, Email, Drive, etc.
     */
    fun shareFile(context: Context, items: List<BarcodeEntity>, format: ExportFormat) {
        val uri = createShareableFile(context, items, format)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Hasil Scan Barcode (${items.size} data)")
            putExtra(Intent.EXTRA_TEXT, "Berikut adalah hasil scan barcode (${items.size} item) yang diekspor dari aplikasi Barcode to Excel.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Ekspor / Buka di Excel")
        context.startActivity(chooser)
    }
}

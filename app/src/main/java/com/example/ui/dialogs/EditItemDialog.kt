package com.example.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BarcodeEntity

@Composable
fun EditItemDialog(
    item: BarcodeEntity,
    onDismiss: () -> Unit,
    onSave: (BarcodeEntity) -> Unit,
    onDelete: (BarcodeEntity) -> Unit,
    preventDuplicates: Boolean = false,
    existingCodes: Set<String> = emptySet()
) {
    val context = LocalContext.current
    var codeText by remember { mutableStateOf(item.code) }
    var title by remember { mutableStateOf(item.title) }
    var note by remember { mutableStateOf(item.note) }
    var quantityText by remember { mutableStateOf(item.quantity.toString()) }

    val trimmedCode = codeText.trim()
    val isDuplicate = remember(trimmedCode, existingCodes, preventDuplicates) {
        preventDuplicates && trimmedCode.isNotEmpty() && trimmedCode != item.code && existingCodes.contains(trimmedCode)
    }
    val isValidCode = trimmedCode.isNotEmpty() && !isDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = "Edit Data Barcode",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Editable Barcode Number input (User requested: nomor bisa diedit kalau salah baca)
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it },
                    label = { Text("Nomor / Kode Barcode") },
                    placeholder = { Text("Ketik atau perbaiki nomor barcode...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = null,
                            tint = if (isDuplicate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (codeText.isNotEmpty()) {
                                IconButton(
                                    onClick = { codeText = "" },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Hapus teks",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Barcode", codeText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Nomor barcode disalin!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Salin Kode",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isDuplicate) {
                                    "⚠️ Kode ini sudah ada di daftar Excel!"
                                } else if (codeText.isBlank()) {
                                    "⚠️ Nomor tidak boleh kosong"
                                } else {
                                    "Format: ${item.format} • Edit jika salah baca"
                                },
                                color = if (isDuplicate || codeText.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${codeText.length} digit",
                                fontWeight = FontWeight.Bold,
                                color = if (codeText.length == 10) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    },
                    isError = codeText.isBlank() || isDuplicate,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_code_input")
                )

                // Name / Title input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nama / Deskripsi Barang") },
                    placeholder = { Text("Contoh: Barang A / Box 12") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_title_input")
                )

                // Quantity counter
                Column {
                    Text(
                        text = "Jumlah (Qty):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                val current = quantityText.toIntOrNull() ?: 1
                                if (current > 1) {
                                    quantityText = (current - 1).toString()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Kurang")
                        }

                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { newText ->
                                if (newText.isEmpty() || newText.all { it.isDigit() }) {
                                    quantityText = newText
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("edit_quantity_input")
                        )

                        FilledTonalIconButton(
                            onClick = {
                                val current = quantityText.toIntOrNull() ?: 1
                                quantityText = (current + 1).toString()
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Tambah")
                        }
                    }
                }

                // Note input
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan / Keterangan") },
                    placeholder = { Text("Opsional: rak, status, keterangan") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_note_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalQty = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    onSave(
                        item.copy(
                            code = trimmedCode,
                            title = title.trim(),
                            quantity = finalQty,
                            note = note.trim()
                        )
                    )
                },
                enabled = isValidCode,
                modifier = Modifier.testTag("save_edit_button")
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onDelete(item) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("delete_item_button")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Hapus",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Hapus")
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Batal")
                }
            }
        }
    )
}

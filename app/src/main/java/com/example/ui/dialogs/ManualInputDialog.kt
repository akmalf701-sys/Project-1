package com.example.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ManualInputDialog(
    onDismiss: () -> Unit,
    onSubmit: (code: String, title: String, qty: Int, note: String) -> Unit,
    preventDuplicates: Boolean = true,
    existingCodes: Set<String> = emptySet()
) {
    var code by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var showError by remember { mutableStateOf(false) }
    var duplicateError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Input Barcode Manual",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val isCodeDuplicate = preventDuplicates && existingCodes.contains(code.trim())

                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        showError = false
                        duplicateError = false
                    },
                    label = { Text("Kode Barcode *") },
                    placeholder = { Text("Ketik angka atau karakter barcode") },
                    isError = showError || duplicateError || isCodeDuplicate,
                    supportingText = {
                        when {
                            showError -> Text("Kode barcode tidak boleh kosong", color = MaterialTheme.colorScheme.error)
                            duplicateError || isCodeDuplicate -> Text("⚠️ Kode barcode ini sudah terdaftar di Excel! (Duplikat)", color = MaterialTheme.colorScheme.error)
                            else -> null
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_code_input")
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nama / Deskripsi (Opsional)") },
                    placeholder = { Text("Contoh: Barang Manual 01") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_title_input")
                )

                // Quantity
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
                                .testTag("manual_qty_input")
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

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan (Opsional)") },
                    placeholder = { Text("Catatan tambahan") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_note_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = code.trim()
                    if (trimmed.isEmpty()) {
                        showError = true
                    } else if (preventDuplicates && existingCodes.contains(trimmed)) {
                        duplicateError = true
                    } else {
                        val qty = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        onSubmit(trimmed, title.trim(), qty, note.trim())
                    }
                },
                modifier = Modifier.testTag("submit_manual_barcode")
            ) {
                Text("Tambah")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

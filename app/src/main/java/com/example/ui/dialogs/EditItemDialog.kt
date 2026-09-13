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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import com.example.data.BarcodeEntity

@Composable
fun EditItemDialog(
    item: BarcodeEntity,
    onDismiss: () -> Unit,
    onSave: (BarcodeEntity) -> Unit,
    onDelete: (BarcodeEntity) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(item.title) }
    var note by remember { mutableStateOf(item.note) }
    var quantityText by remember { mutableStateOf(item.quantity.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Detail & Edit Barcode",
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
                // Barcode code display with copy
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Format: ${item.format}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = item.code,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Barcode", item.code)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Kode barcode disalin!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Salin Kode",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

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
                            title = title.trim(),
                            quantity = finalQty,
                            note = note.trim()
                        )
                    )
                },
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

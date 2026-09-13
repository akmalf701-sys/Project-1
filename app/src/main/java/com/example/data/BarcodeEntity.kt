package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scanned_barcodes")
data class BarcodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val code: String,
    val format: String = "BARCODE",
    val title: String = "",
    val quantity: Int = 1,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

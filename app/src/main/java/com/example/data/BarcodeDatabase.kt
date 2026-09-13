package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BarcodeEntity::class], version = 1, exportSchema = false)
abstract class BarcodeDatabase : RoomDatabase() {
    abstract fun barcodeDao(): BarcodeDao

    companion object {
        @Volatile
        private var INSTANCE: BarcodeDatabase? = null

        fun getDatabase(context: Context): BarcodeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BarcodeDatabase::class.java,
                    "barcode_scanner.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

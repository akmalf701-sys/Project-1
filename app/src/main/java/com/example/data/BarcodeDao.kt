package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM scanned_barcodes ORDER BY timestamp DESC")
    fun getAll(): Flow<List<BarcodeEntity>>

    @Query("SELECT * FROM scanned_barcodes WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): BarcodeEntity?

    @Query("SELECT * FROM scanned_barcodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BarcodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BarcodeEntity): Long

    @Update
    suspend fun update(item: BarcodeEntity)

    @Delete
    suspend fun delete(item: BarcodeEntity)

    @Query("DELETE FROM scanned_barcodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM scanned_barcodes")
    suspend fun deleteAll()

    @Query("UPDATE scanned_barcodes SET quantity = :quantity WHERE id = :id")
    suspend fun updateQuantity(id: Long, quantity: Int)

    @Query("SELECT COUNT(*) FROM scanned_barcodes")
    fun getCount(): Flow<Int>
}

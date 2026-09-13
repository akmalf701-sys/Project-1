package com.example.data

import kotlinx.coroutines.flow.Flow

class BarcodeRepository(private val dao: BarcodeDao) {
    val allItems: Flow<List<BarcodeEntity>> = dao.getAll()
    val itemCount: Flow<Int> = dao.getCount()

    suspend fun findByCode(code: String): BarcodeEntity? = dao.findByCode(code)

    suspend fun insert(item: BarcodeEntity): Long = dao.insert(item)

    suspend fun update(item: BarcodeEntity) = dao.update(item)

    suspend fun updateQuantity(id: Long, qty: Int) = dao.updateQuantity(id, qty)

    suspend fun delete(item: BarcodeEntity) = dao.delete(item)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}

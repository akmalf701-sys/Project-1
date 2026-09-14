package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.BarcodeDao
import com.example.data.BarcodeDatabase
import com.example.data.BarcodeEntity
import com.example.data.BarcodeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomBarcodePersistenceTest {

    private lateinit var database: BarcodeDatabase
    private lateinit var dao: BarcodeDao
    private lateinit var repository: BarcodeRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BarcodeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.barcodeDao()
        repository = BarcodeRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSaveAndRetrieveScannedBarcodes() = runBlocking {
        val item1 = BarcodeEntity(
            code = "8992761123456",
            format = "EAN_13",
            title = "Produk A",
            quantity = 2
        )
        val item2 = BarcodeEntity(
            code = "4762604839",
            format = "CODE_39",
            title = "Kontrak B",
            quantity = 1
        )

        val id1 = repository.insert(item1)
        val id2 = repository.insert(item2)

        val retrieved1 = repository.findByCode("8992761123456")
        assertNotNull(retrieved1)
        assertEquals("8992761123456", retrieved1?.code)
        assertEquals(2, retrieved1?.quantity)

        val all = repository.allItems.first()
        assertEquals(2, all.size)
        assertEquals(2, repository.itemCount.first())
    }

    @Test
    fun testUpdateQuantityAndDuplicateManagement() = runBlocking {
        val item = BarcodeEntity(
            code = "1234567890",
            format = "CODE_128",
            quantity = 1
        )
        val id = repository.insert(item)

        repository.updateQuantity(id, 5)
        val updated = repository.findByCode("1234567890")
        assertEquals(5, updated?.quantity)

        repository.deleteById(id)
        assertNull(repository.findByCode("1234567890"))
        assertEquals(0, repository.itemCount.first())
    }
}

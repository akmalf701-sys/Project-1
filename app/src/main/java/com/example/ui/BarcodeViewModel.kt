package com.example.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.BarcodeEntity
import com.example.data.BarcodeRepository
import com.example.ui.scanner.BarcodeAnalyzer
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BarcodeViewModel(
    private val repository: BarcodeRepository,
    private val appContext: Context
) : ViewModel() {

    // All scanned items from database
    val allItems: StateFlow<List<BarcodeEntity>> = repository.allItems
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Search query for filtering the scanned list
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // Filtered items based on search query
    val filteredItems: StateFlow<List<BarcodeEntity>> = combine(allItems, _searchQuery) { items, query ->
        if (query.isBlank()) {
            items
        } else {
            val lower = query.trim().lowercase()
            items.filter {
                it.code.lowercase().contains(lower) ||
                        it.title.lowercase().contains(lower) ||
                        it.note.lowercase().contains(lower) ||
                        it.format.lowercase().contains(lower)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Camera and scanner states
    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn = _isTorchOn.asStateFlow()

    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera = _useFrontCamera.asStateFlow()

    private val _isContinuousMode = MutableStateFlow(true)
    val isContinuousMode = _isContinuousMode.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled = _hapticsEnabled.asStateFlow()

    // Event notifications for UI (e.g. snackbar or dialog when item scanned)
    private val _scanEvent = MutableSharedFlow<BarcodeEntity>()
    val scanEvent = _scanEvent.asSharedFlow()

    // Cooldown tracker to prevent duplicate floods in rapid succession
    private var lastScannedCode = ""
    private var lastScannedTime = 0L
    private val scanCooldownMs = 1500L

    fun toggleTorch() {
        _isTorchOn.value = !_isTorchOn.value
    }

    fun toggleCameraFacing() {
        _useFrontCamera.value = !_useFrontCamera.value
    }

    fun toggleContinuousMode() {
        _isContinuousMode.value = !_isContinuousMode.value
    }

    fun toggleHaptics() {
        _hapticsEnabled.value = !_hapticsEnabled.value
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Called when Camera Analyzer detects a barcode
     */
    fun onBarcodeDetected(code: String, format: String) {
        val now = System.currentTimeMillis()
        if (code == lastScannedCode && (now - lastScannedTime) < scanCooldownMs) {
            return
        }

        lastScannedCode = code
        lastScannedTime = now

        triggerHapticFeedback()

        viewModelScope.launch {
            // Check if barcode already exists in database
            val existing = repository.findByCode(code)
            if (existing != null) {
                // If already scanned, increment quantity by 1 and update timestamp
                val updated = existing.copy(
                    quantity = existing.quantity + 1,
                    timestamp = System.currentTimeMillis()
                )
                repository.update(updated)
                _scanEvent.emit(updated)
            } else {
                // Create new entry
                val newItem = BarcodeEntity(
                    code = code,
                    format = format,
                    title = "",
                    quantity = 1,
                    note = "",
                    timestamp = System.currentTimeMillis()
                )
                val id = repository.insert(newItem)
                _scanEvent.emit(newItem.copy(id = id))
            }
        }
    }

    /**
     * Add manual barcode (if barcode cannot be scanned or camera is unavailable)
     */
    fun addManualBarcode(code: String, format: String = "MANUAL", title: String = "", qty: Int = 1, note: String = "") {
        if (code.isBlank()) return
        viewModelScope.launch {
            val existing = repository.findByCode(code)
            if (existing != null) {
                val updated = existing.copy(
                    quantity = existing.quantity + qty,
                    title = if (title.isNotBlank()) title else existing.title,
                    note = if (note.isNotBlank()) note else existing.note,
                    timestamp = System.currentTimeMillis()
                )
                repository.update(updated)
                _scanEvent.emit(updated)
            } else {
                val newItem = BarcodeEntity(
                    code = code.trim(),
                    format = format,
                    title = title.trim(),
                    quantity = if (qty > 0) qty else 1,
                    note = note.trim(),
                    timestamp = System.currentTimeMillis()
                )
                val id = repository.insert(newItem)
                _scanEvent.emit(newItem.copy(id = id))
            }
        }
    }

    /**
     * Scan barcode from gallery photo
     */
    fun scanBarcodeFromUri(uri: Uri, onResult: (Boolean, String) -> Unit) {
        try {
            val image = InputImage.fromFilePath(appContext, uri)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        val first = barcodes[0]
                        val raw = first.rawValue ?: ""
                        if (raw.isNotBlank()) {
                            val format = BarcodeAnalyzer.getFormatName(first.format)
                            onBarcodeDetected(raw, format)
                            onResult(true, "Berhasil scan: $raw ($format)")
                        } else {
                            onResult(false, "Barcode terdeteksi tetapi nilainya kosong.")
                        }
                    } else {
                        onResult(false, "Tidak ada barcode yang terdeteksi di gambar ini.")
                    }
                }
                .addOnFailureListener { e ->
                    onResult(false, "Gagal memproses gambar: ${e.localizedMessage}")
                }
        } catch (e: Exception) {
            onResult(false, "Error membuka gambar: ${e.localizedMessage}")
        }
    }

    fun updateItem(item: BarcodeEntity) {
        viewModelScope.launch {
            repository.update(item)
        }
    }

    fun updateQuantity(id: Long, qty: Int) {
        if (qty <= 0) return
        viewModelScope.launch {
            repository.updateQuantity(id, qty)
        }
    }

    fun deleteItem(item: BarcodeEntity) {
        viewModelScope.launch {
            repository.delete(item)
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }

    private fun triggerHapticFeedback() {
        if (!_hapticsEnabled.value) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val defaultVibrator = vibratorManager?.defaultVibrator
                if (defaultVibrator?.hasVibrator() == true) {
                    defaultVibrator.vibrate(
                        VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(70)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore if vibration is not supported or not permitted
        }
    }

    class Factory(
        private val repository: BarcodeRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BarcodeViewModel::class.java)) {
                return BarcodeViewModel(repository, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

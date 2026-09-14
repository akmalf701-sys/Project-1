package com.example.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    fun setContinuousMode(enabled: Boolean) {
        _isContinuousMode.value = enabled
    }

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled = _hapticsEnabled.asStateFlow()

    // Sound beep ("tut") toggle (Default: true)
    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled = _soundEnabled.asStateFlow()

    // Duplicate prevention toggle (Default: true)
    private val _preventDuplicates = MutableStateFlow(true)
    val preventDuplicates = _preventDuplicates.asStateFlow()

    // Event notifications for UI (e.g. snackbar or dialog when item scanned)
    private val _scanEvent = MutableSharedFlow<BarcodeEntity>()
    val scanEvent = _scanEvent.asSharedFlow()

    // Notification when duplicate barcode is scanned and rejected
    private val _duplicateEvent = MutableSharedFlow<String>()
    val duplicateEvent = _duplicateEvent.asSharedFlow()

    // Cooldown tracker to prevent duplicate floods in rapid succession
    private var lastScannedCode = ""
    private var lastScannedTime = 0L
    private val scanCooldownMs = 1200L

    // ToneGenerator for the classic barcode scanner "tut" beep sound
    @Volatile
    private var toneGenerator: ToneGenerator? = null

    private fun getToneGenerator(): ToneGenerator? {
        if (toneGenerator == null) {
            synchronized(this) {
                if (toneGenerator == null) {
                    toneGenerator = try {
                        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                    } catch (_: Exception) {
                        try {
                            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return toneGenerator
    }

    /**
     * Plays the classic retail scanner beep ("tut") sound
     */
    fun playScanBeep() {
        if (!_soundEnabled.value) return
        try {
            // TONE_PROP_BEEP is 1000Hz pure tone - classic supermarket / POS scanner "tut"
            getToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
        } catch (_: Exception) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
            } catch (_: Exception) {}
        }
    }

    /**
     * Plays warning tone for duplicate scan
     */
    fun playDuplicateBeep() {
        if (!_soundEnabled.value) return
        try {
            // Double tone alert for duplicate notification
            getToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        } catch (_: Exception) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
            } catch (_: Exception) {}
        }
    }

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

    fun toggleSound() {
        _soundEnabled.value = !_soundEnabled.value
    }

    fun togglePreventDuplicates() {
        _preventDuplicates.value = !_preventDuplicates.value
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    suspend fun isBarcodeRegistered(code: String): Boolean {
        return repository.findByCode(code.trim()) != null
    }

    /**
     * Called when Camera Analyzer detects a barcode
     */
    fun onBarcodeDetected(code: String, format: String) {
        val trimmedCode = code.trim()
        if (trimmedCode.isBlank()) return

        val now = System.currentTimeMillis()
        if (trimmedCode == lastScannedCode && (now - lastScannedTime) < scanCooldownMs) {
            return
        }

        lastScannedCode = trimmedCode
        lastScannedTime = now

        viewModelScope.launch {
            // Check if barcode already exists in database
            val existing = repository.findByCode(trimmedCode)
            if (existing != null) {
                if (_preventDuplicates.value) {
                    // Prevent duplicate: do NOT insert duplicate or update database
                    playDuplicateBeep()
                    triggerHapticFeedback(isError = true)
                    _duplicateEvent.emit(trimmedCode)
                } else {
                    // Duplicate allowed: increment quantity
                    val updated = existing.copy(
                        quantity = existing.quantity + 1,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.update(updated)
                    playScanBeep()
                    triggerHapticFeedback(isError = false)
                    _scanEvent.emit(updated)
                }
            } else {
                // New unique barcode: insert into database
                val newItem = BarcodeEntity(
                    code = trimmedCode,
                    format = format,
                    title = "",
                    quantity = 1,
                    note = "",
                    timestamp = System.currentTimeMillis()
                )
                val id = repository.insert(newItem)
                playScanBeep()
                triggerHapticFeedback(isError = false)
                _scanEvent.emit(newItem.copy(id = id))
            }
        }
    }

    /**
     * Add manual barcode (if barcode cannot be scanned or camera is unavailable)
     */
    fun addManualBarcode(
        code: String,
        format: String = "MANUAL",
        title: String = "",
        qty: Int = 1,
        note: String = "",
        onDuplicate: (() -> Unit)? = null
    ) {
        val trimmedCode = code.trim()
        if (trimmedCode.isBlank()) return
        viewModelScope.launch {
            val existing = repository.findByCode(trimmedCode)
            if (existing != null) {
                if (_preventDuplicates.value) {
                    playDuplicateBeep()
                    triggerHapticFeedback(isError = true)
                    _duplicateEvent.emit(trimmedCode)
                    onDuplicate?.invoke()
                    return@launch
                } else {
                    val updated = existing.copy(
                        quantity = existing.quantity + qty,
                        title = if (title.isNotBlank()) title else existing.title,
                        note = if (note.isNotBlank()) note else existing.note,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.update(updated)
                    playScanBeep()
                    triggerHapticFeedback(isError = false)
                    _scanEvent.emit(updated)
                }
            } else {
                val newItem = BarcodeEntity(
                    code = trimmedCode,
                    format = format,
                    title = title.trim(),
                    quantity = if (qty > 0) qty else 1,
                    note = note.trim(),
                    timestamp = System.currentTimeMillis()
                )
                val id = repository.insert(newItem)
                playScanBeep()
                triggerHapticFeedback(isError = false)
                _scanEvent.emit(newItem.copy(id = id))
            }
        }
    }

    /**
     * Scan barcode from gallery photo with automatic OCR fallback for contract labels
     */
    fun scanBarcodeFromUri(uri: Uri, onResult: (Boolean, String) -> Unit) {
        try {
            val image = InputImage.fromFilePath(appContext, uri)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_ITF
                )
                .build()
            val scanner = BarcodeScanning.getClient(options)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val first = barcodes.firstOrNull { 
                        !it.rawValue.isNullOrBlank() && BarcodeAnalyzer.isValidBarcode(it.rawValue!!, it.format)
                    }
                    if (first != null) {
                        val raw = first.rawValue!!.trim()
                        val cleanedCode = BarcodeAnalyzer.cleanBarcodeValue(raw, first.format)
                        val format = BarcodeAnalyzer.getFormatName(first.format)
                        viewModelScope.launch {
                            val existing = repository.findByCode(cleanedCode)
                            if (existing != null && _preventDuplicates.value) {
                                playDuplicateBeep()
                                triggerHapticFeedback(isError = true)
                                _duplicateEvent.emit(cleanedCode)
                                onResult(false, "Barcode $cleanedCode sudah terdaftar di daftar Excel (Duplikat dicegah).")
                            } else {
                                onBarcodeDetected(cleanedCode, format)
                                onResult(true, "Berhasil scan barcode: $cleanedCode ($format)")
                            }
                        }
                    } else {
                        // Fallback: Use OCR Text Recognition to read Contract Number text
                        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        textRecognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val candidates = mutableListOf<String>()
                                for (block in visionText.textBlocks) {
                                    for (line in block.lines) {
                                        val words = line.text.trim().split("\\s+".toRegex())
                                        for (w in words) {
                                            val clean = w.replace("[^A-Za-z0-9]".toRegex(), "")
                                            if (clean.length >= 6) {
                                                candidates.add(clean)
                                            }
                                        }
                                    }
                                }
                                val distinct = candidates.distinct()
                                val contractCandidate = distinct.firstOrNull { c ->
                                    c.any { it.isLetter() } && c.any { it.isDigit() } && c.length in 8..24
                                } ?: distinct.firstOrNull { it.length in 8..24 }

                                if (contractCandidate != null) {
                                    viewModelScope.launch {
                                        val existing = repository.findByCode(contractCandidate)
                                        if (existing != null && _preventDuplicates.value) {
                                            playDuplicateBeep()
                                            triggerHapticFeedback(isError = true)
                                            _duplicateEvent.emit(contractCandidate)
                                            onResult(false, "Nomor Kontrak $contractCandidate sudah terdaftar (Duplikat dicegah).")
                                        } else {
                                            onBarcodeDetected(contractCandidate, "NOMOR_KONTRAK")
                                            onResult(true, "Berhasil mengenali Nomor Kontrak: $contractCandidate")
                                        }
                                    }
                                } else {
                                    onResult(false, "Barcode tidak terdeteksi di gambar ini.")
                                }
                            }
                            .addOnFailureListener {
                                onResult(false, "Tidak ada barcode yang terdeteksi di gambar ini.")
                            }
                            .addOnCompleteListener {
                                try { textRecognizer.close() } catch (_: Exception) {}
                            }
                    }
                }
                .addOnFailureListener { e ->
                    onResult(false, "Gagal memproses gambar: ${e.localizedMessage}")
                }
                .addOnCompleteListener {
                    try { scanner.close() } catch (_: Exception) {}
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

    private fun triggerHapticFeedback(isError: Boolean = false) {
        if (!_hapticsEnabled.value) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val defaultVibrator = vibratorManager?.defaultVibrator
                if (defaultVibrator?.hasVibrator() == true) {
                    if (isError) {
                        val timings = longArrayOf(0, 70, 80, 70)
                        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                        defaultVibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        defaultVibrator.vibrate(
                            VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (isError) {
                            val timings = longArrayOf(0, 70, 80, 70)
                            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                        } else {
                            vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(if (isError) 150 else 70)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore if vibration is not supported or not permitted
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
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

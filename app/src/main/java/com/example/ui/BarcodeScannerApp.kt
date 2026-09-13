package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.BarcodeEntity
import com.example.ui.components.BarcodeItemCard
import com.example.ui.dialogs.EditItemDialog
import com.example.ui.dialogs.ExportBottomSheet
import com.example.ui.dialogs.ManualInputDialog
import com.example.ui.scanner.CameraScannerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun BarcodeScannerApp(viewModel: BarcodeViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigation tab: 0 = Scanner, 1 = Data Scan
    var currentTab by remember { mutableIntStateOf(0) }

    // Dialog & Sheet states
    var showExportSheet by remember { mutableStateOf(false) }
    var showManualInputDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<BarcodeEntity?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // Last scanned banner
    var recentScannedItem by remember { mutableStateOf<BarcodeEntity?>(null) }
    // Duplicate rejection banner
    var duplicateScannedCode by remember { mutableStateOf<String?>(null) }

    // Permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(
                context,
                "Izin kamera diperlukan untuk scan barcode secara langsung.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.scanBarcodeFromUri(uri) { success, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Observe scan events to show interactive overlay card
    LaunchedEffect(Unit) {
        viewModel.scanEvent.collectLatest { item ->
            duplicateScannedCode = null
            recentScannedItem = item
            delay(3500)
            if (recentScannedItem?.id == item.id) {
                recentScannedItem = null
            }
        }
    }

    // Observe duplicate rejection events to show warning banner
    LaunchedEffect(Unit) {
        viewModel.duplicateEvent.collectLatest { code ->
            recentScannedItem = null
            duplicateScannedCode = code
            delay(3500)
            if (duplicateScannedCode == code) {
                duplicateScannedCode = null
            }
        }
    }

    val allItems by viewModel.allItems.collectAsState()
    val filteredItems by viewModel.filteredItems.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isTorchOn by viewModel.isTorchOn.collectAsState()
    val useFrontCamera by viewModel.useFrontCamera.collectAsState()
    val isContinuousMode by viewModel.isContinuousMode.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val preventDuplicates by viewModel.preventDuplicates.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                windowInsets = WindowInsets.navigationBars,
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scanner Tab"
                        )
                    },
                    label = { Text("Scan Barcode") },
                    modifier = Modifier.testTag("nav_scanner_tab")
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = {
                        Box {
                            Icon(
                                imageVector = Icons.Default.TableChart,
                                contentDescription = "Data & Excel Tab"
                            )
                            if (allItems.isNotEmpty()) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .align(Alignment.TopEnd)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${allItems.size}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    },
                    label = { Text("Daftar Excel (${allItems.size})") },
                    modifier = Modifier.testTag("nav_list_tab")
                )
            }
        },
        floatingActionButton = {
            if (currentTab == 1 && allItems.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { showExportSheet = true },
                    containerColor = Color(0xFF0F766E),
                    contentColor = Color.White,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .testTag("fab_export_excel")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Ekspor ke Excel"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ekspor Excel",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when (currentTab) {
                0 -> {
                    // SCANNER TAB
                    ScannerTabContent(
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        isTorchOn = isTorchOn,
                        onToggleTorch = { viewModel.toggleTorch() },
                        useFrontCamera = useFrontCamera,
                        onToggleCameraFacing = { viewModel.toggleCameraFacing() },
                        hapticsEnabled = hapticsEnabled,
                        onToggleHaptics = { viewModel.toggleHaptics() },
                        soundEnabled = soundEnabled,
                        onToggleSound = { viewModel.toggleSound() },
                        preventDuplicates = preventDuplicates,
                        onTogglePreventDuplicates = { viewModel.togglePreventDuplicates() },
                        autoScanEnabled = isContinuousMode,
                        onToggleAutoScan = { viewModel.toggleContinuousMode() },
                        onPickPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onManualInput = { showManualInputDialog = true },
                        onBarcodeDetected = { code, format ->
                            viewModel.onBarcodeDetected(code, format)
                        },
                        scannedCount = allItems.size,
                        onOpenExcelExport = { showExportSheet = true },
                        recentItem = recentScannedItem,
                        duplicateCode = duplicateScannedCode,
                        onDismissDuplicate = { duplicateScannedCode = null },
                        onEditRecentItem = { item ->
                            itemToEdit = item
                        }
                    )
                }

                1 -> {
                    // LIST & EXCEL TAB
                    DataListTabContent(
                        items = filteredItems,
                        totalItems = allItems,
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        preventDuplicates = preventDuplicates,
                        onTogglePreventDuplicates = { viewModel.togglePreventDuplicates() },
                        soundEnabled = soundEnabled,
                        onToggleSound = { viewModel.toggleSound() },
                        onEditItem = { item -> itemToEdit = item },
                        onDeleteItem = { item -> viewModel.deleteItem(item) },
                        onQuantityChange = { id, newQty -> viewModel.updateQuantity(id, newQty) },
                        onClearAll = { showClearAllConfirm = true },
                        onExportExcel = { showExportSheet = true },
                        onAddManual = { showManualInputDialog = true },
                        onGoToScanner = { currentTab = 0 }
                    )
                }
            }
        }
    }

    // Dialogs
    if (showExportSheet) {
        ExportBottomSheet(
            items = allItems,
            onDismiss = { showExportSheet = false }
        )
    }

    if (showManualInputDialog) {
        val existingCodes = remember(allItems) { allItems.map { it.code }.toSet() }
        ManualInputDialog(
            onDismiss = { showManualInputDialog = false },
            onSubmit = { code, title, qty, note ->
                viewModel.addManualBarcode(code, "MANUAL", title, qty, note)
                showManualInputDialog = false
                Toast.makeText(context, "Barcode berhasil ditambahkan!", Toast.LENGTH_SHORT).show()
            },
            preventDuplicates = preventDuplicates,
            existingCodes = existingCodes
        )
    }

    itemToEdit?.let { item ->
        EditItemDialog(
            item = item,
            onDismiss = { itemToEdit = null },
            onSave = { updated ->
                viewModel.updateItem(updated)
                itemToEdit = null
                Toast.makeText(context, "Perubahan disimpan!", Toast.LENGTH_SHORT).show()
            },
            onDelete = { toDelete ->
                viewModel.deleteItem(toDelete)
                itemToEdit = null
                Toast.makeText(context, "Item dihapus", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Hapus Semua Data?") },
            text = { Text("Semua data barcode yang telah discan akan dihapus secara permanen. Pastikan Anda sudah mengekspor ke Excel jika masih diperlukan.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAll()
                        showClearAllConfirm = false
                        Toast.makeText(context, "Semua data barcode dibersihkan", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_clear_all")
                ) {
                    Text("Ya, Hapus Semua")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
fun ScannerTabContent(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    isTorchOn: Boolean,
    onToggleTorch: () -> Unit,
    useFrontCamera: Boolean,
    onToggleCameraFacing: () -> Unit,
    hapticsEnabled: Boolean,
    onToggleHaptics: () -> Unit,
    soundEnabled: Boolean,
    onToggleSound: () -> Unit,
    preventDuplicates: Boolean,
    onTogglePreventDuplicates: () -> Unit,
    autoScanEnabled: Boolean = true,
    onToggleAutoScan: () -> Unit = {},
    onPickPhoto: () -> Unit,
    onManualInput: () -> Unit,
    onBarcodeDetected: (String, String) -> Unit,
    scannedCount: Int,
    onOpenExcelExport: () -> Unit,
    recentItem: BarcodeEntity?,
    duplicateCode: String?,
    onDismissDuplicate: () -> Unit,
    onEditRecentItem: (BarcodeEntity) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            // Live CameraX Viewfinder with Shutter Freeze Button & Stability Filter
            CameraScannerView(
                modifier = Modifier.fillMaxSize(),
                isTorchOn = isTorchOn,
                useFrontCamera = useFrontCamera,
                autoScanEnabled = autoScanEnabled,
                onToggleAutoScan = onToggleAutoScan,
                onBarcodeDetected = onBarcodeDetected
            )
        } else {
            // Permission screen
            CameraPermissionPlaceholder(
                onRequestPermission = onRequestPermission,
                onManualInput = onManualInput
            )
        }

        // Top Control Floating Bar (Glassmorphic HUD)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xCC0F172A),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Torch toggle
                IconButton(
                    onClick = onToggleTorch,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("torch_toggle"),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isTorchOn) Color(0xFFF59E0B) else Color(0x33FFFFFF),
                        contentColor = if (isTorchOn) Color.Black else Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = "Senter / Flash",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Sound ("Tut" beep) toggle
                IconButton(
                    onClick = onToggleSound,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("sound_toggle"),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (soundEnabled) Color(0xFF0284C7) else Color(0x33FFFFFF),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = if (soundEnabled) "Suara Tut Aktif" else "Suara Tut Mute",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Prevent Duplicates toggle
                IconButton(
                    onClick = onTogglePreventDuplicates,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("prevent_duplicate_toggle"),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (preventDuplicates) Color(0xFF10B981) else Color(0x33FFFFFF),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = if (preventDuplicates) "Anti-Duplikat Aktif" else "Anti-Duplikat Nonaktif",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Flip camera
                IconButton(
                    onClick = onToggleCameraFacing,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0x33FFFFFF),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Ganti Kamera",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Scan from Gallery
                IconButton(
                    onClick = onPickPhoto,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0x33FFFFFF),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Scan dari Galeri Foto",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Manual Input
                IconButton(
                    onClick = onManualInput,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0x33FFFFFF),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Input Manual",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Quick Export Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F766E),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenExcelExport() }
                        .testTag("top_excel_export_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.TableChart,
                            contentDescription = "Excel",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$scannedCount",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Active Mode Status Indicators (Anti-Duplikat & Suara Tut Pills)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 66.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (preventDuplicates) Color(0xE0065F46) else Color(0xCC7F1D1D),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onTogglePreventDuplicates() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (preventDuplicates) "🛡️ Anti-Duplikat ON" else "⚠️ Duplikat Diizinkan",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (soundEnabled) Color(0xE00369A1) else Color(0xCC475569),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleSound() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (soundEnabled) "🔊 Suara Tut ON" else "🔇 Tut Mute",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Subtitle Tip Overlay
        Text(
            text = "Arahkan kamera tepat ke barcode",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 98.dp)
                .background(Color(0x66000000), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp)
        )

        // Duplicate Barcode Warning Pop-up Banner
        AnimatedVisibility(
            visible = duplicateCode != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 148.dp)
        ) {
            duplicateCode?.let { code ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xF0450A0A)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismissDuplicate() }
                        .testTag("duplicate_rejection_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ Barcode Duplikat Ditolak!",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFFCA5A5),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = code,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Nomor ini sudah ada di daftar Excel.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x33FFFFFF)
                        ) {
                            Text(
                                text = "Abaikan",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Recent Scanned Item Pop-up Banner
        AnimatedVisibility(
            visible = recentItem != null && duplicateCode == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 148.dp)
        ) {
            recentItem?.let { item ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xF00F172A)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditRecentItem(item) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF10B981),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Berhasil Terscan! • Tut",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• Qty: ${item.quantity}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = item.code,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x33FFFFFF)
                        ) {
                            Text(
                                text = "Edit",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPermissionPlaceholder(
    onRequestPermission: () -> Unit,
    onManualInput: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Izin Kamera Dibutuhkan",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Untuk mendeteksi dan memindai kode barcode secara otomatis menggunakan kamera, silakan aktifkan izin kamera.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("grant_camera_permission_button")
            ) {
                Text("Izinkan Kamera", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onManualInput,
                modifier = Modifier.testTag("manual_input_fallback_button")
            ) {
                Text("Ketik Barcode Manual")
            }
        }
    }
}

@Composable
fun DataListTabContent(
    items: List<BarcodeEntity>,
    totalItems: List<BarcodeEntity>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    preventDuplicates: Boolean,
    onTogglePreventDuplicates: () -> Unit,
    soundEnabled: Boolean,
    onToggleSound: () -> Unit,
    onEditItem: (BarcodeEntity) -> Unit,
    onDeleteItem: (BarcodeEntity) -> Unit,
    onQuantityChange: (Long, Int) -> Unit,
    onClearAll: () -> Unit,
    onExportExcel: () -> Unit,
    onAddManual: () -> Unit,
    onGoToScanner: () -> Unit
) {
    val totalQty = totalItems.sumOf { it.quantity }
    val uniqueCount = totalItems.map { it.code }.distinct().size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Daftar Scan Barcode",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Siap diekspor ke Microsoft Excel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (totalItems.isNotEmpty()) {
                    IconButton(
                        onClick = onClearAll,
                        modifier = Modifier.testTag("clear_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Hapus Semua",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(
                    onClick = onAddManual,
                    modifier = Modifier.testTag("add_manual_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Tambah Manual",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Mode Settings Pills (Anti-Duplikat & Suara Tut)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (preventDuplicates) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTogglePreventDuplicates() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (preventDuplicates) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (preventDuplicates) "Anti-Duplikat: AKTIF" else "Anti-Duplikat: NONAKTIF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (preventDuplicates) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (soundEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleSound() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = null,
                        tint = if (soundEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (soundEnabled) "Suara Tut: ON" else "Suara Tut: MUTE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (soundEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Summary Statistics Cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                label = "Total Barcode",
                value = "${totalItems.size}",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Total Qty",
                value = "$totalQty",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Kode Unik",
                value = "$uniqueCount",
                modifier = Modifier.weight(1f)
            )
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Cari kode, nama, format, atau catatan...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Cari"
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Hapus Pencarian"
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("search_barcode_input")
        )

        // List Content
        if (totalItems.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Belum Ada Barcode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Mulai scan barcode produk atau barang dengan kamera untuk mengumpulkan data dan ekspor ke Excel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onGoToScanner,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buka Scanner Kamera")
                    }
                }
            }
        } else if (items.isEmpty()) {
            // Search no results
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Tidak ada hasil pencarian untuk \"$searchQuery\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Items List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    BarcodeItemCard(
                        item = item,
                        index = index + 1,
                        onEdit = { onEditItem(item) },
                        onDelete = { onDeleteItem(item) },
                        onQuantityChange = { newQty -> onQuantityChange(item.id, newQty) }
                    )
                }
            }
        }
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

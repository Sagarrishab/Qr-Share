package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val themeColor by viewModel.themeColor.collectAsState()
            MyApplicationTheme(themeMode = themeMode, themeColor = themeColor) {
                MainScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiMode by viewModel.uiMode.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val hostedFiles by viewModel.hostedFiles.collectAsState()
    val targetUrl by viewModel.targetUrl.collectAsState()
    val clientStatus by viewModel.clientStatus.collectAsState()
    val transferHistory by viewModel.transferHistory.collectAsState(initial = emptyList())
    val localIpAddresses by viewModel.localIpAddresses.collectAsState()
    val selectedIp by viewModel.selectedIp.collectAsState()
    val selectedPort by viewModel.selectedPort.collectAsState()
    val activeServerTransfers by viewModel.activeServerTransfers.collectAsState()
    val preparedFiles by viewModel.preparedFiles.collectAsState()

    val customUrlAlias by viewModel.customUrlAlias.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val isNetworkConnected by viewModel.isNetworkConnected.collectAsState()

    var bottomTab by remember { mutableStateOf("HOME") }
    var showAboutAppDialog by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Picker for sharing files from Android (Host server)
    val hostFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            viewModel.addHostShare(uri)
        }
    }

    // Picker for pushing files to PC (scan client)
    val clientFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPreparedFiles(uris)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("bottom_nav")
            ) {
                NavigationBarItem(
                    selected = bottomTab == "HOME",
                    onClick = { bottomTab = "HOME" },
                    icon = { Icon(imageVector = Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    modifier = Modifier.testTag("nav_home")
                )
                NavigationBarItem(
                    selected = bottomTab == "HOW_TO_USE",
                    onClick = { bottomTab = "HOW_TO_USE" },
                    icon = { Icon(imageVector = Icons.Default.Info, contentDescription = "How to Use") },
                    label = { Text("How to Use") },
                    modifier = Modifier.testTag("nav_how_to_use")
                )
                NavigationBarItem(
                    selected = bottomTab == "MORE",
                    onClick = { bottomTab = "MORE" },
                    icon = { Icon(imageVector = Icons.Default.Menu, contentDescription = "More") },
                    label = { Text("More") },
                    modifier = Modifier.testTag("nav_more")
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // App Header
            AppHeader(
                onInfoClick = { showAboutAppDialog = true },
                isNetworkConnected = isNetworkConnected
            )

            when (bottomTab) {
                "HOME" -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Tab Selector
                        TabSelector(
                            currentMode = uiMode,
                            onModeSelected = { mode ->
                                viewModel.setUiMode(mode)
                                if (mode == "HOST") {
                                    viewModel.startLocalServer()
                                } else {
                                    viewModel.stopLocalServer()
                                }
                            }
                        )

                        // Main content
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            when (uiMode) {
                                "HOST" -> {
                                    HostSharingPanel(
                                        serverUrl = serverUrl,
                                        customUrlAlias = customUrlAlias,
                                        hostedFiles = hostedFiles,
                                        localIpAddresses = localIpAddresses,
                                        selectedIp = selectedIp,
                                        selectedPort = selectedPort,
                                        activeServerTransfers = activeServerTransfers,
                                        onUpdateHost = { ip, port -> viewModel.updateHostAddressAndPort(ip, port) },
                                        onAddFiles = { hostFilePickerLauncher.launch("*/*") },
                                        onRemoveShare = { viewModel.removeHostShare(it) }
                                    )
                                }
                                "SCAN" -> {
                                    ScanPanel(
                                        hasPermission = hasCameraPermission,
                                        targetUrl = targetUrl,
                                        status = clientStatus,
                                        preparedFiles = preparedFiles,
                                        onRemovePreparedFile = { viewModel.removePreparedFile(it) },
                                        onClearPreparedFiles = { viewModel.clearPreparedFiles() },
                                        onTransferPreparedFiles = { viewModel.transferPreparedFiles() },
                                        onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                        onQrScanned = { viewModel.onTargetScanned(it) },
                                        onSelectFiles = { clientFilePickerLauncher.launch("*/*") },
                                        onReset = {
                                            viewModel.clearTargetUrl()
                                            viewModel.clearPreparedFiles()
                                        }
                                    )
                                }
                                "CLOUD" -> {
                                    CloudBackupPanel(
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                }
                "HOW_TO_USE" -> {
                    HowToUsePage()
                }
                "MORE" -> {
                    MorePage(
                        customUrlAlias = customUrlAlias,
                        connectedDevices = connectedDevices,
                        transferHistory = transferHistory,
                        viewModel = viewModel,
                        context = context
                    )
                }
            }
        }
    }

    if (showAboutAppDialog) {
        AppAboutDialog(onDismiss = { showAboutAppDialog = false })
    }
}

@Composable
fun HowToUsePage() {
    val faqList = listOf(
        "Is there any size limit for sharing?" to "No! Since the file transfer occurs entirely over your local Wi-Fi router network, you can transfer massive GB scale files or folder structures with zero limits at local gigabit speeds.",
        "How can I access the local server on my PC?" to "Simply open any modern web browser (Chrome, Edge, Safari, Firefox) on your PC, tablet, or secondary phone, and enter either the beautiful Short URL (e.g., http://sds.local:8182) or the raw IP address address displayed on your Home screen.",
        "Why is my PC unable to load the URL?" to "Make sure that BOTH your phone and your PC are connected to the exact same Wi-Fi router access point. Also, check that AP Isolation or Guest Mode is disabled on your router settings, as this prevents local devices from discovering each other.",
        "Can I send files from my computer back to my phone?" to "Absolutely! Click or drag and drop any file or folder onto the dashed dropzone area on the PC Web Interface. They will instantly transfer at peer-to-peer speeds and find themselves securely saved in your phone's storage (ExternalFiles/Received folder)."
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Connect & Transfer Guide",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Follow these simple steps to move files between devices without wires",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Card 1: How to share from Android to PC
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "1. Share to Computer (Android \u2794 PC)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "• On your Phone: Select 'Home' -> select files to share -> click 'Share Files' to start hosting local web page server.\n" +
                               "• On your Computer: Simply scan the generator QR code or open the displayed URL link (e.g. http://sds.local:8080) in any browser.\n" +
                               "• Download files: Your files are now instantly accessible on the computer screen. Just tap Download!",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Card 2: How to share from PC to Android
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "2. Send to Phone (PC \u2794 Android)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "• Open our local browser web dashboard link on your PC.\n" +
                               "• Click the dashed drop-zone area to browse files, or drag and drop files/directories directly into it.\n" +
                               "• Files are instantly transferred of high speed. Click history on phone to reveal downloaded files!",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Section: FAQ
        item {
            Text(
                text = "Frequently Asked Questions",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
        }

        items(faqList) { faq ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Q: ${faq.first}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = faq.second,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MorePage(
    customUrlAlias: String,
    connectedDevices: Map<String, ConnectedClient>,
    transferHistory: List<TransferRecord>,
    viewModel: MainViewModel,
    context: Context
) {
    var aliasText by remember(customUrlAlias) { mutableStateOf(customUrlAlias) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }

    val currentThemeMode by viewModel.themeMode.collectAsState()
    val currentThemeColor by viewModel.themeColor.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Settings & URL Customization
        item {
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Customize Connection Short URL",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Change the local browser address friendly alias (default is 'sds')",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = aliasText,
                            onValueChange = { aliasText = it },
                            singleLine = true,
                            label = { Text("URL Alias Name") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("alias_input")
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.updateCustomUrlAlias(aliasText) },
                            modifier = Modifier.testTag("alias_save_btn")
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }
        }

        // Section: Personalization Theme Customizer
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Customize App Theme",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Personalize colors and lighting mode according to your taste",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Mode Selection Row
                    Text(
                        text = "Theme Mode",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            "ALWAYS_DARK" to "Dark",
                            "ALWAYS_LIGHT" to "Light",
                            "FOLLOW_SYSTEM" to "System"
                        )
                        modes.forEach { (mode, label) ->
                            val selected = currentThemeMode == mode
                            FilledTonalButton(
                                onClick = { viewModel.updateThemeMode(mode) },
                                modifier = Modifier.weight(1f).testTag("theme_mode_$mode"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Color Palette Selector Row
                    Text(
                        text = "Primary Accent Color",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val colorOptions = listOf(
                            "COSMIC_CYAN" to Color(0xFF38BDF8),
                            "SUNSET_ORANGE" to Color(0xFFFB923C),
                            "ROYAL_PURPLE" to Color(0xFFC084FC),
                            "FOREST_GREEN" to Color(0xFF34D399),
                            "OCEAN_BLUE" to Color(0xFF60A5FA)
                        )
                        colorOptions.forEach { (colorName, color) ->
                            val selected = currentThemeColor == colorName
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.updateThemeColor(colorName) }
                                    .testTag("theme_color_$colorName"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = if (colorName == "COSMIC_CYAN" || colorName == "ROYAL_PURPLE" || colorName == "FOREST_GREEN" || colorName == "OCEAN_BLUE") Color(0xFF0F172A) else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Active Desktop Companions
        item {
            Text(
                text = "Connected Companions",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val devicesList = connectedDevices.values.toList()
                    if (devicesList.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "No external devices currently browsing files.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "${devicesList.size} active companion(s) connected:",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        devicesList.forEach { client ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "IP: ${client.ip}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Browser Agent: ${client.userAgent}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: History
        item {
            Text(
                text = "Transfer Logs",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            HistoryPanel(
                historyList = transferHistory,
                onShare = { record ->
                    val file = File(record.filePath)
                    if (file.exists()) {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share File"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open share chooser: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDelete = { viewModel.deleteHistoryRecord(it) },
                onBackup = { viewModel.manualBackup(it) },
                onClearAll = { viewModel.clearHistory() }
            )
        }

        // Section: Legal docs & Links
        item {
            Text(
                text = "About & Legal",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { showPrivacyDialog = true },
                    modifier = Modifier.weight(1f).testTag("btn_show_privacy"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Privacy Policy")
                }
                OutlinedButton(
                    onClick = { showTermsDialog = true },
                    modifier = Modifier.weight(1f).testTag("btn_show_terms"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Terms of Service")
                }
            }
        }

        // Section: About Developer
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "SS",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Sagar Saini",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Technology Enthusiast & UI/UX Designer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "I am a technology enthusiast with experience in UI/UX design, no-code and low-code development, and digital solutions. Despite coming from a non-technical academic background, I have built practical projects using modern tools and AI-assisted workflows. I am passionate about learning, problem-solving, and creating solutions that help businesses and users. I am seeking opportunities to further develop my skills while contributing with dedication, adaptability, and a strong willingness to learn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Developed and designed by Sagar Saini",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("Dismiss")
                }
            },
            title = {
                Text(
                    text = "Privacy Policy",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Welcome to QR File Share. We are highly dedicated to shielding your privacy and user rights.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Local-Only Architecture",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "All transmissions occur purely within your local Wi-Fi router intranet, without routing data through remote background servers or third-party web databases.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "2. No Analytics Tracking",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "We do not harvest telemetry logs, personal metadata, IP histories, or device identification keys. Your transfer activities are strictly sandbox-private on your localized terminal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text("Dismiss")
                }
            },
            title = {
                Text(
                    text = "Terms and Conditions",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Please read these Terms & Conditions carefully before deploying the local transmission utilities.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Usage Scope",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "You authorize the local server implementation to bind to TCP sockets on your local computer/phone network, allowing devices to upload and download shared assets locally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "2. Liability Exclusions",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "You acknowledge and agree that transferring sensitive files over insecure public Wi-Fi access points carries personal risk. Ensure your network is secure before allowing hosting connections.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

@Composable
fun AppHeader(
    onInfoClick: () -> Unit,
    isNetworkConnected: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "NetworkDotGlow")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 20.dp, end = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = "Logo",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "QR File Share",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    fontSize = 22.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer(alpha = dotAlpha)
                        .background(
                            color = if (isNetworkConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (isNetworkConnected) "Network Connected" else "Connection Lost",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    ),
                    color = if (isNetworkConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "Local Shared",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier.testTag("btn_about_app_dialog")
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "About App Info",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun TabSelector(
    currentMode: String,
    onModeSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f), RoundedCornerShape(27.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (currentMode == "HOST") MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onModeSelected("HOST") },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    tint = if (currentMode == "HOST") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Share",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (currentMode == "HOST") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (currentMode == "SCAN") MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onModeSelected("SCAN") },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = if (currentMode == "SCAN") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Scan",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (currentMode == "SCAN") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (currentMode == "CLOUD") MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onModeSelected("CLOUD") },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (currentMode == "CLOUD") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Cloud",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (currentMode == "CLOUD") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun HostSharingPanel(
    serverUrl: String?,
    customUrlAlias: String,
    hostedFiles: List<OutgoingShare>,
    localIpAddresses: List<String>,
    selectedIp: String,
    selectedPort: Int,
    activeServerTransfers: Map<String, ActiveTransfer>,
    onUpdateHost: (String, Int) -> Unit,
    onAddFiles: () -> Unit,
    onRemoveShare: (OutgoingShare) -> Unit
) {
    val context = LocalContext.current
    var isSettingsExpanded by remember { mutableStateOf(false) }
    var qrUseShortUrl by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf("LIST") }

    val shortUrl = "http://$customUrlAlias.local:$selectedPort"
    val qrUrl = if (qrUseShortUrl && serverUrl != null) shortUrl else (serverUrl ?: "")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Hosting Sharing Session",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF10B981)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (serverUrl != null) {
                        // QR Code container
                        val qrBitmap = remember(qrUrl) {
                            QrCodeGenerator.generateQrCode(qrUrl, 450, 450)
                        }

                        Box(
                            modifier = Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Connection QR Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // QR Code target switcher using modern Material 3 layout
                        Text(
                            text = "QR Code Address Type",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { qrUseShortUrl = true },
                                modifier = Modifier.weight(1f).testTag("select_short_url_qr"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (qrUseShortUrl) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    contentColor = if (qrUseShortUrl) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Short URL", style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = { qrUseShortUrl = false },
                                modifier = Modifier.weight(1f).testTag("select_ip_url_qr"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (!qrUseShortUrl) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    contentColor = if (!qrUseShortUrl) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("IP Address", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Display both addresses
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Short URL option
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.background,
                                border = BorderStroke(1.dp, if (qrUseShortUrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Host URL Alias",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (qrUseShortUrl) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier.padding(vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "QR Active",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = shortUrl,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            copyToClipboard(context, shortUrl)
                                            Toast.makeText(context, "Short URL Copied!", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Copy", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }

                            // Raw IP URL alternative
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.background,
                                border = BorderStroke(1.dp, if (!qrUseShortUrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Host IP Address",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                            if (!qrUseShortUrl) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier.padding(vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "QR Active",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = serverUrl,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            copyToClipboard(context, serverUrl)
                                            Toast.makeText(context, "IP URL Copied!", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.secondary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Copy", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "To access this phone's files, connect your computer to the same Wi-Fi router, then open either of the links in your web browser or scan the active QR code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )

                        if (isEmulator()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Cloud Sandbox",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Running in Cloud Sandbox",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "The generated QR code and HTTP link (${serverUrl ?: "http://10.0.2.15:8817"}) use the virtual emulator's private IP. Because this app is running in the remote cloud preview environment, it cannot be connected directly from your local browser.\n\n" +
                                                "• To test full device-to-device wireless sharing, build and export the APK of this app, then install it on your actual physical phone.\n" +
                                                "• When running on a real phone connected to your home Wi-Fi, the QR code and link will generate real local IPs (like 192.168.x.x) letting you share files instantly!",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Warning Info",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Connection Guide & Troubleshooting:",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "1. Use HTTP, NOT HTTPS: This is an offline local network server. If your browser automatically upgrades the connection to HTTPS and displays a 'secure connection failed' error, click 'Continue to site' or 'Go to site (unsafe)'. You may also disable the 'Always use secure connections' setting in your browser.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "2. Use IP Address instead of Short URL: If 'sds.local' fails to load (DNS error), it means your Wi-Fi router blocks multicast hostname discovery. Simply use the 'Host IP Address' link/QR instead.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                    } else {
                        Box(
                            modifier = Modifier
                                .height(200.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Starting wireless sharing server...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isSettingsExpanded = !isSettingsExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PC Connection Diagnostics & Custom IP",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Expand Settings",
                            modifier = Modifier.rotate(if (isSettingsExpanded) 180f else 0f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isSettingsExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Selected IP Address Input or Dropdown selector
                        Text(
                            text = "Webpage not opening on your PC? Try changing the Host IP below to correspond to your local Wi-Fi or active hotspot subnet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Selector of existing IP interfaces
                        Text(
                            text = "Choose IP subnet / interface:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        var isDropdownExpanded by remember { mutableStateOf(false) }
                        var customIpText by remember { mutableStateOf("") }
                        var isCustomIpEnabled by remember { mutableStateOf(false) }
                        var portText by remember { mutableStateOf(selectedPort.toString()) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { isDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isCustomIpEnabled) "Custom Override: $selectedIp" else "Primary: $selectedIp",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            }

                            DropdownMenu(
                                expanded = isDropdownExpanded,
                                onDismissRequest = { isDropdownExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                localIpAddresses.forEach { ip ->
                                    DropdownMenuItem(
                                        text = {
                                            val description = when {
                                                ip.startsWith("192.168.43.") -> "Mobile Hotspot Network"
                                                ip.startsWith("192.168.") -> "Local Wi-Fi Network"
                                                ip.startsWith("10.0.2.") -> "Android Emulator NAT Network"
                                                ip.startsWith("10.") -> "Private LAN Network"
                                                ip == "127.0.0.1" -> "Local loopback (Device-only)"
                                                else -> "Other Network Interface"
                                            }
                                            Text("$ip ($description)", style = MaterialTheme.typography.bodyMedium)
                                        },
                                        onClick = {
                                            isCustomIpEnabled = false
                                            onUpdateHost(ip, selectedPort)
                                            isDropdownExpanded = false
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Custom IP Override...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) },
                                    onClick = {
                                        isCustomIpEnabled = true
                                        isDropdownExpanded = false
                                    }
                                )
                            }
                        }

                        if (isCustomIpEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customIpText,
                                onValueChange = { customIpText = it },
                                label = { Text("Enter your PC-reachable Wi-Fi IP") },
                                placeholder = { Text("e.g. 192.168.1.10") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            if (customIpText.trim().isNotEmpty()) {
                                                onUpdateHost(customIpText.trim(), selectedPort)
                                            }
                                        }
                                    ) {
                                        Icon(imageVector = Icons.Default.Check, contentDescription = "Apply Custom IP", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Port control input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it },
                                label = { Text("Port Number") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Button(
                                onClick = {
                                    val port = portText.toIntOrNull() ?: 8080
                                    onUpdateHost(selectedIp, port)
                                    Toast.makeText(context, "Network settings refreshed!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(54.dp)
                            ) {
                                Text("Apply Settings")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "💡 Direct Troubleshooting Checks:",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "• If both devices are on the exact same Wi-Fi, try selecting a different Wi-Fi IP.\n" +
                                            "• If you have VPN active on either phone or computer, try temporarily disabling it.\n" +
                                            "• You can also turn on Mobile Hotspot on the phone, connect your PC to that hotspot, and select the Hotspot IP listed above (typically starting with 192.168.43.x). This is the most reliable, high-speed fallback!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        if (activeServerTransfers.isNotEmpty()) {
            item {
                Text(
                    text = "Active Live Transfers (${activeServerTransfers.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(activeServerTransfers.values.toList(), key = { it.key }) { transfer ->
                ActiveTransferCard(transfer = transfer)
            }
        }

        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Shared Files from Phone (${hostedFiles.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Button(
                        onClick = onAddFiles,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Files", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (hostedFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                        ) {
                            Row(modifier = Modifier.padding(2.dp)) {
                                val isList = viewMode == "LIST"
                                IconButton(
                                    onClick = { viewMode = "LIST" },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            if (isList) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        ).testTag("select_list_view")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = "List View",
                                        tint = if (isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { viewMode = "GRID" },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            if (!isList) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        ).testTag("select_grid_view")
                                ) {
                                    Column(
                                        modifier = Modifier.size(14.dp),
                                        verticalArrangement = Arrangement.SpaceBetween,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hostedFiles.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp)
                        )
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No files currently shared from this phone",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap 'Share Files' to make them instantly downloadable on Windows browser.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        } else {
            if (viewMode == "GRID") {
                val chunked = hostedFiles.chunked(2)
                items(chunked) { rowFiles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowFiles.forEach { share ->
                            Box(modifier = Modifier.weight(1f)) {
                                SharedFileGridItem(share = share, onRemove = { onRemoveShare(share) })
                            }
                        }
                        if (rowFiles.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(hostedFiles) { share ->
                    SharedFileRow(share = share, onRemove = { onRemoveShare(share) })
                }
            }
        }
    }
}

@Composable
fun SharedFileRow(
    share: OutgoingShare,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileThumbnail(
                uri = share.uri,
                name = share.name,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = share.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatBytes(share.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove share",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ScanPanel(
    hasPermission: Boolean,
    targetUrl: String?,
    status: ClientTransferStatus,
    preparedFiles: List<PreparedFile>,
    onRemovePreparedFile: (Uri) -> Unit,
    onClearPreparedFiles: () -> Unit,
    onTransferPreparedFiles: () -> Unit,
    onRequestPermission: () -> Unit,
    onQrScanned: (String) -> Unit,
    onSelectFiles: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The application requires access to the camera in order to scan the sharing QR code displayed on your Windows screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Grant Permission")
                    }
                }
            }
            return
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (targetUrl == null) {
                QrScannerView(
                    onQrScanned = onQrScanned,
                    modifier = Modifier.fillMaxSize()
                )

                // Instruction Overlay text labels
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Text(
                            text = "Point camera at QR on Windows screen",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                // Client UI states when connected to scanned target URL
                ConnectedClientPanel(
                    status = status,
                    targetUrl = targetUrl,
                    preparedFiles = preparedFiles,
                    onRemovePreparedFile = onRemovePreparedFile,
                    onClearPreparedFiles = onClearPreparedFiles,
                    onTransferPreparedFiles = onTransferPreparedFiles,
                    onSelectFiles = onSelectFiles,
                    onReset = onReset
                )
            }
        }
    }
}

@Composable
fun ConnectedClientPanel(
    status: ClientTransferStatus,
    targetUrl: String,
    preparedFiles: List<PreparedFile>,
    onRemovePreparedFile: (Uri) -> Unit,
    onClearPreparedFiles: () -> Unit,
    onTransferPreparedFiles: () -> Unit,
    onSelectFiles: () -> Unit,
    onReset: () -> Unit
) {
    var viewMode by remember { mutableStateOf("LIST") } // "LIST" or "GRID"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (status) {
            is ClientTransferStatus.Connected -> {
                if (preparedFiles.isEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Bound to PC",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = targetUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onSelectFiles,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("client_select_files_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Files to Transfer", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(onClick = onReset, modifier = Modifier.testTag("client_disconnect_button")) {
                        Text("Disconnect & Scan New", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // Show File Selection / Preparation Queue UI!
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Prepared Files Queue",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Connected to $targetUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Compact view mode selector
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                        ) {
                            Row(modifier = Modifier.padding(2.dp)) {
                                val isList = viewMode == "LIST"
                                IconButton(
                                    onClick = { viewMode = "LIST" },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            if (isList) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        ).testTag("client_select_list_view")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = "List View",
                                        tint = if (isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { viewMode = "GRID" },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                             if (!isList) MaterialTheme.colorScheme.primary else Color.Transparent,
                                             RoundedCornerShape(6.dp)
                                        ).testTag("client_select_grid_view")
                                ) {
                                    Column(
                                        modifier = Modifier.size(12.dp),
                                        verticalArrangement = Arrangement.SpaceBetween,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                            Surface(modifier = Modifier.size(5.dp), shape = RoundedCornerShape(1.dp), color = if (!isList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {}
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Card of prepared files list
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            // Header / stats
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val totalBytes = preparedFiles.sumOf { it.size }
                                Text(
                                    text = "${preparedFiles.size} items selected",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatBytes(totalBytes),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

                            // List of files (scrollable space inside the card)
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("client_prepared_files_list")
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (viewMode == "GRID") {
                                    val chunked = preparedFiles.chunked(2)
                                    items(chunked) { rowFiles ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowFiles.forEach { preparedFile ->
                                                Box(modifier = Modifier.weight(1f)) {
                                                    PreparedFileGridItem(
                                                        file = preparedFile,
                                                        onRemove = { onRemovePreparedFile(preparedFile.uri) }
                                                    )
                                                }
                                            }
                                            if (rowFiles.size < 2) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                } else {
                                    items(preparedFiles) { preparedFile ->
                                        PreparedFileRow(
                                            file = preparedFile,
                                            onRemove = { onRemovePreparedFile(preparedFile.uri) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Action block
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Outlined Add More button
                        OutlinedButton(
                            onClick = onSelectFiles,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("client_add_more_files_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Files", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                        }

                        // Solid Primary "Transfer Now" button
                        Button(
                            onClick = onTransferPreparedFiles,
                            modifier = Modifier
                                .weight(1.3f)
                                .height(50.dp)
                                .testTag("client_start_transfer_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send to PC", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    // Clear and Disconnect layout row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onClearPreparedFiles,
                            modifier = Modifier.testTag("client_clear_prepared_files_button")
                        ) {
                            Text("Clear Selection", color = MaterialTheme.colorScheme.error)
                        }

                        TextButton(
                            onClick = {
                                onClearPreparedFiles()
                                onReset()
                            },
                            modifier = Modifier.testTag("client_disconnect_from_pc_button")
                        ) {
                            Text("Disconnect", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                        }
                    }
                }
            }

            is ClientTransferStatus.Transferring -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .testTag("client_transferring_status_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Animated uploading icon
                        val infiniteTransition = rememberInfiniteTransition(label = "icon_anim")
                        val iconScale by infiniteTransition.animateFloat(
                            initialValue = 0.9f,
                            targetValue = 1.1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "scale"
                        )
                        
                        Box(
                            modifier = Modifier
                                .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Transferring Files to PC",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        
                        Text(
                            text = "${(status.progress * 100).toInt()}% Complete",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Dynamic linear progress bar
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = status.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            is ClientTransferStatus.Success -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Transfer Complete",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onSelectFiles,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Send More Files")
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = onReset) {
                    Text("Disconnect / Scan Again")
                }
            }

            is ClientTransferStatus.Error -> {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Connection Failed",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(onReset, shape = RoundedCornerShape(12.dp)) {
                    Text("Retry Scanning")
                }
            }

            else -> {}
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun HistoryPanel(
    historyList: List<TransferRecord>,
    onShare: (TransferRecord) -> Unit,
    onDelete: (TransferRecord) -> Unit,
    onBackup: (TransferRecord) -> Unit,
    onClearAll: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "History",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Transfer History (${historyList.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded && historyList.isNotEmpty()) {
                        IconButton(onClick = onClearAll, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear all",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (expanded) 180f else 0f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    if (historyList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No files transferred yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        ) {
                            items(historyList) { record ->
                                HistoryRecordRow(
                                    record = record,
                                    onShare = { onShare(record) },
                                    onDelete = { onDelete(record) },
                                    onBackup = { onBackup(record) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryRecordRow(
    record: TransferRecord,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onBackup: () -> Unit
) {
    val isIncoming = record.direction == "INCOMING"
    val existsLocal = !isIncoming || File(record.filePath).exists()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction indicator
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = if (isIncoming) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = if (isIncoming) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).rotate(if (isIncoming) 0f else 180f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (existsLocal) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatBytes(record.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isIncoming) "From: ${record.peerIp}" else "To: ${record.peerIp}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Cloud representation icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Cloud item status
                when (record.backupStatus) {
                    "UPLOADING" -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    "BACKED_UP" -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Cloud Backed Up",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    "FAILED" -> {
                        IconButton(onClick = onBackup, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Retry Backup",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    else -> {
                        // "NOT_BACKED_UP" -> clickable upload trigger helper
                        IconButton(onClick = onBackup, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Backup to Cloud",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (isIncoming && existsLocal) {
                    IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share received file",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete record",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CloudBackupPanel(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val backupConfigState = viewModel.cloudBackupConfig.collectAsState(initial = null)
    val testResult by viewModel.testResult.collectAsState()

    val config = backupConfigState.value ?: CloudBackupConfig()

    var selectedProvider by remember(config.provider) { mutableStateOf(config.provider) }
    var accessToken by remember(config.accessToken) { mutableStateOf(config.accessToken) }
    var folderName by remember(config.targetFolder) { mutableStateOf(config.targetFolder) }
    var isAutoBackup by remember(config.isAutoBackupEnabled) { mutableStateOf(config.isAutoBackupEnabled) }

    var showPassword by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Optional Cloud Backup",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Configure automated backups to securely store your files with cloud providers such as Google Drive or Dropbox.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                text = "1. Choose Provider",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Triple mapping structure (id, name, displayIcon)
                val providers = listOf(
                    Triple("NONE", "None", Icons.Default.Lock),
                    Triple("DROPBOX", "Dropbox", Icons.Default.Home),
                    Triple("GOOGLE_DRIVE", "Drive", Icons.Default.Share),
                    Triple("WEBHOOK", "Webhook", Icons.Default.Send)
                )

                providers.forEach { (provId, provName, icon) ->
                    val isSelected = selectedProvider == provId
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(68.dp)
                            .clickable { selectedProvider = provId },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = provName,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = provName,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        if (selectedProvider != "NONE") {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "2. Configure Integration Settings",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    // Auto Backup toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Backup on Shares",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Auto-save shared files to target space",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAutoBackup,
                            onCheckedChange = { isAutoBackup = it }
                        )
                    }

                    // Credentials input
                    val labelText = when (selectedProvider) {
                        "DROPBOX" -> "Dropbox Access Token"
                        "GOOGLE_DRIVE" -> "Google OAuth Access Token"
                        "WEBHOOK" -> "Authorization Header Key"
                        else -> "API Secrets / Tokens"
                    }

                    OutlinedTextField(
                        value = accessToken,
                        onValueChange = { accessToken = it },
                        label = { Text(labelText) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        singleLine = true
                    )

                    // Folder path or full target web hook address
                    val folderLabel = if (selectedProvider == "WEBHOOK") "Webhook full endpoint target URL" else "Target backup directory folder"
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text(folderLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Action controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.testCloudConnection(selectedProvider, accessToken, folderName) },
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(12.dp),
                            enabled = accessToken.isNotEmpty()
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Verify", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }

                        Button(
                            onClick = {
                                val newConfig = config.copy(
                                    provider = selectedProvider,
                                    accessToken = accessToken,
                                    targetFolder = folderName,
                                    isAutoBackupEnabled = isAutoBackup
                                )
                                viewModel.saveBackupConfig(newConfig)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save Config", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    // Test output feedback alerts
                    testResult?.let { result ->
                        val isSuccess = result == "SUCCESS"
                        val isTesting = result == "Testing..."

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSuccess) Color(0xFF10B981).copy(alpha = 0.08f)
                                else if (isTesting) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = when {
                                        isTesting -> "Sending diagnostic request file to cloud..."
                                        isSuccess -> "Cloud account verified successfully! Created file: $folderName/Diagnostics_Connection_Test.txt"
                                        else -> result
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = if (isSuccess) Color(0xFF10B981) else if (isTesting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Current Status",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Connection status:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = config.connectionStatus,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (config.connectionStatus.contains("Connected")) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                )
                            }
                            if (config.lastBackupTime > 0L) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Last successful backup:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val formattedTime = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(config.lastBackupTime))
                                    Text(formattedTime, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Global emulator detection helper
fun isEmulator(): Boolean {
    val brand = android.os.Build.BRAND
    val device = android.os.Build.DEVICE
    val fingerprint = android.os.Build.FINGERPRINT
    val hardware = android.os.Build.HARDWARE
    val model = android.os.Build.MODEL
    val manufacturer = android.os.Build.MANUFACTURER
    val product = android.os.Build.PRODUCT
    return (brand.startsWith("generic") && device.startsWith("generic"))
            || fingerprint.startsWith("generic")
            || fingerprint.startsWith("unknown")
            || hardware.contains("goldfish")
            || hardware.contains("ranchu")
            || model.contains("google_sdk")
            || model.contains("Emulator")
            || model.contains("Android SDK built for x86")
            || manufacturer.contains("Genymotion")
            || product.contains("sdk_google")
            || product.contains("google_sdk")
            || product.contains("sdk")
            || product.contains("sdk_x86")
            || product.contains("vbox86p")
            || product.contains("emulator")
            || product.contains("simulator")
}

// Global copy utility helper
fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("scanned_url", text)
    clipboard.setPrimaryClip(clip)
}

// Global share utility helper using compliant FileProviders
fun shareFile(context: Context, record: TransferRecord) {
    try {
        val file = File(record.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File no longer exists on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share File via"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// Format byte size representation helper
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Bytes"
    val k = 1024L
    val sizes = arrayOf("Bytes", "KB", "MB", "GB")
    val i = (Math.log(bytes.toDouble()) / Math.log(k.toDouble())).toInt()
    return String.format("%.1f %s", bytes / Math.pow(k.toDouble(), i.toDouble()), sizes[i])
}

@Composable
fun ActiveTransferCard(transfer: ActiveTransfer) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("active_transfer_card_${transfer.key}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Direction icon
                val isSending = transfer.direction == "SENDING"
                val icon = if (isSending) Icons.Default.Send else Icons.Default.Add
                val color = if (isSending) MaterialTheme.colorScheme.primary else Color(0xFF10B981)
                
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(color.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSending) "Sending to PC (${transfer.peerIp})" else "Receiving from PC (${transfer.peerIp})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        
                        Text(
                            text = "${(transfer.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = color
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            
            // Progress Bar
            LinearProgressIndicator(
                progress = { transfer.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = if (transfer.direction == "SENDING") MaterialTheme.colorScheme.primary else Color(0xFF10B981),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Transferred vs total size label
            val progressText = if (transfer.totalBytes > 0) {
                "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.totalBytes)}"
            } else {
                "${formatBytes(transfer.bytesTransferred)} transferred"
            }
            
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
fun PreparedFileRow(
    file: PreparedFile,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("prepared_file_${file.name}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileThumbnail(
                uri = file.uri,
                name = file.name,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatBytes(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("remove_prepared_file_${file.name}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove prepared file",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

fun isImageFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
}

fun isVideoFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".webm") || lower.endsWith(".3gp")
}

@Composable
fun FileThumbnail(
    uri: Uri,
    name: String,
    modifier: Modifier = Modifier
) {
    val isImg = isImageFile(name)
    val ext = name.substringAfterLast('.', "").uppercase()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        if (isImg) {
            AsyncImage(
                model = uri,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = rememberAsyncImagePainter(model = null)
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(2.dp)
            ) {
                Icon(
                    imageVector = when {
                        isVideoFile(name) -> Icons.Default.PlayArrow
                        else -> Icons.Default.List
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                if (ext.isNotEmpty() && ext.length <= 4) {
                    Text(
                        text = ext,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun SharedFileGridItem(
    share: OutgoingShare,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(130.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                FileThumbnail(
                    uri = share.uri,
                    name = share.name,
                    modifier = Modifier.size(60.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = share.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = formatBytes(share.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
            }
            
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clickable { onRemove() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove share",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PreparedFileGridItem(
    file: PreparedFile,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(130.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                FileThumbnail(
                    uri = file.uri,
                    name = file.name,
                    modifier = Modifier.size(60.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = formatBytes(file.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
            }
            
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clickable { onRemove() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove file",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedAboutLogo() {
    val infiniteTransition = rememberInfiniteTransition(label = "AboutGlow")

    // Pulse animation for the soft radial glow
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // Spin animation for the outer technical rotating ring
    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRotation"
    )
    // Spin animation for the inner tech ring in reverse
    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(5500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRotation"
    )

    // Floating vertical translate animation for the main logo central sphere
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatOffset"
    )

    Box(
        modifier = Modifier
            .size(130.dp)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        // Glowing halo underlying the rings
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = glowScale, scaleY = glowScale)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                    shape = CircleShape
                )
        )

        // Outer tech ring (dotted/dashed border simulation using a thin transparent rotation circle with border)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(outerRotation)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), shape = CircleShape)
        )

        // Second inner rings offset (reverse decoration)
        Box(
            modifier = Modifier
                .fillMaxSize(0.78f)
                .rotate(innerRotation)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                    shape = CircleShape
                )
        )

        // Floating central shield/share symbol content
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(54.dp)
                .offset(y = floatOffset.dp),
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
fun AppAboutDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    text = "Acknowledge",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        title = null, // Custom styled title inside content column
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Beautiful Animated Composable Logo
                AnimatedAboutLogo()
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Text(
                    text = "About QR File Share",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = "This application handles seamless, wireless offline file sharing using localized P2P network capabilities.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Feature Highlight Card: Unlimited & Offline Private
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "⚡ Limitless & Private",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This app has absolutely no size limits and doesn't share your files anywhere. Everything is self-contained and confidential.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ALERT Card: "100% Free - Warning"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp).padding(top = 1.dp)
                        )
                        Column {
                            Text(
                                text = "100% Completely Free",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "This app does NOT share or ask for money. If someone shared this utility with you and is requesting money, please do NOT send them anything.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Developer contact segment
                Text(
                    text = "Need Assistance? Contact Developer",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .clickable {
                            copyToClipboard(context, "sagarsainiknp@gmail.com")
                            Toast.makeText(context, "Developer email copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "Email",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "sagarsainiknp@gmail.com",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Copy Icon",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    )
}


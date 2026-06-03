package com.example

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.transferDao()

    // Real-time network connection monitoring
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isNetworkConnected = MutableStateFlow(false)
    val isNetworkConnected: StateFlow<Boolean> = _isNetworkConnected.asStateFlow()

    private fun isNetworkCapable(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isCurrentlyConnected(capabilities: NetworkCapabilities?): Boolean {
        try {
            val ips = fileServer.getAllLocalIpAddresses()
            if (ips.isNotEmpty() && ips.any { it != "127.0.0.1" }) {
                return true
            }
        } catch (e: Exception) {
            // fallback
        }
        if (capabilities != null && isNetworkCapable(capabilities)) {
            return true
        }
        return false
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            _isNetworkConnected.value = isCurrentlyConnected(capabilities)
        }

        override fun onLost(network: Network) {
            _isNetworkConnected.value = checkInitialNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _isNetworkConnected.value = isCurrentlyConnected(networkCapabilities)
        }
    }

    private fun checkInitialNetworkStatus(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            isCurrentlyConnected(capabilities)
        } catch (e: Exception) {
            try {
                val ips = fileServer.getAllLocalIpAddresses()
                ips.isNotEmpty() && ips.any { it != "127.0.0.1" }
            } catch (ex: Exception) {
                false
            }
        }
    }

    val transferHistory = dao.getAllTransfers()
    val cloudBackupConfig = dao.getBackupConfigFlux()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    // UI Modes: "HOST" (serve files), "SCAN" (send to scanned IP), or "CLOUD" (configure backup)
    private val _uiMode = MutableStateFlow("HOST")
    val uiMode: StateFlow<String> = _uiMode.asStateFlow()

    // Host Server URL (e.g. http://192.168.1.5:8080)
    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    // Active local IP addresses detected on the device
    private val _localIpAddresses = MutableStateFlow<List<String>>(emptyList())
    val localIpAddresses: StateFlow<List<String>> = _localIpAddresses.asStateFlow()

    // Currently selected Host IP or override
    private val _selectedIp = MutableStateFlow<String>("127.0.0.1")
    val selectedIp: StateFlow<String> = _selectedIp.asStateFlow()

    // Current Server Port
    private val _selectedPort = MutableStateFlow<Int>(8817)
    val selectedPort: StateFlow<Int> = _selectedPort.asStateFlow()

    // Hosted Files list
    private val _hostedFiles = MutableStateFlow<List<OutgoingShare>>(emptyList())
    val hostedFiles: StateFlow<List<OutgoingShare>> = _hostedFiles.asStateFlow()

    // Connection state for Client/Scan Mode
    private val _clientStatus = MutableStateFlow<ClientTransferStatus>(ClientTransferStatus.Idle)
    val clientStatus: StateFlow<ClientTransferStatus> = _clientStatus.asStateFlow()

    // File selection / preparation queue before pushing to PC
    private val _preparedFiles = MutableStateFlow<List<PreparedFile>>(emptyList())
    val preparedFiles: StateFlow<List<PreparedFile>> = _preparedFiles.asStateFlow()

    // Target Server Scanned (e.g. http://192.168.1.15:8080)
    private val _targetUrl = MutableStateFlow<String?>(null)
    val targetUrl: StateFlow<String?> = _targetUrl.asStateFlow()

    // Active local server transfers
    val activeServerTransfers: StateFlow<Map<String, ActiveTransfer>> get() = fileServer.activeTransfers.asStateFlow()

    // Custom URL Alias for local hosting (e.g., http://sds.local:8080)
    private val _customUrlAlias = MutableStateFlow<String>("sds")
    val customUrlAlias: StateFlow<String> = _customUrlAlias.asStateFlow()

    // Global Tunneling State Flows (to share files globally beyond the local network)
    val globalTunnelUrl: StateFlow<String?> = GlobalTunnelManager.tunnelUrl
    val isGlobalTunnelConnecting: StateFlow<Boolean> = GlobalTunnelManager.isConnecting
    val globalTunnelError: StateFlow<String?> = GlobalTunnelManager.tunnelError

    fun startGlobalSharingTunnel() {
        val port = _selectedPort.value
        if (port > 0) {
            GlobalTunnelManager.startTunnel(port, viewModelScope)
        }
    }

    fun stopGlobalSharingTunnel() {
        GlobalTunnelManager.stopTunnel()
    }

    // Theme Customizations
    private val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "ALWAYS_DARK") ?: "ALWAYS_DARK")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _themeColor = MutableStateFlow(prefs.getString("theme_color", "SYSTEM_DYNAMIC") ?: "SYSTEM_DYNAMIC")
    val themeColor: StateFlow<String> = _themeColor.asStateFlow()

    fun updateThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun updateThemeColor(color: String) {
        _themeColor.value = color
        prefs.edit().putString("theme_color", color).apply()
    }

    // Real-time track of connected device clients
    val connectedDevices: StateFlow<Map<String, ConnectedClient>> get() = fileServer.connectedDevices.asStateFlow()

    private val fileServer = HttpFileServer(
        context = context,
        scope = viewModelScope,
        onIncomingFile = { file, originalName, senderIp ->
            saveIncomingRecord(file, originalName, senderIp)
        }
    )

    init {
        // Initialize network status and register default callback
        _isNetworkConnected.value = checkInitialNetworkStatus()
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to register network callback", e)
        }
        // Automatically start local file server in Host Mode by default
        startLocalServer()
    }

    fun setUiMode(mode: String) {
        _uiMode.value = mode
    }

    fun clearTargetUrl() {
        _targetUrl.value = null
        _clientStatus.value = ClientTransferStatus.Idle
    }

    fun startLocalServer() {
        viewModelScope.launch(Dispatchers.IO) {
            var port = _selectedPort.value
            if (port == 0) {
                regeneratePort()
                port = _selectedPort.value
            }
            var url = fileServer.start(port, _customUrlAlias.value)
            
            // Port retry loop: if binding fails, try up to 5 alternative ports sequentially
            var retries = 5
            while (url == null && retries > 0) {
                Log.w("MainViewModel", "Port $port is busy or unavailable. Trying another random port...")
                regeneratePort()
                port = _selectedPort.value
                url = fileServer.start(port, _customUrlAlias.value)
                retries--
            }

            val ipAddresses = fileServer.getAllLocalIpAddresses()
            _localIpAddresses.value = ipAddresses
            
            val lastSelectedIp = _selectedIp.value
            val ipToUse = if (lastSelectedIp != "127.0.0.1" && lastSelectedIp in ipAddresses) {
                lastSelectedIp
            } else {
                val autoDetectedIp = fileServer.getLocalIpAddress()
                _selectedIp.value = autoDetectedIp
                autoDetectedIp
            }
            _serverUrl.value = if (url != null) "http://$ipToUse:$port" else null

            // If we successfully resolved at least one non-loopback IP, we are connected!
            if (ipAddresses.isNotEmpty() && ipAddresses.any { it != "127.0.0.1" }) {
                _isNetworkConnected.value = true
            }
        }
    }

    fun updateHostAddressAndPort(ip: String, port: Int) {
        _selectedIp.value = ip
        _selectedPort.value = port
        viewModelScope.launch(Dispatchers.IO) {
            fileServer.start(port, _customUrlAlias.value)
            _serverUrl.value = "http://$ip:$port"
        }
    }

    fun stopLocalServer() {
        stopGlobalSharingTunnel()
        fileServer.stop()
        _serverUrl.value = null
        synchronized(fileServer.sharedFiles) {
            fileServer.sharedFiles.clear()
        }
        _hostedFiles.value = emptyList()
        // DO NOT regenerate port automatically when server is stopped.
        // This keeps the IP and port extremely stable and predictable.
    }

    fun regeneratePort() {
        val randomPort = (8000..9000).random()
        _selectedPort.value = randomPort
    }

    fun updateCustomUrlAlias(newAlias: String) {
        val cleanAlias = newAlias.trim().lowercase().replace(Regex("[^a-z0-9-]"), "")
        if (cleanAlias.isNotEmpty()) {
            _customUrlAlias.value = cleanAlias
            if (fileServer.isRunning()) {
                startLocalServer()
            }
        }
    }

    // Add selected file from Content Picker to host/transmit
    fun addHostShare(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val (name, size) = getFileInfo(context, uri)
            val share = OutgoingShare(name, size, uri)
            synchronized(fileServer.sharedFiles) {
                fileServer.sharedFiles.add(share)
                _hostedFiles.value = fileServer.sharedFiles.toList()
            }
        }
    }

    fun removeHostShare(share: OutgoingShare) {
        synchronized(fileServer.sharedFiles) {
            fileServer.sharedFiles.remove(share)
            _hostedFiles.value = fileServer.sharedFiles.toList()
        }
    }

    private fun getFileInfo(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unknown_file"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error reading file info from URI", e)
        }
        if (name == "unknown_file") {
            name = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
        }
        return Pair(name, size)
    }

    private fun saveIncomingRecord(file: File, originalName: String, senderIp: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val config = dao.getBackupConfig()
            val isAutoEnabled = config?.isAutoBackupEnabled == true && config.provider != "NONE" && config.accessToken.isNotEmpty()

            val record = TransferRecord(
                fileName = originalName,
                fileSize = file.length(),
                direction = "INCOMING",
                timestamp = System.currentTimeMillis(),
                filePath = file.absolutePath,
                peerIp = senderIp,
                backupStatus = if (isAutoEnabled) "UPLOADING" else "NOT_BACKED_UP",
                backupTarget = if (isAutoEnabled) config.provider else "LOCAL"
            )
            val rowId = dao.insertTransfer(record).toInt()

            if (isAutoEnabled && config != null) {
                val updatedRecord = record.copy(id = rowId)
                val isSuccess = CloudBackupService.backupFile(
                    context = context,
                    file = file,
                    originalName = originalName,
                    provider = config.provider,
                    accessToken = config.accessToken,
                    folderName = config.targetFolder
                )
                val finalRecord = updatedRecord.copy(
                    backupStatus = if (isSuccess) "BACKED_UP" else "FAILED"
                )
                dao.insertTransfer(finalRecord)
                if (isSuccess) {
                    dao.saveBackupConfig(config.copy(lastBackupTime = System.currentTimeMillis()))
                }
            }
        }
    }

    // Client/Scan Mode: Scanned a QR containing Windows URL, now ready to upload multiple files
    fun onTargetScanned(scannedUrl: String) {
        // Simple validation of URL structure
        if (scannedUrl.startsWith("http://") || scannedUrl.startsWith("https://")) {
            _targetUrl.value = scannedUrl
            _clientStatus.value = ClientTransferStatus.Connected(scannedUrl)
        } else {
            _clientStatus.value = ClientTransferStatus.Error("Scanned text is not a valid transfer endpoint: $scannedUrl")
        }
    }

    fun pushFilesToTarget(uris: List<Uri>) {
        val target = _targetUrl.value ?: return
        if (uris.isEmpty()) return

        _clientStatus.value = ClientTransferStatus.Transferring(0f, "Preparing uploads...")

        viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS) // Very generous for large files
                .build()

            var successCount = 0
            val totalFiles = uris.size

            uris.forEachIndexed { index, uri ->
                val (name, size) = getFileInfo(context, uri)
                _clientStatus.value = ClientTransferStatus.Transferring(
                    progress = index.toFloat() / totalFiles,
                    statusText = "Sending file ${index + 1} of $totalFiles: $name"
                )

                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        Log.e("MainViewModel", "Could not open stream for URI: $uri")
                        return@forEachIndexed
                    }

                    val requestBody = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength() = size
                        override fun writeTo(sink: okio.BufferedSink) {
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var bytesWritten = 0L
                            inputStream.use { stream ->
                                while (stream.read(buffer).also { bytesRead = it } != -1) {
                                    sink.write(buffer, 0, bytesRead)
                                    bytesWritten += bytesRead
                                    val fileProgress = if (size > 0) bytesWritten.toFloat() / size else 0f
                                    val overallProgress = (index + fileProgress) / totalFiles
                                    val formattedSize = String.format("%.1f", size / (1024f * 1024f))
                                    val formattedSent = String.format("%.1f", bytesWritten / (1024f * 1024f))
                                    _clientStatus.value = ClientTransferStatus.Transferring(
                                        progress = overallProgress,
                                        statusText = "Sending file ${index + 1} of $totalFiles: $name ($formattedSent MB / $formattedSize MB, ${(fileProgress * 100).toInt()}%)"
                                    )
                                }
                            }
                        }
                    }

                    // Append target upload context URL
                    val baseTarget = target.trimEnd('/')
                    val uploadUrl = if (baseTarget.endsWith("/upload")) {
                        "$baseTarget?name=${Uri.encode(name)}"
                    } else {
                        val separator = if (baseTarget.contains("?")) "&" else "?"
                        "$baseTarget/upload${separator}name=${Uri.encode(name)}"
                    }

                    val request = Request.Builder()
                        .url(uploadUrl)
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            successCount++
                            // Write to transfer history!
                            val config = dao.getBackupConfig()
                            val isAutoEnabled = config?.isAutoBackupEnabled == true && config.provider != "NONE" && config.accessToken.isNotEmpty()

                            val record = TransferRecord(
                                fileName = name,
                                fileSize = size,
                                direction = "OUTGOING",
                                timestamp = System.currentTimeMillis(),
                                filePath = uri.toString(),
                                peerIp = getHostFromUrl(target),
                                backupStatus = if (isAutoEnabled) "UPLOADING" else "NOT_BACKED_UP",
                                backupTarget = if (isAutoEnabled) config.provider else "LOCAL"
                            )
                            val rowId = dao.insertTransfer(record).toInt()

                            if (isAutoEnabled && config != null) {
                                val updatedRecord = record.copy(id = rowId)
                                val isSuccess = CloudBackupService.backupUri(
                                    context = context,
                                    uri = uri,
                                    originalName = name,
                                    size = size,
                                    provider = config.provider,
                                    accessToken = config.accessToken,
                                    folderName = config.targetFolder
                                )
                                val finalRecord = updatedRecord.copy(
                                    backupStatus = if (isSuccess) "BACKED_UP" else "FAILED"
                                )
                                dao.insertTransfer(finalRecord)
                                if (isSuccess) {
                                    dao.saveBackupConfig(config.copy(lastBackupTime = System.currentTimeMillis()))
                                }
                            }
                        } else {
                            Log.e("MainViewModel", "Upload unsuccessful: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Upload error for file $name", e)
                }
            }

            if (successCount == totalFiles) {
                _clientStatus.value = ClientTransferStatus.Success("Transferred all $successCount file(s) successfully!")
                _preparedFiles.value = emptyList()
            } else {
                _clientStatus.value = ClientTransferStatus.Success("Transferred $successCount of $totalFiles file(s). Some failed.")
                _preparedFiles.value = emptyList()
            }
        }
    }

    private fun getHostFromUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    fun deleteHistoryRecord(record: TransferRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTransfer(record)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
        }
    }

    // Cloud configuration & operation methods
    fun saveBackupConfig(config: CloudBackupConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.saveBackupConfig(config)
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun testCloudConnection(provider: String, accessToken: String, folderName: String) {
        _testResult.value = "Testing..."
        viewModelScope.launch(Dispatchers.IO) {
            val res = CloudBackupService.testConnection(
                context = context,
                provider = provider,
                accessToken = accessToken,
                folderName = folderName
            )
            if (res.isSuccess) {
                _testResult.value = "SUCCESS"
                val current = dao.getBackupConfig() ?: CloudBackupConfig()
                dao.saveBackupConfig(
                    current.copy(
                        provider = provider,
                        accessToken = accessToken,
                        targetFolder = folderName,
                        connectionStatus = "Connected successfully!",
                        lastBackupTime = System.currentTimeMillis()
                    )
                )
            } else {
                val errorMsg = res.exceptionOrNull()?.message ?: "Refused validation."
                _testResult.value = "FAILED: $errorMsg"
                val current = dao.getBackupConfig() ?: CloudBackupConfig()
                dao.saveBackupConfig(
                    current.copy(
                        provider = provider,
                        accessToken = accessToken,
                        targetFolder = folderName,
                        connectionStatus = "Failed: $errorMsg"
                    )
                )
            }
        }
    }

    fun manualBackup(record: TransferRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            val config = dao.getBackupConfig() ?: return@launch
            if (config.provider == "NONE" || config.accessToken.isEmpty()) {
                Log.e("MainViewModel", "Manual backup failed: Provider details not set")
                return@launch
            }

            val uploadingRecord = record.copy(backupStatus = "UPLOADING", backupTarget = config.provider)
            dao.insertTransfer(uploadingRecord)

            val isSuccess = if (record.direction == "INCOMING") {
                val file = File(record.filePath)
                CloudBackupService.backupFile(
                    context = context,
                    file = file,
                    originalName = record.fileName,
                    provider = config.provider,
                    accessToken = config.accessToken,
                    folderName = config.targetFolder
                )
            } else {
                val uri = Uri.parse(record.filePath)
                CloudBackupService.backupUri(
                    context = context,
                    uri = uri,
                    originalName = record.fileName,
                    size = record.fileSize,
                    provider = config.provider,
                    accessToken = config.accessToken,
                    folderName = config.targetFolder
                )
            }

            val finalRecord = record.copy(
                backupStatus = if (isSuccess) "BACKED_UP" else "FAILED",
                backupTarget = config.provider
            )
            dao.insertTransfer(finalRecord)

            if (isSuccess) {
                dao.saveBackupConfig(config.copy(lastBackupTime = System.currentTimeMillis()))
            }
        }
    }

    fun addPreparedFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val newList = _preparedFiles.value.toMutableList()
            uris.forEach { uri ->
                if (newList.none { it.uri == uri }) {
                    val (name, size) = getFileInfo(context, uri)
                    newList.add(PreparedFile(uri, name, size))
                }
            }
            _preparedFiles.value = newList
        }
    }

    fun removePreparedFile(uri: Uri) {
        _preparedFiles.value = _preparedFiles.value.filter { it.uri != uri }
    }

    fun clearPreparedFiles() {
        _preparedFiles.value = emptyList()
    }

    fun transferPreparedFiles() {
        val uris = _preparedFiles.value.map { it.uri }
        if (uris.isNotEmpty()) {
            pushFilesToTarget(uris)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to unregister network callback", e)
        }
        fileServer.stop()
    }
}

data class PreparedFile(
    val uri: Uri,
    val name: String,
    val size: Long
)

sealed class ClientTransferStatus {
    object Idle : ClientTransferStatus()
    data class Connected(val targetUrl: String) : ClientTransferStatus()
    data class Transferring(val progress: Float, val statusText: String) : ClientTransferStatus()
    data class Success(val message: String) : ClientTransferStatus()
    data class Error(val message: String) : ClientTransferStatus()
}

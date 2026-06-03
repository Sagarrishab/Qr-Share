package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

data class OutgoingShare(
    val name: String,
    val size: Long,
    val uri: Uri
)

data class ActiveTransfer(
    val key: String,
    val fileName: String,
    val direction: String, // "RECEIVING" or "SENDING"
    val progress: Float,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val peerIp: String
)

data class ConnectedClient(
    val ip: String,
    val userAgent: String,
    val lastActiveTime: Long
)

class HttpFileServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onIncomingFile: (File, String, String) -> Unit // (file, originalName, senderIp)
) {
    private var server: HttpServer? = null
    private val executor = Executors.newFixedThreadPool(4)
    val sharedFiles = mutableListOf<OutgoingShare>()
    var currentPort: Int = 8080
        private set

    // NSD mDNS properties
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    // Real-time track of connected devices
    val connectedDevices = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ConnectedClient>>(emptyMap())

    val activeTransfers = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ActiveTransfer>>(emptyMap())

    fun registerActiveClient(ip: String, userAgent: String) {
        if (ip == "127.0.0.1" || ip == "localhost" || ip.isBlank() || ip.contains("0:0:0:0")) return
        synchronized(connectedDevices) {
            val map = connectedDevices.value.toMutableMap()
            map[ip] = ConnectedClient(ip, userAgent, System.currentTimeMillis())
            connectedDevices.value = map
        }
    }

    fun updateServerTransfer(
        key: String,
        fileName: String,
        direction: String,
        progress: Float,
        bytesTransferred: Long,
        totalBytes: Long,
        peerIp: String
    ) {
        synchronized(activeTransfers) {
            val map = activeTransfers.value.toMutableMap()
            map[key] = ActiveTransfer(key, fileName, direction, progress, bytesTransferred, totalBytes, peerIp)
            activeTransfers.value = map
        }
    }

    fun removeServerTransfer(key: String) {
        synchronized(activeTransfers) {
            val map = activeTransfers.value.toMutableMap()
            map.remove(key)
            activeTransfers.value = map
        }
    }

    fun start(port: Int = 8080, nsdAlias: String = "sds"): String? {
        val ip = getLocalIpAddress()
        if (ip == "127.0.0.1") {
            Log.e("HttpFileServer", "Could not get local Wi-Fi IP address")
        }
        
        try {
            stop()
            currentPort = port
            server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0).apply {
                createContext("/", DashboardHandler())
                createContext("/shared_files_json", FilesJsonHandler())
                createContext("/download", DownloadHandler())
                createContext("/upload", UploadHandler())
                setExecutor(executor)
                start()
            }
            registerNsdService(nsdAlias, port)
            val serverUrl = "http://$ip:$port"
            Log.d("HttpFileServer", "Server started successfully at $serverUrl")
            return serverUrl
        } catch (e: Exception) {
            Log.e("HttpFileServer", "Error starting server: ${e.message}", e)
            return null
        }
    }

    fun stop() {
        try {
            unregisterNsdService()
            server?.stop(0)
            server = null
            activeTransfers.value = emptyMap()
            connectedDevices.value = emptyMap()
        } catch (e: Exception) {
            Log.e("HttpFileServer", "Error stopping server", e)
        }
    }

    fun registerNsdService(alias: String, port: Int) {
        try {
            unregisterNsdService()
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = alias
                serviceType = "_http._tcp."
                setPort(port)
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d("HttpFileServer", "NSD Service registered successfully: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e("HttpFileServer", "NSD Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d("HttpFileServer", "NSD Service unregistered successfully")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e("HttpFileServer", "NSD Unregistration failed: $errorCode")
                }
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("HttpFileServer", "Error registering NSD: ${e.message}")
        }
    }

    fun unregisterNsdService() {
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.e("HttpFileServer", "Error unregistering NSD: ${e.message}")
        } finally {
            registrationListener = null
            nsdManager = null
        }
    }

    fun isRunning(): Boolean = server != null

    fun getLocalIpAddress(): String {
        return getAllLocalIpAddresses().firstOrNull() ?: "127.0.0.1"
    }

    fun getAllLocalIpAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (inetAddress in addresses) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val ip = inetAddress.hostAddress
                        if (ip != null) {
                            list.add(ip)
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("HttpFileServer", "Error finding local IP addresses", ex)
        }
        val sortedList = list.sortedWith(compareByDescending { ip ->
            when {
                ip.startsWith("192.168.") -> 4
                ip.startsWith("172.") -> 3
                ip.startsWith("10.") -> 2
                else -> 1
            }
        })
        return if (sortedList.isEmpty()) listOf("127.0.0.1") else sortedList
    }

    // Handlers
    private inner class DashboardHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            val clientIp = exchange.remoteAddress.address.hostAddress ?: ""
            val userAgent = exchange.requestHeaders["User-Agent"]?.firstOrNull()?.toString() ?: ""
            registerActiveClient(clientIp, userAgent)
            val response = getHtmlDashboard()
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class FilesJsonHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            val clientIp = exchange.remoteAddress.address.hostAddress ?: ""
            val userAgent = exchange.requestHeaders["User-Agent"]?.firstOrNull()?.toString() ?: ""
            registerActiveClient(clientIp, userAgent)
            // Convert to a clean JSON array
            val jsonBuilder = StringBuilder("[")
            synchronized(sharedFiles) {
                sharedFiles.forEachIndexed { i, file ->
                    jsonBuilder.append("{")
                        .append("\"name\":\"").append(escapeJson(file.name)).append("\",")
                        .append("\"size\":").append(file.size)
                        .append("}")
                    if (i < sharedFiles.size - 1) jsonBuilder.append(",")
                }
            }
            jsonBuilder.append("]")

            val bytes = jsonBuilder.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class DownloadHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            val clientIp = exchange.remoteAddress.address.hostAddress ?: ""
            val userAgent = exchange.requestHeaders["User-Agent"]?.firstOrNull()?.toString() ?: ""
            registerActiveClient(clientIp, userAgent)

            val query = exchange.requestURI.query ?: ""
            val indexParam = query.split("&")
                .firstOrNull { it.startsWith("index=") }
                ?.substringAfter("index=")
                ?.toIntOrNull()

            if (indexParam == null) {
                val errorMsg = "Missing file index"
                exchange.sendResponseHeaders(400, errorMsg.length.toLong())
                exchange.responseBody.use { it.write(errorMsg.toByteArray()) }
                return
            }

            val share = synchronized(sharedFiles) {
                if (indexParam in 0 until sharedFiles.size) sharedFiles[indexParam] else null
            }

            if (share == null) {
                val errorMsg = "File not found at index $indexParam"
                exchange.sendResponseHeaders(404, errorMsg.length.toLong())
                exchange.responseBody.use { it.write(errorMsg.toByteArray()) }
                return
            }

            val senderIp = exchange.remoteAddress.address.hostAddress ?: "Unknown"
            val transferKey = "send_${share.name}_${System.currentTimeMillis()}"
            try {
                // Stream directly from ContentResolver uri
                context.contentResolver.openInputStream(share.uri)?.use { inputStream ->
                    val headers = exchange.responseHeaders
                    headers.set("Content-Disposition", "attachment; filename=\"${share.name}\"")
                    headers.set("Content-Type", "application/octet-stream")
                    exchange.sendResponseHeaders(200, share.size)
                    
                    val buffer = ByteArray(64 * 1024)
                    val outputStream = exchange.responseBody
                    var bytesRead: Int
                    var bytesTransferred = 0L
                    updateServerTransfer(transferKey, share.name, "SENDING", 0f, 0L, share.size, senderIp)

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesTransferred += bytesRead
                        val progress = if (share.size > 0) bytesTransferred.toFloat() / share.size else 0f
                        updateServerTransfer(transferKey, share.name, "SENDING", progress, bytesTransferred, share.size, senderIp)
                    }
                    outputStream.flush()
                } ?: run {
                    throw Exception("Could not open stream for URI")
                }
            } catch (e: Exception) {
                Log.e("HttpFileServer", "Error downloading file: ${e.message}", e)
                val errorMsg = "Error streaming file: ${e.message}"
                exchange.sendResponseHeaders(500, errorMsg.length.toLong())
                exchange.responseBody.use { it.write(errorMsg.toByteArray()) }
            } finally {
                removeServerTransfer(transferKey)
            }
        }
    }

    private inner class UploadHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            val clientIp = exchange.remoteAddress.address.hostAddress ?: ""
            val userAgent = exchange.requestHeaders["User-Agent"]?.firstOrNull()?.toString() ?: ""
            registerActiveClient(clientIp, userAgent)

            val query = exchange.requestURI.query ?: ""
            var fileName = query.split("&")
                .firstOrNull { it.startsWith("name=") }
                ?.substringAfter("name=")
                ?.let { Uri.decode(it) } ?: "uploaded_file_${System.currentTimeMillis()}"

            // Clean filename to prevent path traversal
            fileName = File(fileName).name

            val senderIp = exchange.remoteAddress.address.hostAddress ?: "Unknown"
            val contentLength = exchange.requestHeaders["content-length"]?.toLongOrNull() ?: 0L
            val transferKey = "recv_${fileName}_${System.currentTimeMillis()}"

            try {
                val receivedDir = File(context.getExternalFilesDir(null), "Received").apply {
                    if (!exists()) mkdirs()
                }
                
                // Add tiny disambiguation if file already exists
                var targetFile = File(receivedDir, fileName)
                if (targetFile.exists()) {
                    val baseName = fileName.substringBeforeLast(".")
                    val extension = fileName.substringAfterLast(".", "")
                    val fileSuffix = if (extension.isNotEmpty()) ".$extension" else ""
                    var i = 1
                    while (targetFile.exists()) {
                        targetFile = File(receivedDir, "${baseName}_$i$fileSuffix")
                        i++
                    }
                    fileName = targetFile.name
                    targetFile = File(receivedDir, fileName)
                }

                updateServerTransfer(transferKey, fileName, "RECEIVING", 0f, 0L, contentLength, senderIp)

                val inputStream = exchange.requestBody
                FileOutputStream(targetFile).use { fos ->
                    val bos = BufferedOutputStream(fos)
                    val buffer = ByteArray(128 * 1024) // 128KB buffer
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        bos.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        val progress = if (contentLength > 0) totalBytesRead.toFloat() / contentLength else 0f
                        updateServerTransfer(transferKey, fileName, "RECEIVING", progress, totalBytesRead, contentLength, senderIp)
                    }
                    bos.flush()
                }

                // Notify callback on local state
                onIncomingFile(targetFile, fileName, senderIp)

                val response = "Success"
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } catch (e: Exception) {
                Log.e("HttpFileServer", "Error handling file upload: ${e.message}", e)
                val errorMsg = "Upload error: ${e.message}"
                exchange.sendResponseHeaders(500, errorMsg.length.toLong())
                exchange.responseBody.use { it.write(errorMsg.toByteArray()) }
            } finally {
                removeServerTransfer(transferKey)
            }
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun getHtmlDashboard(): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>QR File Share Hub</title>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
            <style>
                body {
                    font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
                    background-color: #0b0f19;
                    color: #e2e8f0;
                    margin: 0;
                    padding: 1.5rem;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    min-height: 100vh;
                }
                .container {
                    width: 100%;
                    max-width: 650px;
                    background: #151f32;
                    padding: 2rem;
                    border-radius: 16px;
                    box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.4), 0 8px 10px -6px rgba(0, 0, 0, 0.4);
                    border: 1px solid #1e293b;
                    box-sizing: border-box;
                    transition: all 0.3s ease;
                }
                h1 {
                    margin-top: 0;
                    font-size: 1.8rem;
                    font-weight: 700;
                    color: #38bdf8;
                    display: flex;
                    align-items: center;
                    gap: 0.75rem;
                }
                .subtitle {
                    color: #94a3b8;
                    margin-bottom: 2rem;
                    font-size: 0.95rem;
                }
                
                /* Responsive Two-Column Layout */
                .main-layout {
                    display: flex;
                    flex-direction: column;
                    gap: 2rem;
                }
                @media (min-width: 860px) {
                    .container {
                        max-width: 1000px;
                    }
                    .main-layout {
                        display: grid;
                        grid-template-columns: 1.15fr 0.85fr;
                        gap: 2.22rem;
                        align-items: start;
                    }
                }
                
                .left-col {
                    display: flex;
                    flex-direction: column;
                }
                .right-col {
                    display: flex;
                    flex-direction: column;
                    gap: 1.5rem;
                }

                .section-title {
                    font-size: 1.05rem;
                    font-weight: 600;
                    margin: 1.5rem 0 0.75rem 0;
                    color: #f1f5f9;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                }
                .section-title:first-child {
                    margin-top: 0;
                }
                .drop-zone {
                    border: 2px dashed #334155;
                    border-radius: 12px;
                    padding: 2.5rem 1.5rem;
                    text-align: center;
                    cursor: pointer;
                    transition: all 0.2s ease-in-out;
                    background: #0d1527;
                }
                .drop-zone:hover, .drop-zone.dragover {
                    border-color: #38bdf8;
                    background: #111a2e;
                }
                .drop-zone svg {
                    width: 48px;
                    height: 48px;
                    fill: #94a3b8;
                    margin-bottom: 0.75rem;
                }
                .drop-zone p {
                    margin: 0;
                    color: #94a3b8;
                    font-size: 0.9rem;
                }
                .files-list {
                    list-style: none;
                    padding: 0;
                    margin: 0;
                }
                .file-item {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 0.85rem 1rem;
                    background: #0d1527;
                    border-radius: 8px;
                    margin-bottom: 0.5rem;
                    border: 1px solid #1e293b;
                    transition: border-color 0.15s ease;
                }
                .file-item:hover {
                    border-color: #334155;
                }
                .file-info {
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .file-name {
                    font-weight: 500;
                    color: #f1f5f9;
                    font-size: 0.9rem;
                    text-overflow: ellipsis;
                    overflow: hidden;
                    white-space: nowrap;
                    max-width: 250px;
                }
                @media (min-width: 860px) {
                    .file-name {
                        max-width: 320px;
                    }
                }
                .file-size {
                    font-size: 0.75rem;
                    color: #64748b;
                    margin-top: 3px;
                }
                .btn {
                    background-color: #0284c7;
                    color: white;
                    border: none;
                    padding: 0.5rem 1rem;
                    border-radius: 6px;
                    cursor: pointer;
                    font-weight: 600;
                    font-size: 0.85rem;
                    text-decoration: none;
                    display: inline-flex;
                    align-items: center;
                    gap: 4px;
                    transition: background 0.15s ease;
                }
                .btn:hover {
                    background-color: #0369a1;
                }
                .btn-sm {
                    padding: 0.4rem 0.85rem;
                    flex-shrink: 0;
                }
                .progress-container {
                    margin-top: 1rem;
                    display: none;
                    background: #0d1527;
                    padding: 1rem;
                    border-radius: 8px;
                    border: 1px solid #1e293b;
                }
                .progress-bar {
                    height: 6px;
                    width: 100%;
                    background-color: #1e293b;
                    border-radius: 3px;
                    overflow: hidden;
                }
                .progress-fill {
                    height: 100%;
                    width: 0%;
                    background-color: #38bdf8;
                    transition: width 0.1s;
                }
                .progress-text {
                    font-size: 0.8rem;
                    color: #cbd5e1;
                    margin-top: 0.5rem;
                    display: flex;
                    justify-content: space-between;
                }
                .toast {
                    position: fixed;
                    bottom: 24px;
                    background: #0284c7;
                    color: white;
                    padding: 0.8rem 1.6rem;
                    border-radius: 8px;
                    box-shadow: 0 10px 15px -3px rgba(0,0,0,0.3);
                    transform: translateY(100px);
                    opacity: 0;
                    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
                    z-index: 1000;
                    font-size: 0.9rem;
                    font-weight: 500;
                }
                .toast.show {
                    transform: translateY(0);
                    opacity: 1;
                }

                /* Active Connection Indicator Panel */
                .status-card {
                    background: #0d1527;
                    border: 1px solid #1e293b;
                    border-radius: 12px;
                    padding: 1.2rem;
                    display: flex;
                    flex-direction: column;
                    gap: 0.6rem;
                }
                .status-header {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }
                .pulse-indicator {
                    width: 10px;
                    height: 10px;
                    background-color: #10b981;
                    border-radius: 50%;
                    display: inline-block;
                    box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7);
                    animation: pulse 1.8s infinite;
                }
                @keyframes pulse {
                    0% {
                        transform: scale(0.95);
                        box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7);
                    }
                    70% {
                        transform: scale(1);
                        box-shadow: 0 0 0 6px rgba(16, 185, 129, 0);
                    }
                    100% {
                        transform: scale(0.95);
                        box-shadow: 0 0 0 0 rgba(16, 185, 129, 0);
                    }
                }
                .status-badge {
                    background-color: rgba(16, 185, 129, 0.12);
                    color: #10b981;
                    padding: 2px 8px;
                    border-radius: 12px;
                    font-size: 0.75rem;
                    font-weight: 700;
                    letter-spacing: 0.05em;
                }
                .status-address {
                    font-family: monospace;
                    font-size: 0.85rem;
                    color: #38bdf8;
                    background: rgba(15, 23, 42, 0.6);
                    padding: 6px 10px;
                    border-radius: 6px;
                    border: 1px solid #1e293b;
                    word-break: break-all;
                }

                /* QR Code Display Card */
                .qr-card {
                    background: #0d1527;
                    border: 1px solid #1e293b;
                    border-radius: 12px;
                    padding: 1.5rem;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    gap: 1rem;
                }
                .qr-card-title {
                    font-size: 0.95rem;
                    font-weight: 600;
                    color: #f1f5f9;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                }
                .qr-wrapper {
                    background: white;
                    padding: 12px;
                    border-radius: 8px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
                }
                .qr-caption {
                    font-size: 0.8rem;
                    color: #94a3b8;
                    text-align: center;
                    line-height: 1.4;
                    margin: 0;
                }

                /* Instructions Card styling */
                .instructions-card {
                    background: #0d1527;
                    border: 1px solid #1e293b;
                    border-radius: 12px;
                    padding: 1.5rem;
                }
                .instructions-header {
                    font-size: 1rem;
                    font-weight: 600;
                    color: #38bdf8;
                    margin-bottom: 1.2rem;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    border-bottom: 1px solid #19243a;
                    padding-bottom: 8px;
                }
                .step-list {
                    display: flex;
                    flex-direction: column;
                    gap: 1.2rem;
                }
                .step-item {
                    display: flex;
                    gap: 12px;
                }
                .step-num {
                    background-color: rgba(56, 189, 248, 0.15);
                    color: #38bdf8;
                    border-radius: 50%;
                    width: 24px;
                    height: 24px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 0.8rem;
                    font-weight: 700;
                    flex-shrink: 0;
                }
                .step-details {
                    display: flex;
                    flex-direction: column;
                }
                .step-title {
                    font-weight: 600;
                    color: #f1f5f9;
                    font-size: 0.85rem;
                    margin-bottom: 2px;
                }
                .step-desc {
                    font-size: 0.8rem;
                    color: #94a3b8;
                    line-height: 1.4;
                }

                /* Staging Area Container */
                .staging-area {
                    margin-top: 1.25rem;
                    background: #0d1527;
                    border: 1px dashed #38bdf8;
                    border-radius: 12px;
                    padding: 1.25rem;
                    display: none;
                    animation: slideDown 0.25s cubic-bezier(0.16, 1, 0.3, 1);
                }
                @keyframes slideDown {
                    from { opacity: 0; transform: translateY(-8px); }
                    to { opacity: 1; transform: translateY(0); }
                }
                .staging-title {
                    font-size: 0.9rem;
                    font-weight: 600;
                    margin-bottom: 0.75rem;
                    color: #e2e8f0;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                }
                .staged-list {
                    max-height: 180px;
                    overflow-y: auto;
                    margin-bottom: 1rem;
                    padding-right: 4px;
                }
                .staged-list::-webkit-scrollbar {
                    width: 4px;
                }
                .staged-list::-webkit-scrollbar-track {
                    background: transparent;
                }
                .staged-list::-webkit-scrollbar-thumb {
                    background: #1e293b;
                    border-radius: 2px;
                }
                .staged-item {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 0.55rem 0.75rem;
                    background: rgba(15, 23, 42, 0.6);
                    border: 1px solid #1e293b;
                    border-radius: 6px;
                    margin-bottom: 0.4rem;
                    font-size: 0.85rem;
                }
                .staged-item:hover {
                    border-color: #334155;
                }
                .file-type-badge {
                    background: rgba(56, 189, 248, 0.15);
                    color: #38bdf8;
                    font-weight: 700;
                    font-size: 0.7rem;
                    padding: 2px 6px;
                    border-radius: 4px;
                    min-width: 32px;
                    text-align: center;
                    letter-spacing: 0.02em;
                }
                .staged-name {
                    font-weight: 500;
                    color: #cbd5e1;
                    white-space: nowrap;
                    text-overflow: ellipsis;
                    overflow: hidden;
                    max-width: 250px;
                    display: inline-block;
                }
                .staged-size {
                    font-size: 0.7rem;
                    color: #64748b;
                }
                .remove-staged-btn {
                    background: transparent;
                    border: none;
                    color: #64748b;
                    cursor: pointer;
                    font-size: 0.95rem;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    width: 24px;
                    height: 24px;
                    border-radius: 50%;
                    transition: all 0.15s ease;
                }
                .remove-staged-btn:hover {
                    color: #f87171;
                    background: rgba(239, 68, 68, 0.15);
                }
                .btn-danger {
                    background-color: #1e293b !important;
                    color: #94a3b8 !important;
                }
                .btn-danger:hover {
                    background-color: #ef4444 !important;
                    color: white !important;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>
                    <svg style="width:28px;height:28px" viewBox="0 0 24 24"><path fill="currentColor" d="M12,1.8C6.4,1.8,1.8,6.4,1.8,12c0,5.6,4.6,10.2,10.2,10.2s10.2-4.6,10.2-10.2C22.2,6.4,17.6,1.8,12,1.8z M12,20.4c-4.6,0-8.4-3.8-8.4-8.4S7.4,3.6,12,3.6s8.4,3.8,8.4,8.4S16.6,20.4,12,20.4z M13,7h-2v6l5.2,3.2l1-1.6l-4.2-2.5V7z"/></svg>
                    QR File Share Hub
                </h1>
                <div class="subtitle">Secure high-speed local network bidirectional share workspace active with Windows.</div>

                <div class="main-layout">
                    <!-- Left Column: Primary Actions -->
                    <div class="left-col">
                        <div class="section-title">
                            <svg style="width:18px;height:18px;fill:#38bdf8" viewBox="0 0 24 24"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg>
                            Upload and Save to Phone
                        </div>
                        
                        <div class="drop-zone" id="drop-zone">
                            <svg viewBox="0 0 24 24"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg>
                            <p>Drag & drop folder, files, or click to browse</p>
                            <input type="file" id="file-input" multiple style="display: none;">
                        </div>

                        <!-- Staging Area Container -->
                        <div class="staging-area" id="staging-area">
                            <div class="staging-title">
                                <svg style="width:16px;height:16px;fill:#38bdf8" viewBox="0 0 24 24"><path d="M14 10H3v2h11v-2zm0-4H3v2h11V6zM3 16h7v-2H3v2zm11.5-1.5V11h-2v3.5H9v2h3.5V20h2v-3.5H18v-2h-3.5z"/></svg>
                                Prepared for Sharing
                            </div>
                            <div class="staged-list" id="staged-list"></div>
                            <div id="stage-summary"></div>
                        </div>

                        <div class="progress-container" id="progress-container">
                            <div class="progress-bar"><div class="progress-fill" id="progress-fill"></div></div>
                            <div class="progress-text" id="progress-text">
                                <span>Uploading file...</span>
                                <span id="progress-percentage">0%</span>
                            </div>
                        </div>

                        <div class="section-title">
                            <svg style="width:18px;height:18px;fill:#38bdf8" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.53c-.26-.81-1-1.4-1.9-1.4h-1v-3c0-.55-.45-1-1-1h-6v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
                            Download from Phone
                        </div>
                        <ul class="files-list" id="files-list">
                            <!-- Dynamic files -->
                            <li class="file-item" style="color: #64748b; font-style: italic; font-size: 0.9rem; justify-content: center;">
                                No files shared from Android yet. Touch "Share Files" on Android.
                            </li>
                        </ul>
                    </div>

                    <!-- Right Column: Status info, Dynamic QR generator, guidelines steps -->
                    <div class="right-col">
                        <!-- Connection status indicator panel -->
                        <div class="status-card">
                            <div class="status-header">
                                <span class="pulse-indicator"></span>
                                <span class="status-badge">CONNECTED</span>
                                <span style="font-size: 0.8rem; color: #94a3b8; font-weight: 500;">Local Tunnel</span>
                            </div>
                            <div style="font-size: 0.8rem; color: #cbd5e1; font-weight: bold; margin-top: 4px;">COMPUTER ACCESS LINK:</div>
                            <div class="status-address" id="pc-url-display">Checking host URL...</div>
                        </div>

                        <!-- Dynamic QR Code generator for devices to join quickly -->
                        <div class="qr-card">
                            <div class="qr-card-title">
                                <svg style="width:18px;height:18px;fill:#38bdf8" viewBox="0 0 24 24"><path d="M4 4h6v6H4V4zm2 2v2h2V6H6zm0 12h2v-2H6v2zM14 4h6v6h-6V4zm2 2v2h2V6h-2zm0 8h2v-2h-2v2zm-2 2h2v-2h-2v2zm2 2h2v-2h-2v2zm-4-4h2v-2h-2v2zm2-2h2V8h-2v2zm-2 2H8v2h2V12zm-2 4h2v-2H8v2zm8 2h2v-2h-2v2zM4 14h6v6H4v-6zm2 2v2h2V16H6z"/></svg>
                                Share Connection QR Code
                            </div>
                            <div class="qr-wrapper">
                                <div id="qrcode">
                                    <div style="color:#64748b; font-size: 0.75rem; text-align: center; padding: 12px 24px;">
                                        Creating dynamic connection QR...
                                    </div>
                                </div>
                            </div>
                            <p class="qr-caption">
                                Scan this QR code on any Android phone / tablet on the same Wi-Fi network to instantly join this file share dashboard.
                            </p>
                        </div>

                        <!-- Full step instructions guide for the user -->
                        <div class="instructions-card">
                            <div class="instructions-header">
                                <svg style="width:18px;height:18px;fill:#38bdf8" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                                How to use on Computer
                            </div>
                            <div class="step-list">
                                <div class="step-item">
                                    <div class="step-num">1</div>
                                    <div class="step-details">
                                        <div class="step-title">Ensure Shared Local Wi-Fi</div>
                                        <div class="step-desc">Your computer and Android phone must be connected to the exact same local Wi-Fi or router network.</div>
                                    </div>
                                </div>
                                <div class="step-item">
                                    <div class="step-num">2</div>
                                    <div class="step-details">
                                        <div class="step-title">Receive from Phone</div>
                                        <div class="step-desc">On your phone, select files and touch "Share Files". They will appear instantly on this table under "Download from Phone" for you to save.</div>
                                    </div>
                                </div>
                                <div class="step-item">
                                    <div class="step-num">3</div>
                                    <div class="step-details">
                                        <div class="step-title">Send to Phone</div>
                                        <div class="step-desc">Drag files or folder structures onto the dashed dropzone area or click to browse. They will be uploaded instantly in high-speed and saved on your phone.</div>
                                    </div>
                                </div>
                                <div class="step-item">
                                    <div class="step-num">4</div>
                                    <div class="step-details">
                                        <div class="step-title">Double-Pair / Sync Mode</div>
                                        <div class="step-desc">Switch phone tab to "Scan" mode and capture the QR code above; it binds your phone directly as a remote data push client!</div>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            <div class="toast" id="toast">File sent successfully to Phone!</div>

            <script>
                const dropZone = document.getElementById('drop-zone');
                const fileInput = document.getElementById('file-input');
                const progressContainer = document.getElementById('progress-container');
                const progressFill = document.getElementById('progress-fill');
                const progressPercentage = document.getElementById('progress-percentage');
                const filesList = document.getElementById('files-list');
                const toast = document.getElementById('toast');

                // Update URL display labels on load
                const connectionUrl = window.location.href;
                document.getElementById('pc-url-display').textContent = connectionUrl;

                // QR Code Generator initialization with CDN offline fallback detection
                try {
                    if (typeof QRCode !== 'undefined') {
                        const qrElement = document.getElementById('qrcode');
                        qrElement.innerHTML = ''; // Clear loading text
                        new QRCode(qrElement, {
                            text: connectionUrl,
                            width: 160,
                            height: 160,
                            colorDark : "#151f32",
                            colorLight : "#ffffff",
                            correctLevel : QRCode.CorrectLevel.M
                        });
                    } else {
                        throw new Error('QRCode JS is undefined (offline/not cached)');
                    }
                } catch (e) {
                    console.warn("QR Generator fall-back triggered: ", e);
                    document.getElementById('qrcode').innerHTML = `
                        <div style="color: #94a3b8; font-size: 0.8rem; text-align: center; padding: 10px;">
                            <div style="font-weight: bold; color: #f1f5f9; margin-bottom: 6px;">CDN Unavailable</div>
                            <p style="margin: 0 0 6px 0;">Local URL is:</p>
                            <span style="display:inline-block; font-family: monospace; font-size: 0.85rem; color: #38bdf8; background: #0b0f19; padding: 4px 8px; border-radius: 4px; word-break: break-all;">` + connectionUrl + `</span>
                        </div>
                    `;
                }

                function showToast(message) {
                    toast.textContent = message;
                    toast.classList.add('show');
                    setTimeout(() => toast.classList.remove('show'), 3500);
                }

                dropZone.addEventListener('click', () => fileInput.click());
                dropZone.addEventListener('dragover', (e) => {
                    e.preventDefault();
                    dropZone.classList.add('dragover');
                });
                dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
                dropZone.addEventListener('drop', (e) => {
                    e.preventDefault();
                    dropZone.classList.remove('dragover');
                    handleFiles(e.dataTransfer.files);
                });
                fileInput.addEventListener('change', () => handleFiles(fileInput.files));

                async function loadFiles() {
                    try {
                        const res = await fetch('/shared_files_json');
                        const files = await res.json();
                        if (files.length === 0) {
                            filesList.innerHTML = '<li class="file-item" style="color: #64748b; font-style: italic; font-size: 0.85rem; justify-content: center;">No files currently shared from Phone. Add files on Android to download them here!</li>';
                            return;
                        }
                        filesList.innerHTML = '';
                        files.forEach((file, index) => {
                            const item = document.createElement('li');
                            item.className = 'file-item';
                            item.innerHTML = '<div class="file-info">' +
                                '<span class="file-name" title="' + file.name + '">' + file.name + '</span>' +
                                '<span class="file-size">' + formatBytes(file.size) + '</span>' +
                                '</div>' +
                                '<a href="/download?index=' + index + '" class="btn btn-sm" download="' + file.name + '">Download</a>';
                            filesList.appendChild(item);
                        });
                    } catch (e) {
                        console.error("Error loading files:", e);
                    }
                }

                function formatBytes(bytes) {
                    if (bytes === 0) return '0 Bytes';
                    const k = 1024;
                    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                    const i = Math.floor(Math.log(bytes) / Math.log(k));
                    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
                }

                let stagedFiles = [];
                let isUploading = false;

                function handleFiles(files) {
                    if (isUploading) return;
                    if (files.length === 0) return;
                    for (let i = 0; i < files.length; i++) {
                        stagedFiles.push(files[i]);
                    }
                    fileInput.value = '';
                    renderStagedFiles();
                }

                function removeStagedFile(index) {
                    if (isUploading) return;
                    stagedFiles.splice(index, 1);
                    renderStagedFiles();
                }

                function clearStagedFiles() {
                    if (isUploading) return;
                    stagedFiles = [];
                    renderStagedFiles();
                }

                function renderStagedFiles() {
                    const stagingArea = document.getElementById('staging-area');
                    const stagedList = document.getElementById('staged-list');
                    const stageSummary = document.getElementById('stage-summary');
                    
                    if (stagedFiles.length === 0) {
                        stagingArea.style.display = 'none';
                        return;
                    }
                    
                    stagingArea.style.display = 'block';
                    stagedList.innerHTML = '';
                    
                    let totalSize = 0;
                    stagedFiles.forEach((file, idx) => {
                        totalSize += file.size;
                        const nameParts = file.name.split('.');
                        const fileExt = nameParts.length > 1 ? nameParts.pop().toUpperCase().slice(0, 4) : 'FILE';
                        
                        const item = document.createElement('div');
                        item.className = 'staged-item';
                        item.innerHTML = '<div style="display: flex; align-items: center; gap: 10px; width: 85%;">' +
                            '<span class="file-type-badge">' + fileExt + '</span>' +
                            '<div style="display: flex; flex-direction: column; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; width: calc(100% - 42px);">' +
                            '<span class="staged-name" title="' + file.name + '">' + file.name + '</span>' +
                            '<span class="staged-size">' + formatBytes(file.size) + '</span>' +
                            '</div>' +
                            '</div>' +
                            '<button class="remove-staged-btn" onclick="removeStagedFile(' + idx + ')">✕</button>';
                        stagedList.appendChild(item);
                    });
                    
                    stageSummary.innerHTML = '<div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.85rem; font-size: 0.82rem; color: #94a3b8; border-top: 1px solid #1e293b; padding-top: 0.75rem;">' +
                        '<span>Prepared: <strong>' + stagedFiles.length + ' file' + (stagedFiles.length > 1 ? 's' : '') + '</strong></span>' +
                        '<span>Total Size: <strong>' + formatBytes(totalSize) + '</strong></span>' +
                        '</div>' +
                        '<div style="display: flex; gap: 0.75rem;">' +
                        '<button class="btn" style="width: 100%; display: flex; justify-content: center; background-color: #10b981; border: none; font-family: inherit;" onclick="shareStagedFiles()">' +
                        '<svg style="width:16px;height:16px;fill:currentColor;margin-right:6px" viewBox="0 0 24 24"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg>' +
                        'Share Prepared Files' +
                        '</button>' +
                        '<button class="btn btn-danger" onclick="clearStagedFiles()">Clear</button>' +
                        '</div>';
                }

                async function shareStagedFiles() {
                    if (isUploading || stagedFiles.length === 0) return;
                    isUploading = true;
                    
                    // Hide delete buttons during upload
                    document.querySelectorAll('.remove-staged-btn').forEach(btn => btn.style.display = 'none');
                    // Disable clear btn
                    const clearBtn = document.querySelector('.btn-danger');
                    if (clearBtn) clearBtn.disabled = true;
                    
                    progressContainer.style.display = 'block';
                    
                    const totalFilesCount = stagedFiles.length;
                    let successCount = 0;
                    
                    while (stagedFiles.length > 0) {
                        const file = stagedFiles[0];
                        const currentNum = successCount + 1;
                        
                        const textSpan = document.querySelector('#progress-text span:first-child');
                        if (textSpan) {
                            textSpan.textContent = 'Uploading ' + currentNum + ' of ' + totalFilesCount + ': ' + file.name;
                        }
                        
                        progressPercentage.textContent = '0%';
                        progressFill.style.width = '0%';
                        
                        try {
                            await uploadFile(file);
                            successCount++;
                            stagedFiles.shift(); // Remove uploaded item
                            renderStagedFiles(); // Re-render update
                        } catch (e) {
                            console.error("Upload failed", e);
                            showToast("Upload failed: " + file.name);
                            break; 
                        }
                    }
                    
                    isUploading = false;
                    progressContainer.style.display = 'none';
                    if (stagedFiles.length === 0) {
                        showToast('Successfully shared ' + successCount + ' files with Android!');
                    } else {
                        renderStagedFiles(); 
                    }
                }

                function uploadFile(file) {
                    return new Promise((resolve, reject) => {
                        const xhr = new XMLHttpRequest();
                        xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name), true);

                        xhr.upload.onprogress = (e) => {
                            if (e.lengthComputable) {
                                const pct = Math.round((e.loaded / e.total) * 100);
                                progressFill.style.width = pct + '%';
                                progressPercentage.textContent = pct + '%';
                            }
                        };

                        xhr.onload = () => {
                            if (xhr.status === 200) resolve();
                            else reject(new Error('Status: ' + xhr.status));
                        };
                        xhr.onerror = () => reject(new Error('Network error'));
                        xhr.send(file);
                    });
                }

                setInterval(loadFiles, 3500);
                loadFiles();
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}

class HttpServerHeaders {
    private val headersMap = java.util.TreeMap<String, String>(java.lang.String.CASE_INSENSITIVE_ORDER)
    fun set(key: String, value: String) {
        headersMap[key] = value
    }
    fun getFirst(key: String): String? {
        return headersMap[key]
    }
    fun getMap(): Map<String, String> = headersMap
}

class HttpExchange(
    val requestMethod: String,
    val requestURI: java.net.URI,
    val requestBody: java.io.InputStream,
    val responseBody: java.io.OutputStream,
    val remoteAddress: java.net.InetSocketAddress,
    val requestHeaders: Map<String, String> = emptyMap()
) {
    val responseHeaders = HttpServerHeaders()
    var headersSent = false
        private set

    fun sendResponseHeaders(statusCode: Int, responseLength: Long) {
        if (headersSent) return
        headersSent = true

        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }

        val sb = java.lang.StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        
        for ((key, value) in responseHeaders.getMap()) {
            sb.append("$key: $value\r\n")
        }

        if (responseLength > 0) {
            sb.append("Content-Length: $responseLength\r\n")
        } else if (responseLength == 0L) {
            sb.append("Content-Length: 0\r\n")
        }

        sb.append("Connection: close\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.UTF_8)
        responseBody.write(headerBytes)
        responseBody.flush()
    }
}

interface HttpHandler {
    fun handle(exchange: HttpExchange)
}

class HttpServer private constructor(private val addr: InetSocketAddress) {
    private var serverSocket: java.net.ServerSocket? = null
    private val contexts = java.util.concurrent.ConcurrentHashMap<String, HttpHandler>()
    private var executor: java.util.concurrent.Executor? = null
    @Volatile private var running = false

    companion object {
        fun create(addr: InetSocketAddress, backlog: Int): HttpServer {
            return HttpServer(addr)
        }
    }

    fun createContext(path: String, handler: HttpHandler) {
        contexts[path] = handler
    }

    fun setExecutor(executor: java.util.concurrent.Executor) {
        this.executor = executor
    }

    fun start() {
        running = true
        val ss = java.net.ServerSocket()
        ss.reuseAddress = true
        ss.bind(addr)
        serverSocket = ss

        val runServer = Runnable {
            while (running) {
                try {
                    val socket = ss.accept()
                    val exec = executor ?: java.util.concurrent.Executors.newSingleThreadExecutor()
                    exec.execute {
                        handleClient(socket)
                    }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
        Thread(runServer, "HttpServer-Thread").start()
    }

    fun stop(delay: Int) {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: java.net.Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30000 // 30s timeout
                val inputStream = java.io.BufferedInputStream(s.getInputStream())
                val outputStream = java.io.BufferedOutputStream(s.getOutputStream())

                val requestLine = readLine(inputStream) ?: return
                val parts = requestLine.trim().split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]

                Log.d("HttpServer", "Incoming request: $method $rawPath from ${socket.remoteSocketAddress}")

                val headersMap = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(inputStream) ?: break
                    if (line.trim().isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx != -1) {
                        val key = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        headersMap[key] = value
                    }
                }

                val contentLength = headersMap["content-length"]?.toLongOrNull() ?: 0L

                val boundedInputStream = object : java.io.InputStream() {
                    private var bytesRemaining = contentLength
                    override fun read(): Int {
                        if (bytesRemaining <= 0) return -1
                        val b = inputStream.read()
                        if (b != -1) bytesRemaining--
                        return b
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (bytesRemaining <= 0) return -1
                        val toRead = if (len > bytesRemaining) bytesRemaining.toLong() else len.toLong()
                        val readBytes = inputStream.read(b, off, toRead.toInt())
                        if (readBytes != -1) bytesRemaining -= readBytes
                        return readBytes
                    }
                }

                val uri = try {
                    java.net.URI(rawPath)
                } catch (e: Exception) {
                    java.net.URI("/")
                }

                val uriPath = uri.path ?: "/"
                var matchedPattern = ""
                var matchedHandler: HttpHandler? = null
                for ((pattern, handler) in contexts) {
                    if (pattern == "/") {
                        if (matchedPattern.isEmpty()) {
                            matchedPattern = pattern
                            matchedHandler = handler
                        }
                    } else if (uriPath.startsWith(pattern)) {
                        if (pattern.length > matchedPattern.length) {
                            matchedPattern = pattern
                            matchedHandler = handler
                        }
                    }
                }

                if (matchedHandler == null) {
                    val body = "Not Found".toByteArray()
                    outputStream.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n".toByteArray())
                    outputStream.write(body)
                    outputStream.flush()
                    return
                }

                val remoteAddress = socket.remoteSocketAddress as? java.net.InetSocketAddress ?: java.net.InetSocketAddress(0)

                val exchange = HttpExchange(
                    requestMethod = method,
                    requestURI = uri,
                    requestBody = boundedInputStream,
                    responseBody = outputStream,
                    remoteAddress = remoteAddress,
                    requestHeaders = headersMap
                )

                try {
                    matchedHandler.handle(exchange)
                } catch (e: Exception) {
                    Log.e("HttpServer", "Handler error: ${e.message}", e)
                    if (!exchange.headersSent) {
                        val body = "Internal Server Error: ${e.message}".toByteArray()
                        try {
                            outputStream.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n".toByteArray())
                            outputStream.write(body)
                            outputStream.flush()
                        } catch (ex: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HttpServer", "Error handling socket: ${e.message}")
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val bos = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (bos.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                bos.write(b)
            }
        }
        return bos.toString("UTF-8")
    }
}

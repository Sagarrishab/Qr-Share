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

    // Custom DNS/mDNS Hostname Resolver
    private var mdnsResponder: MdnsResponder? = null


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

            // Dynamic background mDNS Responder to resolve "<alias>.local" directly to our WiFi IP address!
            try {
                mdnsResponder?.stop()
                mdnsResponder = MdnsResponder(context, nsdAlias, ip).apply {
                    start()
                }
            } catch (ex: Exception) {
                Log.e("HttpFileServer", "Failed to start custom mDNS Responder: ${ex.message}")
            }

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
            try {
                mdnsResponder?.stop()
                mdnsResponder = null
            } catch (ex: Exception) {}
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
            <title>QR File Share Portal</title>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet">
            <style>
                :root {
                    --bg-darker: #0d121f;
                    --bg-card: #151e33;
                    --bg-input: #0a0e1a;
                    --border-color: #232d44;
                    --text-primary: #f8fafc;
                    --text-secondary: #94a3b8;
                    --text-muted: #64748b;
                    
                    /* Accent Colors (Branding Matching Neon Amber / Orange & Tech Teal) */
                    --accent-orange: #f97316;
                    --accent-orange-hover: #ea580c;
                    --accent-teal: #14b8a6;
                    --accent-cyan: #38bdf8;
                    --accent-green: #10b981;
                    --accent-red: #ef4444;
                    
                    --primary-gradient: linear-gradient(135deg, #f97316 0%, #d97706 100%);
                    --card-shadow: 0 10px 30px -5px rgba(0, 0, 0, 0.4), 0 8px 15px -6px rgba(0, 0, 0, 0.4);
                }

                * {
                    box-sizing: border-box;
                }

                body {
                    font-family: 'Inter', system-ui, -apple-system, sans-serif;
                    background-color: var(--bg-darker);
                    color: var(--text-primary);
                    margin: 0;
                    padding: 1.5rem;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    min-height: 100vh;
                    line-height: 1.5;
                }

                .container {
                    width: 100%;
                    max-width: 650px;
                    background: var(--bg-card);
                    padding: 2.25rem 2rem;
                    border-radius: 20px;
                    box-shadow: var(--card-shadow);
                    border: 1px solid var(--border-color);
                    transition: all 0.3s ease;
                    margin-bottom: 2rem;
                }

                /* Header Styling */
                .portal-header {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    border-bottom: 1px solid var(--border-color);
                    padding-bottom: 1.5rem;
                    margin-bottom: 2rem;
                }

                .portal-brand {
                    display: flex;
                    align-items: center;
                    gap: 0.75rem;
                }

                .portal-brand svg {
                    width: 36px;
                    height: 36px;
                    fill: var(--accent-orange);
                    filter: drop-shadow(0 0 8px rgba(249, 115, 22, 0.4));
                }

                h1 {
                    margin: 0;
                    font-size: 1.75rem;
                    font-weight: 800;
                    letter-spacing: -0.03em;
                    background: var(--primary-gradient);
                    -webkit-background-clip: text;
                    -webkit-text-fill-color: transparent;
                }

                .subtitle {
                    color: var(--text-secondary);
                    font-size: 0.95rem;
                    margin-top: -1.25rem;
                    margin-bottom: 2rem;
                    line-height: 1.6;
                    max-width: 90%;
                }

                /* Responsive Main Grid Layout */
                .main-layout {
                    display: flex;
                    flex-direction: column;
                    gap: 2.25rem;
                }

                @media (min-width: 920px) {
                    .container {
                        max-width: 1100px;
                        padding: 3rem;
                    }
                    .main-layout {
                        display: grid;
                        grid-template-columns: 1.2fr 0.8fr;
                        gap: 3rem;
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
                    gap: 1.75rem;
                }

                .section-title {
                    font-size: 1.1rem;
                    font-weight: 700;
                    margin: 1.75rem 0 0.85rem 0;
                    color: var(--text-primary);
                    display: flex;
                    align-items: center;
                    gap: 10px;
                }

                .section-title:first-child {
                    margin-top: 0;
                }

                .section-title svg {
                    width: 20px;
                    height: 20px;
                    fill: var(--accent-orange);
                }

                /* Drag & Drop Zone Redesign */
                .drop-zone {
                    border: 2px dashed rgba(249, 115, 22, 0.35);
                    border-radius: 16px;
                    padding: 3rem 2rem;
                    text-align: center;
                    cursor: pointer;
                    transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
                    background: var(--bg-input);
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    gap: 0.75rem;
                }

                .drop-zone:hover, .drop-zone.dragover {
                    border-color: var(--accent-orange);
                    background: rgba(249, 115, 22, 0.04);
                    box-shadow: 0 0 20px rgba(249, 115, 22, 0.08) inset;
                    transform: translateY(-2px);
                }

                .drop-zone-icon-container {
                    background: rgba(249, 115, 22, 0.08);
                    color: var(--accent-orange);
                    width: 60px;
                    height: 60px;
                    border-radius: 50%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin-bottom: 0.5rem;
                    transition: all 0.25s ease;
                }

                .drop-zone:hover .drop-zone-icon-container {
                    background: rgba(249, 115, 22, 0.16);
                    transform: scale(1.1);
                }

                .drop-zone-icon {
                    width: 32px;
                    height: 32px;
                    fill: currentColor;
                }

                .drop-zone p {
                    margin: 0;
                    font-size: 1rem;
                    font-weight: 600;
                    color: var(--text-primary);
                }

                .drop-zone-caption {
                    margin: 0;
                    color: var(--text-secondary);
                    font-size: 0.85rem;
                }

                /* Staging Area & Staged Items */
                .staging-area {
                    margin-top: 1.5rem;
                    background: var(--bg-input);
                    border: 1px dashed var(--accent-teal);
                    border-radius: 16px;
                    padding: 1.5rem;
                    display: none;
                    animation: slideDown 0.3s cubic-bezier(0.16, 1, 0.3, 1);
                }

                @keyframes slideDown {
                    from { opacity: 0; transform: translateY(-10px); }
                    to { opacity: 1; transform: translateY(0); }
                }

                .staging-title {
                    font-size: 0.95rem;
                    font-weight: 700;
                    margin-bottom: 1rem;
                    color: var(--text-primary);
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .staging-title svg {
                    width: 18px;
                    height: 18px;
                    fill: var(--accent-teal);
                }

                .staged-list {
                    max-height: 220px;
                    overflow-y: auto;
                    margin-bottom: 1.25rem;
                    padding-right: 6px;
                    display: flex;
                    flex-direction: column;
                    gap: 0.5rem;
                }

                .staged-list::-webkit-scrollbar {
                    width: 5px;
                }

                .staged-list::-webkit-scrollbar-track {
                    background: transparent;
                }

                .staged-list::-webkit-scrollbar-thumb {
                    background: var(--border-color);
                    border-radius: 3px;
                }

                .staged-item {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 0.75rem 1rem;
                    background: var(--bg-card);
                    border: 1px solid var(--border-color);
                    border-radius: 10px;
                    font-size: 0.875rem;
                    transition: border-color 0.15s ease;
                }

                .staged-item:hover {
                    border-color: var(--text-muted);
                }

                .file-type-badge {
                    background: rgba(20, 184, 166, 0.12);
                    color: var(--accent-teal);
                    font-weight: 700;
                    font-size: 0.725rem;
                    padding: 3px 8px;
                    border-radius: 6px;
                    min-width: 44px;
                    text-align: center;
                    letter-spacing: 0.04em;
                    font-family: 'JetBrains Mono', monospace;
                }

                .staged-name {
                    font-weight: 500;
                    color: var(--text-primary);
                    white-space: nowrap;
                    text-overflow: ellipsis;
                    overflow: hidden;
                    max-width: 250px;
                    display: inline-block;
                }

                .staged-size {
                    font-size: 0.75rem;
                    color: var(--text-secondary);
                }

                .remove-staged-btn {
                    background: transparent;
                    border: none;
                    color: var(--text-muted);
                    cursor: pointer;
                    font-size: 1rem;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    width: 28px;
                    height: 28px;
                    border-radius: 50%;
                    transition: all 0.2s ease;
                }

                .remove-staged-btn:hover {
                    color: var(--accent-red);
                    background: rgba(239, 108, 108, 0.12);
                }

                /* Beautiful Interactive Buttons */
                .btn {
                    background: var(--primary-gradient);
                    color: white;
                    border: none;
                    padding: 0.75rem 1.5rem;
                    border-radius: 10px;
                    cursor: pointer;
                    font-weight: 700;
                    font-size: 0.95rem;
                    text-decoration: none;
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    gap: 6px;
                    transition: all 0.2s ease;
                    box-shadow: 0 4px 12px rgba(249, 115, 22, 0.2);
                }

                .btn:hover {
                    transform: translateY(-1px);
                    box-shadow: 0 6px 16px rgba(249, 115, 22, 0.35);
                }

                .btn:active {
                    transform: translateY(1px);
                }

                .btn-sm {
                    padding: 0.5rem 1rem;
                    font-size: 0.8rem;
                    border-radius: 8px;
                }

                .btn-danger {
                    background: var(--bg-card) !important;
                    color: var(--text-secondary) !important;
                    border: 1px solid var(--border-color) !important;
                    box-shadow: none !important;
                }

                .btn-danger:hover {
                    background: rgba(239, 68, 68, 0.1) !important;
                    border-color: var(--accent-red) !important;
                    color: white !important;
                    box-shadow: none !important;
                }

                /* Progress Container */
                .progress-container {
                    margin-top: 1.5rem;
                    display: none;
                    background: var(--bg-input);
                    padding: 1.25rem;
                    border-radius: 12px;
                    border: 1px solid var(--border-color);
                }

                .progress-bar {
                    height: 8px;
                    width: 100%;
                    background-color: var(--border-color);
                    border-radius: 4px;
                    overflow: hidden;
                }

                .progress-fill {
                    height: 100%;
                    width: 0%;
                    background-color: var(--accent-orange);
                    border-radius: 4px;
                    transition: width 0.15s ease;
                }

                .progress-text {
                    font-size: 0.85rem;
                    color: var(--text-secondary);
                    margin-top: 0.75rem;
                    display: flex;
                    justify-content: space-between;
                }

                /* Download List Layout Beautification */
                .files-list {
                    list-style: none;
                    padding: 0;
                    margin: 0;
                    display: flex;
                    flex-direction: column;
                    gap: 0.75rem;
                }

                .file-item {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 1rem 1.25rem;
                    background: var(--bg-input);
                    border-radius: 12px;
                    border: 1px solid var(--border-color);
                    transition: all 0.2s ease;
                    gap: 1rem;
                }

                .file-item:hover {
                    border-color: var(--accent-orange);
                    background: rgba(249, 115, 22, 0.02);
                }

                .file-item-left {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    overflow: hidden;
                }

                .file-icon-wrapper {
                    background: rgba(255, 255, 255, 0.04);
                    padding: 10px;
                    border-radius: 10px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    flex-shrink: 0;
                    border: 1px solid var(--border-color);
                }

                .file-item:hover .file-icon-wrapper {
                    border-color: rgba(249, 115, 22, 0.2);
                }

                .file-info {
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                }

                .file-name {
                    font-weight: 600;
                    color: var(--text-primary);
                    font-size: 0.95rem;
                    text-overflow: ellipsis;
                    overflow: hidden;
                    white-space: nowrap;
                    max-width: 250px;
                }

                @media (min-width: 920px) {
                    .file-name {
                        max-width: 360px;
                    }
                }

                .file-size {
                    font-size: 0.775rem;
                    color: var(--text-secondary);
                    margin-top: 2px;
                }

                .download-btn {
                    background: rgba(249, 115, 22, 0.1) !important;
                    color: var(--accent-orange) !important;
                    border: 1px solid rgba(249, 115, 22, 0.2) !important;
                    box-shadow: none !important;
                }

                .download-btn:hover {
                    background: var(--primary-gradient) !important;
                    color: white !important;
                    border-color: transparent !important;
                    box-shadow: 0 4px 12px rgba(249, 115, 22, 0.2) !important;
                }

                /* Connection Status Card Redesign */
                .status-card {
                    background: var(--bg-input);
                    border: 1px solid var(--border-color);
                    border-radius: 16px;
                    padding: 1.5rem;
                    display: flex;
                    flex-direction: column;
                    gap: 1rem;
                    position: relative;
                }

                .status-header {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                }

                .pulse-indicator {
                    width: 10px;
                    height: 10px;
                    background-color: var(--accent-green);
                    border-radius: 50%;
                    display: inline-block;
                    box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7);
                    animation: pulse 1.8s infinite;
                }

                @keyframes pulse {
                    0% {
                        transform: scale(0.95);
                        box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.5);
                    }
                    70% {
                        transform: scale(1);
                        box-shadow: 0 0 0 8px rgba(16, 185, 129, 0);
                    }
                    100% {
                        transform: scale(0.95);
                        box-shadow: 0 0 0 0 rgba(16, 185, 129, 0);
                    }
                }

                .status-badge {
                    background-color: rgba(16, 185, 129, 0.12);
                    color: var(--accent-green);
                    padding: 4px 10px;
                    border-radius: 20px;
                    font-size: 0.75rem;
                    font-weight: 800;
                    letter-spacing: 0.05em;
                }

                .connection-type {
                    font-size: 0.8rem;
                    color: var(--accent-cyan);
                    font-weight: 600;
                }

                .address-container {
                    display: flex;
                    flex-direction: column;
                    gap: 0.5rem;
                }

                .address-label {
                    font-size: 0.8rem;
                    color: var(--text-secondary);
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.03em;
                }

                .address-box-wrapper {
                    display: flex;
                    align-items: stretch;
                    gap: 0.5rem;
                }

                .status-address {
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 0.9rem;
                    color: var(--accent-orange);
                    background: rgba(15, 23, 42, 0.65);
                    padding: 0.65rem 1rem;
                    border-radius: 8px;
                    border: 1px solid var(--border-color);
                    word-break: break-all;
                    flex-grow: 1;
                    display: flex;
                    align-items: center;
                }

                /* Copy link inline button */
                .copy-btn {
                    background: var(--border-color);
                    border: 1px solid var(--border-color);
                    color: var(--text-primary);
                    padding: 0 1.25rem;
                    border-radius: 8px;
                    cursor: pointer;
                    font-weight: 600;
                    font-size: 0.825rem;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    transition: all 0.15s ease;
                }

                .copy-btn:hover {
                    background: var(--bg-card);
                    border-color: var(--accent-orange);
                    color: var(--accent-orange);
                }

                .copy-btn svg {
                    width: 16px;
                    height: 16px;
                    fill: currentColor;
                }

                /* QR Code Card Frame Redesign */
                .qr-card {
                    background: var(--bg-input);
                    border: 1px solid var(--border-color);
                    border-radius: 16px;
                    padding: 1.75rem;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    gap: 1.25rem;
                    position: relative;
                }

                .qr-card::before {
                    content: '';
                    position: absolute;
                    top: 0;
                    left: 0;
                    right: 0;
                    height: 4px;
                    background: var(--primary-gradient);
                    border-top-left-radius: 16px;
                    border-top-right-radius: 16px;
                }

                .qr-card-title {
                    font-size: 1rem;
                    font-weight: 700;
                    color: var(--text-primary);
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .qr-card-title svg {
                    width: 18px;
                    height: 18px;
                    fill: var(--accent-orange);
                }

                .qr-wrapper {
                    background: white;
                    padding: 14px;
                    border-radius: 12px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.4);
                    transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
                }

                .qr-wrapper:hover {
                    transform: scale(1.04);
                }

                .qr-caption {
                    font-size: 0.8rem;
                    color: var(--text-secondary);
                    text-align: center;
                    line-height: 1.5;
                    margin: 0;
                }

                /* Visual Timeline Instructions Card */
                .instructions-card {
                    background: var(--bg-input);
                    border: 1px solid var(--border-color);
                    border-radius: 16px;
                    padding: 1.75rem;
                }

                .instructions-header {
                    font-size: 1rem;
                    font-weight: 700;
                    color: var(--accent-orange);
                    margin-bottom: 1.5rem;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    border-bottom: 1px solid var(--border-color);
                    padding-bottom: 0.75rem;
                }

                .instructions-header svg {
                    width: 20px;
                    height: 20px;
                    fill: currentColor;
                }

                .timeline {
                    position: relative;
                    padding-left: 1.5rem;
                    display: flex;
                    flex-direction: column;
                    gap: 1.5rem;
                }

                .timeline::before {
                    content: '';
                    position: absolute;
                    left: 10px;
                    top: 12px;
                    bottom: 12px;
                    width: 2px;
                    background: linear-gradient(180deg, var(--accent-orange) 0%, rgba(249, 115, 22, 0.1) 100%);
                }

                .timeline-item {
                    position: relative;
                }

                .timeline-badge {
                    position: absolute;
                    left: -25px;
                    width: 22px;
                    height: 22px;
                    background-color: var(--bg-card);
                    border: 2px solid var(--accent-orange);
                    color: var(--accent-orange);
                    border-radius: 50%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 0.75rem;
                    font-weight: 800;
                    z-index: 2;
                }

                .timeline-content {
                    display: flex;
                    flex-direction: column;
                    padding-left: 0.5rem;
                }

                .timeline-title {
                    font-weight: 700;
                    color: var(--text-primary);
                    font-size: 0.875rem;
                    margin-bottom: 3px;
                }

                .timeline-desc {
                    font-size: 0.8rem;
                    color: var(--text-secondary);
                    line-height: 1.45;
                }

                /* Modern Floating Toast */
                .toast {
                    position: fixed;
                    bottom: 32px;
                    background: var(--primary-gradient);
                    color: white;
                    padding: 0.85rem 1.75rem;
                    border-radius: 10px;
                    box-shadow: 0 10px 25px -3px rgba(249, 115, 22, 0.4);
                    transform: translateY(100px);
                    opacity: 0;
                    transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
                    z-index: 1000;
                    font-size: 0.9rem;
                    font-weight: 600;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .toast.show {
                    transform: translateY(0);
                    opacity: 1;
                }

                .toast svg {
                    width: 18px;
                    height: 18px;
                    fill: currentColor;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="portal-header">
                    <div class="portal-brand">
                        <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.53c-.26-.81-1-1.4-1.9-1.4h-1v-3c0-.55-.45-1-1-1h-6v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
                        <h1>QR File Share Hub</h1>
                    </div>
                </div>
                <div class="subtitle">Secure, high-speed bidirectional wireless file workspace immediately synced with Android.</div>

                <div class="main-layout">
                    <!-- Left Column: Primary Actions -->
                    <div class="left-col">
                        <div class="section-title">
                            <svg viewBox="0 0 24 24"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg>
                            Upload to Phone
                        </div>
                        
                        <div class="drop-zone" id="drop-zone">
                            <div class="drop-zone-icon-container">
                                <svg class="drop-zone-icon" viewBox="0 0 24 24"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM19.35 10.04M14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg>
                            </div>
                            <p>Drag & drop folders, files, or click to browse</p>
                            <span class="drop-zone-caption">Immediate gigabit local transmission</span>
                            <input type="file" id="file-input" multiple style="display: none;">
                        </div>

                        <!-- Staging Area Container -->
                        <div class="staging-area" id="staging-area">
                            <div class="staging-title">
                                <svg viewBox="0 0 24 24"><path d="M14 10H3v2h11v-2zm0-4H3v2h11V6zM3 16h7v-2H3v2zm11.5-1.5V11h-2v3.5H9v2h3.5V20h2v-3.5H18v-2h-3.5z"/></svg>
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
                            <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.53c-.26-.81-1-1.4-1.9-1.4h-1v-3c0-.55-.45-1-1-1h-6v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
                            Download from Phone
                        </div>
                        <ul class="files-list" id="files-list">
                            <!-- Dynamic files list -->
                            <li class="file-item" style="color: var(--text-muted); font-style: italic; font-size: 0.9em; justify-content: center;">
                                No files currently shared from Android device. Click "Share Files" on Android.
                            </li>
                        </ul>
                    </div>

                    <!-- Right Column: Status info, Dynamic QR generator, guidelines steps -->
                    <div class="right-col">
                        <!-- Connection status indicator panel -->
                        <div class="status-card">
                            <div class="status-header">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <span class="pulse-indicator"></span>
                                    <span class="status-badge">CONNECTED</span>
                                </div>
                                <span class="connection-type">P2P LAN Link Active</span>
                            </div>
                            <div class="address-container">
                                <span class="address-label">Web Access Link:</span>
                                <div class="address-box-wrapper">
                                    <span class="status-address" id="pc-url-display">Checking...</span>
                                    <button class="copy-btn" id="copy-btn" onclick="copyAddress()" title="Copy to clipboard">
                                        <svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>
                                        Copy
                                    </button>
                                </div>
                            </div>
                        </div>

                        <!-- Dynamic QR Code generator for devices to join quickly -->
                        <div class="qr-card">
                            <div class="qr-card-title">
                                <svg viewBox="0 0 24 24"><path d="M4 4h6v6H4V4zm2 2v2h2V6H6zm0 12h2v-2H6v2zM14 4h6v6h-6V4zm2 2v2h2V6h-2zm0 8h2v-2h-2v2zm-2 2h2v-2h-2v2zm2 2h2v-2h-2v2zm-4-4h2v-2h-2v2zm2-2h2V8h-2v2zm-2 2H8v2h2V12zm-2 4h2v-2H8v2zm8 2h2v-2h-2v2zM4 14h6v6H4v-6zm2 2v2h2V16H6z"/></svg>
                                Remote Pushing QR Link
                            </div>
                            <div class="qr-wrapper">
                                <div id="qrcode">
                                    <div style="color:var(--text-muted); font-size: 0.75rem; text-align: center; padding: 12px 24px;">
                                        Generating remote QR link...
                                    </div>
                                </div>
                            </div>
                            <p class="qr-caption">
                                Scan this to bind any secondary phone or tablet as a secure local remote client to push files.
                            </p>
                        </div>

                        <!-- Full step instructions guide for the user -->
                        <div class="instructions-card">
                            <div class="instructions-header">
                                <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                                Share Workspace Guide
                            </div>
                            <div class="timeline">
                                <div class="timeline-item">
                                    <div class="timeline-badge">1</div>
                                    <div class="timeline-content">
                                        <span class="timeline-title">Same Wi-Fi Router</span>
                                        <span class="timeline-desc">Make sure both your Computer and Phone are on the same Wi-Fi router network.</span>
                                    </div>
                                </div>
                                <div class="timeline-item">
                                    <div class="timeline-badge">2</div>
                                    <div class="timeline-content">
                                        <span class="timeline-title">Receive from Phone</span>
                                        <span class="timeline-desc">Add files on Android and touch "Share Files". They appear live immediately in the list above to Download!</span>
                                    </div>
                                </div>
                                <div class="timeline-item">
                                    <div class="timeline-badge">3</div>
                                    <div class="timeline-content">
                                        <span class="timeline-title">Send to Phone</span>
                                        <span class="timeline-desc">Drag any sized files or folders onto the dotted dropzone above to transmit them straight into Android storage.</span>
                                    </div>
                                </div>
                                <div class="timeline-item">
                                    <div class="timeline-badge">4</div>
                                    <div class="timeline-content">
                                        <span class="timeline-title">Pair Additional Phones</span>
                                        <span class="timeline-desc">Switch phone mode to "Scan", point the laser grid at the QR above to link and instantly upload from another device!</span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            <div class="toast" id="toast">
                <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>
                <span id="toast-text">File updated successfully!</span>
            </div>

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

                // QR Code Generator initialization with offline fallback
                try {
                    if (typeof QRCode !== 'undefined') {
                        const qrElement = document.getElementById('qrcode');
                        qrElement.innerHTML = ''; // Clear loading text
                        new QRCode(qrElement, {
                            text: connectionUrl,
                            width: 160,
                            height: 160,
                            colorDark : "#0d121f",
                            colorLight : "#ffffff",
                            correctLevel : QRCode.CorrectLevel.M
                        });
                    } else {
                        throw new Error('QRCode JS is undefined');
                    }
                } catch (e) {
                    console.warn("QR Generator fallback: ", e);
                    document.getElementById('qrcode').innerHTML = `
                        <div style="color: var(--text-muted); font-size: 0.8rem; text-align: center; padding: 10px;">
                            <div style="font-weight: bold; color: var(--text-primary); margin-bottom: 6px;">Link Direct</div>
                            <span style="display:inline-block; font-family: monospace; font-size: 0.85rem; color: var(--accent-orange); background: var(--bg-darker); padding: 4px 8px; border-radius: 4px; word-break: break-all;">` + connectionUrl + `</span>
                        </div>
                    `;
                }

                function showToast(message) {
                    document.getElementById('toast-text').textContent = message;
                    toast.classList.add('show');
                    setTimeout(() => toast.classList.remove('show'), 3500);
                }

                function escapeHtml(unsafe) {
                    return unsafe
                         .replace(/&/g, "&amp;")
                         .replace(/</g, "&lt;")
                         .replace(/>/g, "&gt;")
                         .replace(/"/g, "&quot;")
                         .replace(/'/g, "&#039;");
                }

                function copyAddress() {
                    const text = document.getElementById('pc-url-display').textContent;
                    navigator.clipboard.writeText(text).then(() => {
                        showToast('Link copied to clipboard!');
                        const copyBtn = document.getElementById('copy-btn');
                        copyBtn.innerHTML = `<svg viewBox="0 0 24 24" style="fill:#10b981"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg> Copied!`;
                        setTimeout(() => {
                            copyBtn.innerHTML = `<svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg> Copy`;
                        }, 2000);
                    }).catch(err => {
                        console.error('Failed to copy: ', err);
                    });
                }

                function getFileIconSvg(fileName) {
                    const ext = fileName.split('.').pop().toLowerCase();
                    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) {
                        return `<svg style="width:20px;height:20px;fill:#fbbf24" viewBox="0 0 24 24"><path d="M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-4.86 8.86l-3 3.87L9 13.14 6 17h12l-3.86-5.14z"/></svg>`;
                    } else if (['mp4', 'mkv', 'avi', 'mov', 'wmv', '3gp', 'webm'].includes(ext)) {
                        return `<svg style="width:20px;height:20px;fill:#ef4444" viewBox="0 0 24 24"><path d="M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"/></svg>`;
                    } else if (['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac'].includes(ext)) {
                        return `<svg style="width:20px;height:20px;fill:#8b5cf6" viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>`;
                    } else if (['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'rtf', 'csv', 'md'].includes(ext)) {
                        return `<svg style="width:20px;height:20px;fill:#3b82f6" viewBox="0 0 24 24"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>`;
                    } else if (['zip', 'rar', '7z', 'tar', 'gz', 'bz2'].includes(ext)) {
                        return `<svg style="width:20px;height:20px;fill:#10b981" viewBox="0 0 24 24"><path d="M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6 10h-2v-2h2v2zm0-3h-2v-2h2v2zm0-3h-2V8h2v2z"/></svg>`;
                    } else {
                        return `<svg style="width:20px;height:20px;fill:#94a3b8" viewBox="0 0 24 24"><path d="M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z"/></svg>`;
                    }
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
                            filesList.innerHTML = '<li class="file-item" style="color: var(--text-muted); font-style: italic; font-size: 0.85rem; justify-content: center;">No files currently shared from Phone. Add files on Android to download them here!</li>';
                            return;
                        }
                        filesList.innerHTML = '';
                        files.forEach((file, index) => {
                            const item = document.createElement('li');
                            item.className = 'file-item';
                            item.innerHTML = '<div class="file-item-left">' +
                                '<div class="file-icon-wrapper">' +
                                    getFileIconSvg(file.name) +
                                '</div>' +
                                '<div class="file-info">' +
                                    '<span class="file-name" title="' + escapeHtml(file.name) + '">' + escapeHtml(file.name) + '</span>' +
                                    '<span class="file-size">' + formatBytes(file.size) + '</span>' +
                                '</div>' +
                                '</div>' +
                                '<a href="/download?index=' + index + '" class="btn btn-sm download-btn" download="' + escapeHtml(file.name) + '">' +
                                    '<svg style="width:14px;height:14px;fill:currentColor" viewBox="0 0 24 24"><path d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"/></svg>' +
                                    'Download' +
                                '</a>';
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
                                '<span class="staged-name" title="' + escapeHtml(file.name) + '">' + escapeHtml(file.name) + '</span>' +
                                '<span class="staged-size">' + formatBytes(file.size) + '</span>' +
                            '</div>' +
                            '</div>' +
                            '<button class="remove-staged-btn" onclick="removeStagedFile(' + idx + ')">✕</button>';
                        stagedList.appendChild(item);
                    });
                    
                    stageSummary.innerHTML = '<div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.85rem; font-size: 0.82rem; color: var(--text-secondary); border-top: 1px solid var(--border-color); padding-top: 0.75rem;">' +
                        '<span>Prepared: <strong>' + stagedFiles.length + ' file' + (stagedFiles.length > 1 ? 's' : '') + '</strong></span>' +
                        '<span>Total Size: <strong>' + formatBytes(totalSize) + '</strong></span>' +
                        '</div>' +
                        '<div style="display: flex; gap: 0.75rem;">' +
                            '<button class="btn" style="width: 100%; display: flex; justify-content: center; background: var(--accent-green); border: none; font-family: inherit; box-shadow: 0 4px 12px rgba(16, 185, 129, 0.2);" onclick="shareStagedFiles()">' +
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

// Self-contained Lightweight Multicast DNS (mDNS) Responder
// Resolves "<alias>.local" directly to this Android device's local network IP address on port 5353
class MdnsResponder(
    private val context: android.content.Context,
    private val alias: String,
    private val ip: String
) {
    private var thread: Thread? = null
    private var running = false
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var socket: java.net.MulticastSocket? = null

    fun start() {
        if (running) return
        running = true

        try {
            val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wifi?.createMulticastLock("MdnsResponderLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("MdnsResponder", "Acquired WiFi Multicast Lock for mDNS resolving on alias: $alias")
        } catch (e: Exception) {
            Log.e("MdnsResponder", "Failed to acquire multicast lock: ${e.message}")
        }

        thread = Thread {
            runResponder()
        }.apply {
            name = "mDNS-Responder"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
        multicastLock = null

        try {
            thread?.interrupt()
        } catch (e: Exception) {}
        thread = null
        Log.d("MdnsResponder", "mDNS resolver stopped")
    }

    private fun runResponder() {
        val groupAddress = java.net.InetAddress.getByName("224.0.0.251")
        val port = 5353
        
        // Form the binary DNS labels for "<alias>.local."
        val nameBytes = try {
            java.io.ByteArrayOutputStream().apply {
                write(alias.length)
                write(alias.toByteArray(Charsets.US_ASCII))
                write(5)
                write("local".toByteArray(Charsets.US_ASCII))
                write(0)
            }.toByteArray()
        } catch (e: Exception) {
            Log.e("MdnsResponder", "Error converting name labels: ${e.message}")
            return
        }

        // Precompile standard DNS authoritative A-record response bytes
        val answerBytes = try {
            java.io.ByteArrayOutputStream().apply {
                // Header (12 bytes)
                write(byteArrayOf(0x00, 0x00))                     // Transaction ID (0x0000)
                write(byteArrayOf(0x84.toByte(), 0x00))           // Flags: Response, Authoritative
                write(byteArrayOf(0x00, 0x00))                     // Questions: 0
                write(byteArrayOf(0x00, 0x01))                     // Answer RRs: 1
                write(byteArrayOf(0x00, 0x00))                     // Authority RRs: 0
                write(byteArrayOf(0x00, 0x00))                     // Additional RRs: 0

                // Answer: Name label
                write(nameBytes)
                
                // Type & Class
                write(byteArrayOf(0x00, 0x01))                     // Type: A (IPv4 Address)
                write(byteArrayOf(0x80.toByte(), 0x01))           // Class: IN (with cache-flush bit 1)
                
                // TTL (300 seconds)
                write(byteArrayOf(0x00, 0x00, 0x01, 0x2c.toByte()))
                
                // Data length (4 bytes for IPv4)
                write(byteArrayOf(0x00, 0x04))
                
                // Local IPv4 bytes
                val parts = ip.split(".")
                if (parts.size == 4) {
                    for (part in parts) {
                        write(part.toInt().toByte().toInt())
                    }
                } else {
                    write(byteArrayOf(127, 0, 0, 1))
                }
            }.toByteArray()
        } catch (e: Exception) {
            Log.e("MdnsResponder", "Error preparing binary answer: ${e.message}")
            return
        }

        try {
            val ms = java.net.MulticastSocket(port)
            socket = ms
            ms.reuseAddress = true
            
            // Join multicast group on all active, multicast-supporting non-loopback network interfaces
            var joined = false
            try {
                val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                for (iface in interfaces) {
                    if (iface.isUp && !iface.isLoopback && iface.supportsMulticast()) {
                        try {
                            ms.joinGroup(java.net.InetSocketAddress(groupAddress, port), iface)
                            joined = true
                        } catch (ex: Exception) {}
                    }
                }
            } catch (e: Exception) {}

            if (!joined) {
                ms.joinGroup(groupAddress)
            }

            val buffer = ByteArray(2048)
            Log.d("MdnsResponder", "mDNS Resolver actively listening for '${alias}.local' queries on 224.0.0.251:5353")

            while (running) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                try {
                    ms.receive(packet)
                    if (!running) break
                    
                    val len = packet.length
                    if (len < 12) continue
                    
                    val queryBytes = packet.data
                    var matchFound = false
                    
                    // Simple search inside packet buffer to find query for the alias
                    for (i in 12..(len - nameBytes.size)) {
                        var match = true
                        for (j in nameBytes.indices) {
                            if (queryBytes[i + j] != nameBytes[j]) {
                                match = false
                                break
                            }
                        }
                        if (match) {
                            matchFound = true
                            break
                        }
                    }

                    if (matchFound) {
                        Log.d("MdnsResponder", "mDNS query match! Replying with IP $ip to client ${packet.address}:${packet.port}")
                        
                        // Send response packet directly unicast back to sender
                        val responsePacket = java.net.DatagramPacket(answerBytes, answerBytes.size, packet.socketAddress)
                        ms.send(responsePacket)
                        
                        // Also broadcast back multicast to 5353
                        val multicastResponsePacket = java.net.DatagramPacket(answerBytes, answerBytes.size, groupAddress, port)
                        ms.send(multicastResponsePacket)
                    }
                } catch (e: java.io.IOException) {
                    if (!running) break
                } catch (e: Exception) {
                    Log.e("MdnsResponder", "Error processing mDNS packet: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("MdnsResponder", "mDNS Resolver thread main loop failed: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {}
            socket = null
        }
    }
}


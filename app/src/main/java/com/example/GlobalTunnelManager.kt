package com.example

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.lang.StringBuilder

object GlobalTunnelManager {
    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    
    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl = _tunnelUrl.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _tunnelError = MutableStateFlow<String?>(null)
    val tunnelError = _tunnelError.asStateFlow()

    data class TunnelConfig(
        val serviceName: String,
        val host: String,
        val port: Int,
        val user: String,
        val urlPatterns: List<Regex>
    )

    private val configs = listOf(
        TunnelConfig(
            serviceName = "Pinggy (HTTPS port 443)",
            host = "a.pinggy.io",
            port = 443,
            user = "any",
            urlPatterns = listOf(
                Regex("https?://[a-zA-Z0-9.-]+\\.pinggy\\.link"),
                Regex("[a-zA-Z0-9.-]+\\.pinggy\\.link")
            )
        ),
        TunnelConfig(
            serviceName = "Pinggy (SSH port 22)",
            host = "a.pinggy.io",
            port = 22,
            user = "any",
            urlPatterns = listOf(
                Regex("https?://[a-zA-Z0-9.-]+\\.pinggy\\.link"),
                Regex("[a-zA-Z0-9.-]+\\.pinggy\\.link")
            )
        ),
        TunnelConfig(
            serviceName = "Localhost.run (HTTPS port 443)",
            host = "ssh.localhost.run",
            port = 443,
            user = "nokey",
            urlPatterns = listOf(
                Regex("https?://[a-zA-Z0-9.-]+\\.(?:lhr\\.life|lhr\\.rocks)"),
                Regex("[a-zA-Z0-9.-]+\\.(?:lhr\\.life|lhr\\.rocks)")
            )
        ),
        TunnelConfig(
            serviceName = "Localhost.run (SSH port 22)",
            host = "ssh.localhost.run",
            port = 22,
            user = "nokey",
            urlPatterns = listOf(
                Regex("https?://[a-zA-Z0-9.-]+\\.(?:lhr\\.life|lhr\\.rocks)"),
                Regex("[a-zA-Z0-9.-]+\\.(?:lhr\\.life|lhr\\.rocks)")
            )
        ),
        TunnelConfig(
            serviceName = "Serveo.net (SSH port 22)",
            host = "serveo.net",
            port = 22,
            user = "anyuser",
            urlPatterns = listOf(
                Regex("https?://[a-zA-Z0-9.-]+\\.serveo\\.net")
            )
        )
    )

    fun startTunnel(localPort: Int, scope: CoroutineScope) {
        if (_isConnecting.value || _tunnelUrl.value != null) return
        _isConnecting.value = true
        _tunnelError.value = null
        _tunnelUrl.value = null

        scope.launch(Dispatchers.IO) {
            val errorBuilder = StringBuilder()
            var success = false

            for (config in configs) {
                if (!_isConnecting.value) break // Connection cancelled by the user

                Log.d("GlobalTunnel", "Executing tunnel config: ${config.serviceName} at ${config.host}:${config.port}")
                try {
                    val jsch = JSch()
                    
                    // Generate and register an in-memory RSA keypair for publickey authentication.
                    // Most public SSH tunnel hosts (like pinggy and localhost.run) require client signatures
                    // on publickey auth even for anonymous/public connections.
                    try {
                        val privateKeyOS = java.io.ByteArrayOutputStream()
                        val publicKeyOS = java.io.ByteArrayOutputStream()
                        val kpair = com.jcraft.jsch.KeyPair.genKeyPair(jsch, com.jcraft.jsch.KeyPair.RSA, 2048)
                        kpair.writePrivateKey(privateKeyOS)
                        kpair.writePublicKey(publicKeyOS, "aistudio-secure-tunnel")
                        jsch.addIdentity("tunnel_session_key", privateKeyOS.toByteArray(), publicKeyOS.toByteArray(), null)
                        kpair.dispose()
                        Log.d("GlobalTunnel", "Successfully generated in-memory RSA keypair for SSH publickey auth.")
                    } catch (keyEx: Exception) {
                        Log.e("GlobalTunnel", "In-memory RSA key generation failed: ${keyEx.message}")
                    }

                    val hostSession = jsch.getSession(config.user, config.host, config.port)
                    hostSession.setConfig("StrictHostKeyChecking", "no")
                    
                    // Add modern JSch options to be extra resilient
                    hostSession.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                    
                    hostSession.connect(12000) // 12s socket connection timeout
                    
                    // Request remote forwarding: Maps remote gateway's port 80 to device localPort.
                    // We try empty bind address "" first, fallback to "0.0.0.0", then use default.
                    // We use "127.0.0.1" as the local address to prevent IPv6 localhost mismatches.
                    try {
                        hostSession.setPortForwardingR("", 80, "127.0.0.1", localPort)
                    } catch (fe1: Exception) {
                        Log.w("GlobalTunnel", "Failed to bind empty remote host, trying 0.0.0.0: ${fe1.message}")
                        try {
                            hostSession.setPortForwardingR("0.0.0.0", 80, "127.0.0.1", localPort)
                        } catch (fe2: Exception) {
                            Log.w("GlobalTunnel", "Failed to bind 0.0.0.0 remote host, trying *: ${fe2.message}")
                            try {
                                hostSession.setPortForwardingR("*", 80, "127.0.0.1", localPort)
                            } catch (fe3: Exception) {
                                Log.w("GlobalTunnel", "Failed to bind * remote host, trying default: ${fe3.message}")
                                hostSession.setPortForwardingR(80, "127.0.0.1", localPort)
                            }
                        }
                    }
                    
                    session = hostSession
                    Log.d("GlobalTunnel", "Established SSH session for ${config.serviceName}. Opening shell to grab URL...")

                    val chan = hostSession.openChannel("shell") as ChannelShell
                    chan.setPty(true) // We need a pseudo-terminal for them to print the URL sometimes
                    shellChannel = chan
                    
                    val inputStream: InputStream = chan.inputStream
                    chan.connect(8000) // 8s shell activation timeout
                    
                    val responseAccumulator = StringBuilder()
                    val buffer = ByteArray(2048)
                    val startTime = System.currentTimeMillis()
                    var urlFound = false
                    
                    // Process remote stream chunks to capture the assigned domain URL
                    while (chan.isConnected && !urlFound && (System.currentTimeMillis() - startTime < 15000)) {
                        if (inputStream.available() > 0) {
                            val readBytes = inputStream.read(buffer)
                            if (readBytes > 0) {
                                val chunk = String(buffer, 0, readBytes, Charsets.UTF_8)
                                responseAccumulator.append(chunk)
                                Log.d("GlobalTunnel", "Terminal payload [${config.serviceName}]: $chunk")
                                
                                // Strip ANSI escape / styling sequences
                                val cleanText = responseAccumulator.toString()
                                    .replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
                                
                                for (pattern in config.urlPatterns) {
                                    val match = pattern.find(cleanText)
                                    if (match != null) {
                                        var foundUrl = match.value
                                        if (!foundUrl.startsWith("http://") && !foundUrl.startsWith("https://")) {
                                            foundUrl = "https://$foundUrl"
                                        }
                                        _tunnelUrl.value = foundUrl
                                        urlFound = true
                                        success = true
                                        _isConnecting.value = false
                                        Log.i("GlobalTunnel", "TUNNEL ACTIVE! Engine ${config.serviceName} resolved: $foundUrl")
                                        
                                        // Start a background drainer so the window buffer doesn't fill up and freeze SSH
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val drainBuffer = ByteArray(4096)
                                                while (chan.isConnected && !chan.isClosed && session === hostSession) {
                                                    if (inputStream.available() > 0) {
                                                        inputStream.read(drainBuffer) // discard to prevent blocking
                                                    } else {
                                                        Thread.sleep(200)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.d("GlobalTunnel", "Drainer stopped.")
                                            }
                                        }
                                        break
                                    }
                                }
                            }
                        } else {
                            Thread.sleep(150)
                        }
                    }

                    if (urlFound) {
                        break // Working tunnel established, terminate cascade trial
                    } else {
                        val outputSample = responseAccumulator.toString().take(200)
                        Log.w("GlobalTunnel", "Handshake succeeded but parsing domain timed out on ${config.serviceName}. Output: $outputSample")
                        errorBuilder.append("- ${config.serviceName}: Parsing timed out.\n")
                        stopAttempt()
                    }

                } catch (e: Exception) {
                    val rootMsg = e.localizedMessage ?: e.message ?: e.toString()
                    Log.e("GlobalTunnel", "Config ${config.serviceName} error: $rootMsg")
                    errorBuilder.append("- ${config.serviceName}: $rootMsg\n")
                    stopAttempt()
                }
            }

            if (!success && _isConnecting.value) {
                _isConnecting.value = false
                val fullError = errorBuilder.toString().trim()
                _tunnelError.value = if (fullError.isNotEmpty()) {
                    "All global tunnel servers failed to connect. Outbound SSH connections are likely blocked by your Wi-Fi router or cellular firewall.\n\nError details:\n$fullError"
                } else {
                    "All global tunnel servers failed to connect. Standard outbound SSH (Port 22/443) is blocked on this network."
                }
            }
        }
    }

    private fun stopAttempt() {
        try {
            shellChannel?.disconnect()
        } catch (e: Exception) {}
        shellChannel = null
        
        try {
            session?.disconnect()
        } catch (e: Exception) {}
        session = null
    }

    fun stopTunnel() {
        Log.d("GlobalTunnel", "Shutting down global tunnel...")
        _isConnecting.value = false
        _tunnelUrl.value = null
        _tunnelError.value = null
        stopAttempt()
    }
}

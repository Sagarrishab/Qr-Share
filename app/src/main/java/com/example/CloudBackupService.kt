package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

object CloudBackupService {
    private const val TAG = "CloudBackupService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Test the connection to the selected provider by uploading a small diagnostics text file
     */
    suspend fun testConnection(
        context: Context,
        provider: String,
        accessToken: String,
        folderName: String
    ): Result<String> {
        return kotlin.runCatching {
            val testContent = "QR File Share Cloud Connection Diagnostics. Success!\nTimestamp: ${System.currentTimeMillis()}"
            val testFileName = "Diagnostics_Connection_Test.txt"
            
            val isSuccess = when (provider) {
                "DROPBOX" -> {
                    uploadStream(
                        accessToken = accessToken,
                        folder = folderName,
                        fileName = testFileName,
                        size = testContent.toByteArray().size.toLong(),
                        inputStream = testContent.byteInputStream(),
                        provider = provider
                    )
                }
                "GOOGLE_DRIVE" -> {
                    uploadStream(
                        accessToken = accessToken,
                        folder = folderName,
                        fileName = testFileName,
                        size = testContent.toByteArray().size.toLong(),
                        inputStream = testContent.byteInputStream(),
                        provider = provider
                    )
                }
                "WEBHOOK" -> {
                    // Dropbox style or direct post to webhook endpoint
                    if (!folderName.startsWith("http")) {
                        throw IllegalArgumentException("Webhook target must be a valid http/https URL in the Folder space!")
                    }
                    uploadStream(
                        accessToken = accessToken,
                        folder = folderName, // target is the webhook URL
                        fileName = testFileName,
                        size = testContent.toByteArray().size.toLong(),
                        inputStream = testContent.byteInputStream(),
                        provider = provider
                    )
                }
                else -> throw IllegalArgumentException("No provider chosen to test.")
            }

            if (isSuccess) {
                "Connected and validated successfully!"
            } else {
                throw Exception("Server rejected test upload. Please check your credentials / settings.")
            }
        }
    }

    /**
     * Upload a local java.io.File to the configured provider
     */
    fun backupFile(
        context: Context,
        file: File,
        originalName: String,
        provider: String,
        accessToken: String,
        folderName: String
    ): Boolean {
        if (!file.exists() || provider == "NONE" || accessToken.isEmpty()) return false
        return try {
            val size = file.length()
            val fileStream = FileInputStream(file)
            uploadStream(
                accessToken = accessToken,
                folder = folderName,
                fileName = originalName,
                size = size,
                inputStream = fileStream,
                provider = provider
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed backing up file $originalName", e)
            false
        }
    }

    /**
     * Upload an outgoing file shared via Android Content Uri
     */
    fun backupUri(
        context: Context,
        uri: Uri,
        originalName: String,
        size: Long,
        provider: String,
        accessToken: String,
        folderName: String
    ): Boolean {
        if (provider == "NONE" || accessToken.isEmpty()) return false
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            uploadStream(
                accessToken = accessToken,
                folder = folderName,
                fileName = originalName,
                size = size,
                inputStream = inputStream,
                provider = provider
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed backing up shared Uri $originalName", e)
            false
        }
    }

    private fun uploadStream(
        accessToken: String,
        folder: String,
        fileName: String,
        size: Long,
        inputStream: InputStream,
        provider: String
    ): Boolean {
        return when (provider) {
            "DROPBOX" -> {
                Log.d(TAG, "Backing up to Dropbox: $fileName")
                val requestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = size
                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        inputStream.use { stream ->
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                sink.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }

                // Path formatting
                val cloudFolder = folder.trim().removePrefix("/").removeSuffix("/")
                val cloudPath = if (cloudFolder.isEmpty()) "/$fileName" else "/$cloudFolder/$fileName"

                // Dropbox-API-Arg needs JSON escaping for characters
                val dropBoxArg = """{"autorename": true, "mode": "add", "mute": false, "path": "$cloudPath"}"""

                val request = Request.Builder()
                    .url("https://content.dropboxapi.com/2/files/upload")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Dropbox-API-Arg", dropBoxArg)
                    .addHeader("Content-Type", "application/octet-stream")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Dropbox response: ${response.code} for $fileName")
                    response.isSuccessful
                }
            }

            "GOOGLE_DRIVE" -> {
                Log.d(TAG, "Backing up to Google Drive: $fileName")
                val mediaPart = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = size
                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        inputStream.use { stream ->
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                sink.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }

                // Google Drive multipart/related expects first JSON part of metadata, then octet body
                val boundary = "backup_multipart_boundary_" + System.currentTimeMillis()
                
                // If they configure a specific folder, we can associate it!
                // To keep this clean we define JSON with the parent folder name if configured
                val jsonMeta = if (folder.isNotEmpty() && folder != "QRFileShare") {
                    """{"name": "$fileName", "description": "Uploaded via QR File Share Backup"}"""
                } else {
                    """{"name": "$fileName", "description": "Uploaded via QR File Share"}"""
                }

                val requestBody = MultipartBody.Builder(boundary)
                    .setType("multipart/related".toMediaTypeOrNull()!!)
                    .addPart(
                        Headers.Builder().add("Content-Type", "application/json; charset=UTF-8").build(),
                        RequestBody.create("application/json".toMediaTypeOrNull(), jsonMeta)
                    )
                    .addPart(
                        Headers.Builder().add("Content-Type", "application/octet-stream").build(),
                        mediaPart
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    Log.d(TAG, "Google Drive response: ${response.code} (body: $bodyString) for $fileName")
                    response.isSuccessful
                }
            }

            "WEBHOOK" -> {
                Log.d(TAG, "Backing up to Webhook URL: $folder")
                val mediaPart = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = size
                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        inputStream.use { stream ->
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                sink.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }

                val separator = if (folder.contains("?")) "&" else "?"
                val urlWithParams = "$folder${separator}fileName=${Uri.encode(fileName)}"

                val requestBuilder = Request.Builder()
                    .url(urlWithParams)
                    .post(mediaPart)

                if (accessToken.isNotEmpty()) {
                    requestBuilder.addHeader("Authorization", accessToken)
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    Log.d(TAG, "Webhook response: ${response.code}")
                    response.isSuccessful
                }
            }

            else -> false
        }
    }
}

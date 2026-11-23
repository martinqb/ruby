package com.martin.ruby.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * The few Google Drive calls Ruby needs, as plain HTTPS requests, all in the app's
 * private `appDataFolder`. See developers.google.com/drive/api/guides/appdata.
 */
class DriveClient(private val accessToken: String) {

    /** A Drive error. [code] is the HTTP status; 401 means the token needs refreshing. */
    class Error(val code: Int, message: String) : IOException(message)

    suspend fun list(): List<DriveFile> = withContext(Dispatchers.IO) {
        val query = "spaces=appDataFolder&pageSize=100" +
            "&orderBy=" + enc("modifiedTime desc") +
            "&fields=" + enc("files($FIELDS)")
        val json = JSONObject(call("GET", "$API/files?$query").text())
        val files = json.optJSONArray("files") ?: JSONArray()
        List(files.length()) { parse(files.getJSONObject(it)) }
    }

    /**
     * Uploads a file into the app folder. Uses a resumable upload, which works for any
     * size (a simple upload is limited to 5 MB): first the name, then the bytes.
     */
    suspend fun upload(name: String, bytes: ByteArray): DriveFile = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put("appDataFolder"))
            .put("mimeType", MIME)
            .toString().toByteArray()
        val session = call(
            "POST", "$UPLOAD/files?uploadType=resumable&fields=" + enc(FIELDS),
            body = metadata, contentType = "application/json; charset=UTF-8",
            headers = mapOf("X-Upload-Content-Type" to MIME, "X-Upload-Content-Length" to bytes.size.toString()),
        )
        val location = session.getHeaderField("Location") ?: throw Error(0, "Drive didn't start the upload.")
        session.disconnect()
        parse(JSONObject(call("PUT", location, body = bytes, contentType = MIME).text()))
    }

    suspend fun download(id: String): ByteArray = withContext(Dispatchers.IO) {
        call("GET", "$API/files/${enc(id)}?alt=media").inputStream.use { it.readBytes() }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        call("DELETE", "$API/files/${enc(id)}").disconnect()
    }

    // HTTP

    /** Makes a request and checks it worked; throws [Error] with Google's message if not. */
    private fun call(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            contentType?.let { setRequestProperty("Content-Type", it) }
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
            }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream?.use { it.readBytes().decodeToString() }
            connection.disconnect()
            throw Error(code, "Google Drive said: ${googleMessage(detail) ?: "HTTP $code"}")
        }
        return connection
    }

    private fun HttpURLConnection.text(): String =
        inputStream.use { it.readBytes().decodeToString() }.also { disconnect() }

    /** Google's errors look like {"error": {"message": "..."}}. */
    private fun googleMessage(body: String?): String? =
        runCatching { JSONObject(body ?: return null).getJSONObject("error").getString("message") }.getOrNull()

    private fun parse(json: JSONObject) = DriveFile(
        id = json.getString("id"),
        name = json.optString("name"),
        sizeBytes = json.optString("size").toLongOrNull() ?: 0L,
        modifiedMillis = json.optString("modifiedTime").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
    )

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FIELDS = "id,name,size,modifiedTime"
        const val MIME = "application/octet-stream"
    }
}

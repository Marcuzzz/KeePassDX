package com.kunzisoft.keepass.database.sync.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Client for keepass-server (https://github.com/Marcuzzz/keepass-server, API v1).
 * The server only stores encrypted .kdbx files; every upload states the revision it is based on
 * (If-Match) and the server refuses stale uploads with 412, so nothing is ever overwritten.
 */
class KpsClient(
    baseUrl: String,
    var token: String? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) {

    val baseUrl: String = normalizeUrl(baseUrl)

    /** The server could not be reached (or answered with a gateway error): continue offline. */
    class OfflineException(message: String, cause: Throwable? = null) : IOException(message, cause)

    class ApiException(
        val status: Int,
        val code: String,
        message: String,
        val currentRevision: Int? = null
    ) : IOException(message)

    data class VaultInfo(
        val id: String,
        val name: String,
        val role: String,
        val revision: Int,
        val conflicts: Int
    ) {
        val canWrite: Boolean get() = role != "reader"
    }

    data class Download(val revision: Int, val sha256: String)

    data class UploadResult(val revision: Int, val sha256: String, val unchanged: Boolean)

    data class ConnectionTest(val ok: Boolean, val step: Step, val message: String) {
        enum class Step { REACH, SERVER, LOGIN, DONE }
    }

    private fun open(method: String, path: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val connection = try {
            URL(baseUrl + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw OfflineException("Invalid server URL: $baseUrl", e)
        }
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        return connection
    }

    /** Sends the request and maps transport failures and error statuses to exceptions. */
    private fun execute(connection: HttpURLConnection, body: (() -> InputStream)? = null, length: Long = -1): Int {
        val status = try {
            if (body != null) {
                connection.doOutput = true
                if (length >= 0) connection.setFixedLengthStreamingMode(length)
                connection.outputStream.use { output -> body().use { it.copyTo(output) } }
            }
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw OfflineException("Cannot reach ${URL(baseUrl).host}: ${e.message}", e)
        }
        if (status >= 500 && status != 501) {
            connection.disconnect()
            throw OfflineException("Server unavailable ($status)")
        }
        if (status >= 400 || (status in 300..399 && status != 304)) {
            val error = try {
                connection.errorStream?.bufferedReader()?.use { JSONObject(it.readText()).optJSONObject("error") }
            } catch (e: Exception) {
                null
            }
            connection.disconnect()
            throw ApiException(
                status,
                error?.optString("code", "http_error") ?: "http_error",
                error?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP $status",
                error?.takeIf { it.has("currentRevision") }?.optInt("currentRevision")
            )
        }
        return status
    }

    private fun json(method: String, path: String, body: JSONObject? = null): String {
        val headers = if (body != null) mapOf("Content-Type" to "application/json") else emptyMap()
        val connection = open(method, path, headers)
        try {
            val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
            execute(connection, bytes?.let { { it.inputStream() } }, bytes?.size?.toLong() ?: -1)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            if (e is OfflineException || e is ApiException) throw e
            throw OfflineException("Connection lost: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    fun status(): JSONObject = JSONObject(json("GET", "/api/v1/status"))

    fun login(username: String, password: String, deviceName: String): String {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("deviceName", deviceName)
        val newToken = JSONObject(json("POST", "/api/v1/auth/login", body)).getString("token")
        token = newToken
        return newToken
    }

    fun logout() {
        json("POST", "/api/v1/auth/logout")
        token = null
    }

    fun me(): String = JSONObject(json("GET", "/api/v1/me")).getString("username")

    fun listVaults(): List<VaultInfo> {
        val array = JSONArray(json("GET", "/api/v1/vaults"))
        return (0 until array.length()).map { vaultInfo(array.getJSONObject(it)) }
    }

    fun getVault(vaultId: String): VaultInfo = vaultInfo(JSONObject(json("GET", "/api/v1/vaults/${encode(vaultId)}")))

    fun createVault(name: String): VaultInfo =
        vaultInfo(JSONObject(json("POST", "/api/v1/vaults", JSONObject().put("name", name))))

    /**
     * Downloads the current database into [target], verified against its SHA-256.
     * Returns null when the server is still at [knownRevision] (nothing written).
     */
    fun download(vaultId: String, knownRevision: Int?, target: File): Download? {
        val headers = if (knownRevision != null && knownRevision > 0)
            mapOf("If-None-Match" to "\"$knownRevision\"", "Accept" to "application/octet-stream")
        else mapOf("Accept" to "application/octet-stream")
        val connection = open("GET", "/api/v1/vaults/${encode(vaultId)}/content", headers)
        try {
            if (execute(connection) == 304) return null
            val expectedSha = connection.getHeaderField("X-KPS-SHA256") ?: ""
            val revision = connection.getHeaderField("X-KPS-Revision")?.toIntOrNull()
                ?: throw ApiException(502, "bad_response", "Missing revision header")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
            } catch (e: IOException) {
                throw OfflineException("Download interrupted: ${e.message}", e)
            }
            val sha = digest.digest().toHex()
            if (!sha.equals(expectedSha, ignoreCase = true)) {
                throw OfflineException("Download was incomplete (checksum mismatch)")
            }
            return Download(revision, sha)
        } finally {
            connection.disconnect()
        }
    }

    /** Uploads [file] as the next revision after [baseRevision]; ApiException 412 when that is stale. */
    fun upload(vaultId: String, file: File, baseRevision: Int, note: String? = null): UploadResult {
        val headers = mutableMapOf(
            "Content-Type" to "application/octet-stream",
            "If-Match" to "\"$baseRevision\"",
            "X-KPS-SHA256" to sha256(file)
        )
        note?.let { headers["X-KPS-Note"] = encode(it) }
        val connection = open("PUT", "/api/v1/vaults/${encode(vaultId)}/content", headers)
        try {
            execute(connection, { file.inputStream() }, file.length())
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return UploadResult(result.getInt("revision"), result.getString("sha256"), result.optBoolean("unchanged"))
        } catch (e: IOException) {
            if (e is OfflineException || e is ApiException) throw e
            throw OfflineException("Connection lost: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /** Stores [file] as a conflict copy, for changes that can't be merged (e.g. the master key changed). */
    fun uploadConflict(vaultId: String, file: File, baseRevision: Int, reason: String): String {
        val connection = open("POST", "/api/v1/vaults/${encode(vaultId)}/conflicts", mapOf(
            "Content-Type" to "application/octet-stream",
            "X-KPS-Base-Revision" to baseRevision.toString(),
            "X-KPS-Reason" to encode(reason)
        ))
        try {
            execute(connection, { file.inputStream() }, file.length())
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("id")
        } finally {
            connection.disconnect()
        }
    }

    private fun vaultInfo(json: JSONObject) = VaultInfo(
        id = json.getString("id"),
        name = json.getString("name"),
        role = json.optString("role", "reader"),
        revision = json.optInt("revision"),
        conflicts = json.optInt("conflicts")
    )

    companion object {
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
            else "https://$trimmed"
        }

        /** Plain HTTP is only acceptable on a local network (testing); anything else must use HTTPS. */
        fun isInsecure(url: String): Boolean {
            val parsed = try { URL(normalizeUrl(url)) } catch (e: Exception) { return false }
            if (!parsed.protocol.equals("http", true)) return false
            val host = parsed.host
            val privateHost = host == "localhost" || host.endsWith(".local") || host.startsWith("10.")
                    || host.startsWith("192.168.") || host.startsWith("127.")
                    || Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
            return !privateHost
        }

        /** Runs status → login → (logout): what the "Test connection" button reports. */
        fun testConnection(url: String, username: String, password: String, deviceName: String): ConnectionTest {
            val client = KpsClient(url, connectTimeoutMs = 8_000, readTimeoutMs = 15_000)
            try {
                if (client.status().optString("server") != "keepass-server") {
                    return ConnectionTest(false, ConnectionTest.Step.SERVER, "Not a keepass-server")
                }
            } catch (e: OfflineException) {
                return ConnectionTest(false, ConnectionTest.Step.REACH, e.message ?: "Server not reachable")
            } catch (e: Exception) {
                return ConnectionTest(false, ConnectionTest.Step.SERVER, "Not a keepass-server")
            }
            return try {
                client.login(username, password, "$deviceName (connection test)")
                val name = client.me()
                try { client.logout() } catch (e: Exception) { /* token expires anyway */ }
                ConnectionTest(true, ConnectionTest.Step.DONE, name)
            } catch (e: ApiException) {
                ConnectionTest(false, ConnectionTest.Step.LOGIN, e.message ?: "Login failed")
            } catch (e: OfflineException) {
                ConnectionTest(false, ConnectionTest.Step.REACH, e.message ?: "Server not reachable")
            }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

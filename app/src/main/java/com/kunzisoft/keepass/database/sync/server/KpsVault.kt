package com.kunzisoft.keepass.database.sync.server

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Local copy of a keepass-server vault: `files/keepass_server/<vault-id>/<name>.kdbx` plus `state.json`.
 *
 * The app opens and saves the local file through a normal file:// URI, so recent files, key file,
 * biometric unlock and offline use work exactly like for any other database. [KpsDatabaseSync]
 * keeps it in line with the server.
 */
class KpsVault private constructor(val dir: File, var state: State) {

    data class State(
        val serverUrl: String,
        val vaultId: String,
        val vaultName: String,
        val fileName: String,
        /** Server revision the local file is based on (0 = nothing uploaded yet). */
        var baseRevision: Int = 0,
        var baseSha256: String? = null,
        /** The local file has changes the server has not accepted yet. */
        var dirty: Boolean = false,
        /** User choice: never contact the server for this vault. */
        var workOffline: Boolean = false,
        /** The server copy has another master key; local saves are blocked until the database is unlocked again. */
        var needsReopen: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("serverUrl", serverUrl)
            .put("vaultId", vaultId)
            .put("vaultName", vaultName)
            .put("fileName", fileName)
            .put("baseRevision", baseRevision)
            .put("baseSha256", baseSha256 ?: JSONObject.NULL)
            .put("dirty", dirty)
            .put("workOffline", workOffline)
            .put("needsReopen", needsReopen)

        companion object {
            fun fromJson(json: JSONObject) = State(
                serverUrl = json.getString("serverUrl"),
                vaultId = json.getString("vaultId"),
                vaultName = json.getString("vaultName"),
                fileName = json.getString("fileName"),
                baseRevision = json.optInt("baseRevision"),
                baseSha256 = if (json.isNull("baseSha256")) null else json.optString("baseSha256"),
                dirty = json.optBoolean("dirty"),
                workOffline = json.optBoolean("workOffline"),
                needsReopen = json.optBoolean("needsReopen")
            )
        }
    }

    val file: File get() = File(dir, state.fileName)

    val uri: Uri get() = Uri.fromFile(file)

    fun hasLocalCopy(): Boolean = file.exists() && file.length() > 0

    @Synchronized
    fun save() {
        val tmp = File(dir, "$STATE_FILE.tmp")
        tmp.writeText(state.toJson().toString(2))
        if (!tmp.renameTo(File(dir, STATE_FILE))) throw IOException("Unable to write vault state")
    }

    /** Replaces the local file with [downloaded] (a file in the same directory) as revision [revision]. */
    fun adopt(downloaded: File, revision: Int, sha256: String) {
        if (!downloaded.renameTo(file)) throw IOException("Unable to replace local copy")
        state.baseRevision = revision
        state.baseSha256 = sha256
        state.dirty = false
        save()
    }

    /** Target for writing the database; [commitPartial] moves it over the local file once complete. */
    val partialFile: File get() = File(dir, "${state.fileName}.partial")

    fun commitPartial() {
        if (!partialFile.renameTo(file)) throw IOException("Unable to replace local copy")
    }

    fun tempFile(prefix: String): File = File.createTempFile(prefix, ".tmp", dir)

    companion object {
        private const val ROOT = "keepass_server"
        private const val STATE_FILE = "state.json"

        fun root(context: Context): File = File(context.applicationContext.filesDir, ROOT)

        private fun safeFileName(name: String): String {
            val cleaned = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().take(60)
            return (cleaned.ifEmpty { "database" }) + ".kdbx"
        }

        /** Returns the vault, creating its local directory and state on first use. */
        fun getOrCreate(context: Context, serverUrl: String, vaultId: String, vaultName: String): KpsVault {
            require(Regex("^[0-9a-fA-F-]{36}$").matches(vaultId)) { "Invalid vault id" }
            val dir = File(root(context), vaultId)
            load(dir)?.let { return it }
            dir.mkdirs()
            val vault = KpsVault(dir, State(KpsClient.normalizeUrl(serverUrl), vaultId, vaultName, safeFileName(vaultName)))
            vault.save()
            return vault
        }

        /** The vault whose local file is [uri], or null for any other database. */
        fun fromUri(context: Context, uri: Uri?): KpsVault? {
            if (uri == null || uri.scheme != "file") return null
            val path = uri.path ?: return null
            val file = try { File(path).canonicalFile } catch (e: IOException) { return null }
            val dir = file.parentFile ?: return null
            if (dir.parentFile != root(context).canonicalFile) return null
            return load(dir)?.takeIf { it.file.canonicalFile == file }
        }

        fun all(context: Context): List<KpsVault> =
            root(context).listFiles()?.mapNotNull { load(it) }?.sortedBy { it.state.vaultName.lowercase() } ?: emptyList()

        private fun load(dir: File): KpsVault? {
            val stateFile = File(dir, STATE_FILE)
            if (!stateFile.exists()) return null
            return try {
                KpsVault(dir, State.fromJson(JSONObject(stateFile.readText())))
            } catch (e: Exception) {
                null
            }
        }
    }
}

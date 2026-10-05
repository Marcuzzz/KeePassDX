package com.kunzisoft.keepass.database.sync.server

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kunzisoft.keepass.database.exception.InvalidCredentialsDatabaseException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.security.MessageDigest

/**
 * Sync behaviour against a minimal in-process keepass-server (same If-Match / 412 / 304 rules).
 * The "database" is plain text lines; merging is a union of lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KpsDatabaseSyncTest {

    private lateinit var context: Context
    private lateinit var server: FakeServer
    private lateinit var vault: KpsVault

    /** Request/response for the minimal HTTP/1.1 server below (one request per connection). */
    private class Exchange(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        var status = 200
        val responseHeaders = mutableMapOf<String, String>()
        var responseBody = ByteArray(0)
        fun header(name: String) = headers[name.lowercase()]
    }

    private class FakeServer {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        var revision = 0
        var content = ByteArray(0)
        var readOnly = false
        val conflicts = mutableListOf<ByteArray>()
        val url get() = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    client.use {
                        val exchange = read(it.getInputStream()) ?: return@use
                        handle(exchange)
                        val out = it.getOutputStream()
                        val head = StringBuilder("HTTP/1.1 ${exchange.status} X\r\n")
                        exchange.responseHeaders["Content-Length"] = exchange.responseBody.size.toString()
                        exchange.responseHeaders["Connection"] = "close"
                        exchange.responseHeaders.forEach { (k, v) -> head.append("$k: $v\r\n") }
                        head.append("\r\n")
                        out.write(head.toString().toByteArray())
                        out.write(exchange.responseBody)
                        out.flush()
                    }
                }
            }
        }

        fun stop() = socket.close()

        private fun read(input: InputStream): Exchange? {
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                    if (b != '\r'.code) bytes.write(b)
                }
                return bytes.toString("UTF-8")
            }
            val requestLine = line().split(" ")
            if (requestLine.size < 2) return null
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                val i = header.indexOf(':')
                headers[header.substring(0, i).trim().lowercase()] = header.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return Exchange(requestLine[0], requestLine[1], headers, body)
        }

        private fun handle(exchange: Exchange) {
            when {
                exchange.path == "/api/v1/vaults/$VAULT_ID/content" && exchange.method == "GET" -> get(exchange)
                exchange.path == "/api/v1/vaults/$VAULT_ID/content" && exchange.method == "PUT" -> put(exchange)
                exchange.path == "/api/v1/vaults/$VAULT_ID/conflicts" -> {
                    conflicts += exchange.body
                    json(exchange, 201, JSONObject().put("id", "c${conflicts.size}").toString())
                }
                else -> error(exchange, 404, "not_found")
            }
        }

        private fun get(exchange: Exchange) {
            if (revision == 0) return error(exchange, 404, "empty_vault")
            if (exchange.header("If-None-Match") == "\"$revision\"") {
                exchange.status = 304
                return
            }
            exchange.responseHeaders["X-KPS-Revision"] = revision.toString()
            exchange.responseHeaders["X-KPS-SHA256"] = sha(content)
            exchange.responseBody = content
        }

        private fun put(exchange: Exchange) {
            val body = exchange.body
            if (readOnly) return error(exchange, 403, "forbidden")
            if (revision > 0 && sha(body) == sha(content)) return json(exchange, 200, result(true))
            val base = exchange.header("If-Match")?.trim('"')?.toIntOrNull()
            if (base != revision) return error(exchange, 412, "conflict", revision)
            content = body
            revision++
            json(exchange, 201, result(false))
        }

        private fun result(unchanged: Boolean) =
            JSONObject().put("revision", revision).put("sha256", sha(content)).put("unchanged", unchanged).toString()

        private fun error(exchange: Exchange, status: Int, code: String, current: Int? = null) {
            val error = JSONObject().put("code", code).put("message", code)
            current?.let { error.put("currentRevision", it) }
            json(exchange, status, JSONObject().put("error", error).toString())
        }

        private fun json(exchange: Exchange, status: Int, body: String) {
            exchange.status = status
            exchange.responseHeaders["Content-Type"] = "application/json"
            exchange.responseBody = body.toByteArray()
        }

        fun upload(text: String) {
            content = text.toByteArray()
            revision++
        }
    }

    /** Open database whose content is a set of lines; merge = union, like entries merged by UUID. */
    private inner class FakeDatabase(var lines: Set<String>) : KpsDatabaseSync.OpenDatabase {
        var merges = 0
        override fun merge(remote: File) {
            val text = remote.readText()
            if (text.startsWith("OTHER-KEY")) throw InvalidCredentialsDatabaseException()
            lines = lines + text.lines().filter { it.isNotBlank() }
            merges++
        }

        override fun write() {
            vault.file.writeText(lines.sorted().joinToString("\n"))
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KpsVault.root(context).deleteRecursively()
        server = FakeServer()
        KpsAccount.save(context, KpsAccount(server.url, "alice", "token"))
        vault = KpsVault.getOrCreate(context, server.url, VAULT_ID, "Family")
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun saveLocally(db: FakeDatabase) {
        db.write()
        vault.state.dirty = true
        vault.save()
    }

    @Test
    fun push_uploadsFirstVersion_whenVaultIsEmpty() {
        val db = FakeDatabase(setOf("entry-a"))
        saveLocally(db)
        val result = KpsDatabaseSync.push(context, vault, db)
        assertEquals(KpsDatabaseSync.Outcome.PUSHED, result.outcome)
        assertEquals(1, server.revision)
        assertFalse(vault.state.dirty)
        assertEquals(1, vault.state.baseRevision)
    }

    @Test
    fun push_mergesAndRetries_whenAnotherDeviceUploadedFirst() {
        val db = FakeDatabase(setOf("entry-a"))
        saveLocally(db)
        KpsDatabaseSync.push(context, vault, db)

        server.upload("entry-a\nentry-from-laptop")
        db.lines = db.lines + "entry-from-phone"
        saveLocally(db)
        val result = KpsDatabaseSync.push(context, vault, db)

        assertEquals(KpsDatabaseSync.Outcome.MERGED, result.outcome)
        assertEquals(1, db.merges)
        assertEquals(3, server.revision)
        assertEquals(
            listOf("entry-a", "entry-from-laptop", "entry-from-phone"),
            String(server.content).lines()
        )
        assertFalse(vault.state.dirty)
    }

    @Test
    fun push_keepsChangesLocally_whenServerUnreachable() {
        val db = FakeDatabase(setOf("entry-a"))
        saveLocally(db)
        server.stop()
        val result = KpsDatabaseSync.push(context, vault, db)
        assertEquals(KpsDatabaseSync.Outcome.OFFLINE, result.outcome)
        assertTrue(vault.state.dirty)
        assertEquals("entry-a", vault.file.readText())
    }

    @Test
    fun push_doesNothing_whenWorkingOffline() {
        val db = FakeDatabase(setOf("entry-a"))
        saveLocally(db)
        vault.state.workOffline = true
        val result = KpsDatabaseSync.push(context, vault, db)
        assertEquals(KpsDatabaseSync.Outcome.WORK_OFFLINE, result.outcome)
        assertEquals(0, server.revision)
        assertTrue(vault.state.dirty)
    }

    @Test
    fun push_reportsReadOnly_whenUserIsReader() {
        server.readOnly = true
        val db = FakeDatabase(setOf("entry-a"))
        saveLocally(db)
        assertEquals(KpsDatabaseSync.Outcome.READ_ONLY, KpsDatabaseSync.push(context, vault, db).outcome)
        assertTrue(vault.state.dirty)
    }

    @Test
    fun push_storesConflictCopy_whenMasterKeyChangedElsewhere() {
        val db = FakeDatabase(setOf("entry-a"))
        saveLocally(db)
        KpsDatabaseSync.push(context, vault, db)
        server.upload("OTHER-KEY\nentry-a")
        db.lines = db.lines + "entry-from-phone"
        saveLocally(db)

        val result = KpsDatabaseSync.push(context, vault, db)

        assertEquals(KpsDatabaseSync.Outcome.KEY_CHANGED, result.outcome)
        assertEquals(1, server.conflicts.size)
        assertTrue(String(server.conflicts[0]).contains("entry-from-phone"))
        assertTrue(vault.dir.listFiles()!!.any { it.name.startsWith("conflict-") })
        // Local copy now holds the server version; saving is blocked until unlocked again
        assertTrue(vault.file.readText().startsWith("OTHER-KEY"))
        assertTrue(vault.state.needsReopen)
        assertFalse(vault.state.dirty)
    }

    @Test
    fun beforeLoad_downloadsNewerRevision_whenNoLocalChanges() {
        server.upload("entry-a")
        assertEquals(KpsDatabaseSync.Outcome.PULLED, KpsDatabaseSync.beforeLoad(context, vault).outcome)
        assertEquals("entry-a", vault.file.readText())
        assertEquals(KpsDatabaseSync.Outcome.UP_TO_DATE, KpsDatabaseSync.beforeLoad(context, vault).outcome)
        server.upload("entry-a\nentry-b")
        assertEquals(KpsDatabaseSync.Outcome.PULLED, KpsDatabaseSync.beforeLoad(context, vault).outcome)
        assertEquals(2, vault.state.baseRevision)
    }

    @Test
    fun beforeLoad_keepsLocalChanges_whenDirty() {
        server.upload("entry-a")
        KpsDatabaseSync.beforeLoad(context, vault)
        saveLocally(FakeDatabase(setOf("entry-a", "offline-entry")))
        server.upload("entry-a\nentry-b")
        assertEquals(KpsDatabaseSync.Outcome.UP_TO_DATE, KpsDatabaseSync.beforeLoad(context, vault).outcome)
        assertTrue(vault.file.readText().contains("offline-entry"))
    }

    @Test
    fun beforeLoad_opensLocalCopy_whenOffline() {
        server.upload("entry-a")
        KpsDatabaseSync.beforeLoad(context, vault)
        server.stop()
        assertEquals(KpsDatabaseSync.Outcome.OFFLINE, KpsDatabaseSync.beforeLoad(context, vault).outcome)
    }

    @Test
    fun beforeLoad_fails_whenOfflineWithoutLocalCopy() {
        server.stop()
        try {
            KpsDatabaseSync.beforeLoad(context, vault)
            fail("Expected SyncException")
        } catch (e: KpsDatabaseSync.SyncException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun fromUri_recognisesOnlyVaultFiles() {
        vault.file.writeText("x")
        assertNotNull(KpsVault.fromUri(context, vault.uri))
        assertNull(KpsVault.fromUri(context, Uri.fromFile(File(vault.dir, "state.json"))))
        assertNull(KpsVault.fromUri(context, Uri.fromFile(File(context.filesDir, "other.kdbx"))))
        assertNull(KpsVault.fromUri(context, Uri.parse("content://provider/Family.kdbx")))
    }

    companion object {
        private const val VAULT_ID = "3f1c2a9e-0b7d-4c55-9a7e-2f8b1d6c4e01"

        private fun sha(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}

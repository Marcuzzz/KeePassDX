package com.kunzisoft.keepass.database.sync.server

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Protocol check against a real keepass-server. Skipped unless KPS_TEST_URL, KPS_TEST_USER,
 * KPS_TEST_PASSWORD and KPS_TEST_KDBX (path to any .kdbx file) are set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KpsClientLiveTest {

    @Test
    fun protocol_matchesServer() {
        val url = System.getenv("KPS_TEST_URL")
        val user = System.getenv("KPS_TEST_USER")
        val password = System.getenv("KPS_TEST_PASSWORD")
        val kdbx = System.getenv("KPS_TEST_KDBX")?.let { File(it) }
        assumeTrue(url != null && user != null && password != null && kdbx?.exists() == true)
        val context: Context = ApplicationProvider.getApplicationContext()

        assertTrue(KpsClient.testConnection(url!!, user!!, password!!, "test").ok)
        assertEquals(
            KpsClient.ConnectionTest.Step.LOGIN,
            KpsClient.testConnection(url, user, "wrong-password", "test").step
        )

        val client = KpsClient(url)
        client.login(user, password, "KpsClientLiveTest")
        val vault = client.createVault("live-test")
        assertEquals(0, vault.revision)

        val first = client.upload(vault.id, kdbx!!, 0)
        assertEquals(1, first.revision)
        assertTrue(client.upload(vault.id, kdbx, 0).unchanged)

        val target = File(context.cacheDir, "download.kdbx")
        assertNull(client.download(vault.id, 1, target))
        val download = client.download(vault.id, null, target)!!
        assertEquals(1, download.revision)
        assertEquals(KpsClient.sha256(kdbx), download.sha256)

        val modified = File(context.cacheDir, "modified.kdbx").apply { writeBytes(kdbx.readBytes() + ByteArray(16)) }
        try {
            client.upload(vault.id, modified, 0)
            fail("Expected 412")
        } catch (e: KpsClient.ApiException) {
            assertEquals(412, e.status)
            assertEquals(1, e.currentRevision)
        }
        assertEquals(2, client.upload(vault.id, modified, 1, "note with spaces & ü").revision)
        assertFalse(client.uploadConflict(vault.id, modified, 1, "reason with spaces").isEmpty())
        assertEquals(1, client.getVault(vault.id).conflicts)
        assertTrue(client.listVaults().any { it.id == vault.id })
        client.logout()
    }
}

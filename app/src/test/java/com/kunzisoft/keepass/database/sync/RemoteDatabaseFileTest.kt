package com.kunzisoft.keepass.database.sync

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kunzisoft.keepass.database.exception.StorageProviderDatabaseException
import org.junit.Assert.assertArrayEquals
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
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteDatabaseFileTest {

    private lateinit var context: Context
    private lateinit var workDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDirectory = File(context.cacheDir, "remote_test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun createFile(name: String, content: String): File {
        return File(workDirectory, name).apply { writeText(content) }
    }

    private fun sha256(content: String): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
    }

    @Test
    fun Should_CopyContentAndHash_When_Download() {
        val remote = createFile("db.kdbx", "remote content")

        val snapshot = RemoteDatabaseFile.download(context, Uri.fromFile(remote))

        assertEquals("remote content", snapshot.file.readText())
        assertArrayEquals(sha256("remote content"), snapshot.sha256)
        snapshot.delete()
        assertFalse(snapshot.file.exists())
    }

    @Test
    fun Should_ThrowStorageProviderException_When_FileUnreadableAfterRetries() {
        val missing = Uri.fromFile(File(workDirectory, "missing.kdbx"))

        try {
            RemoteDatabaseFile.download(context, missing, retryDelaysMs = longArrayOf(1L, 1L))
            fail("Exception expected")
        } catch (e: StorageProviderDatabaseException) {
            // Expected
        }
    }

    @Test
    fun Should_KeepFile_When_WrittenContentMatches() {
        val written = createFile("written.tmp", "new content")
        val remote = createFile("db.kdbx", "new content")

        RemoteDatabaseFile.verifyWrite(context, Uri.fromFile(remote), written)

        assertEquals("new content", remote.readText())
    }

    @Test
    fun Should_RewriteFile_When_ReadBackMismatch() {
        val written = createFile("written.tmp", "new content")
        // Simulate a provider that did not truncate the previous longer content
        val remote = createFile("db.kdbx", "new contentOLD TRAILING BYTES")

        RemoteDatabaseFile.verifyWrite(context, Uri.fromFile(remote), written)

        assertEquals("new content", remote.readText())
    }

    @Test
    fun Should_KeepOnlyLastBackups_When_BackupCalledManyTimes() {
        val backupDirectory = File(workDirectory, "backup")
        for (i in 1..5) {
            RemoteDatabaseFile.backup(backupDirectory, "uri", createFile("v$i", "version $i"), now = i.toLong())
        }

        val backups = RemoteDatabaseFile.listBackups(backupDirectory, "uri")

        assertEquals(RemoteDatabaseFile.BACKUPS_TO_KEEP, backups.size)
        assertEquals("version 5", backups.first().readText())
        assertEquals("version 3", backups.last().readText())
    }

    @Test
    fun Should_NotDuplicateBackup_When_ContentUnchanged() {
        val backupDirectory = File(workDirectory, "backup")
        val file = createFile("v1", "same")

        RemoteDatabaseFile.backup(backupDirectory, "uri", file, now = 1L)
        RemoteDatabaseFile.backup(backupDirectory, "uri", file, now = 2L)

        assertEquals(1, RemoteDatabaseFile.listBackups(backupDirectory, "uri").size)
    }

    @Test
    fun Should_SeparateBackups_When_DifferentUris() {
        val backupDirectory = File(workDirectory, "backup")

        RemoteDatabaseFile.backup(backupDirectory, "uriA", createFile("a", "a"), now = 1L)
        RemoteDatabaseFile.backup(backupDirectory, "uriB", createFile("b", "b"), now = 1L)

        assertEquals("a", RemoteDatabaseFile.listBackups(backupDirectory, "uriA").single().readText())
        assertEquals("b", RemoteDatabaseFile.listBackups(backupDirectory, "uriB").single().readText())
    }

    @Test
    fun Should_RecogniseBackup_When_UriInBackupDirectory() {
        val uri = Uri.parse("content://com.google.android.apps.docs.storage/document/acc=1;doc=2")
        RemoteDatabaseFile.backup(context, uri, RemoteDatabaseFile.Snapshot(createFile("v", "v"), sha256("v")))

        val backup = RemoteDatabaseFile.lastBackup(context, uri)

        assertNotNull(backup)
        assertTrue(RemoteDatabaseFile.isBackup(context, Uri.fromFile(backup)))
        assertFalse(RemoteDatabaseFile.isBackup(context, uri))
        assertNull(RemoteDatabaseFile.lastBackup(context, Uri.parse("content://other/doc")))
    }
}

package com.kunzisoft.keepass.database.sync

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.action.LoadDatabaseRunnable
import com.kunzisoft.keepass.database.action.SaveDatabaseRunnable
import com.kunzisoft.keepass.database.crypto.kdf.KdfFactory
import com.kunzisoft.keepass.database.exception.ExternalChangeDatabaseException
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.tasks.ActionRunnable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Simulate a laptop and a phone editing the same file stored by a provider
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeCloudSyncTest {

    private lateinit var context: Context
    private lateinit var databaseUri: Uri
    private val noHardwareKey: (HardwareKey, ByteArray?) -> ByteArray = { _, _ -> ByteArray(0) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val directory = File(context.cacheDir, "sync_test").apply {
            deleteRecursively()
            mkdirs()
        }
        databaseUri = Uri.fromFile(File(directory, "shared.kdbx"))
    }

    private fun credential(password: String = "password") =
        MainCredential(password = password.toCharArray())

    private fun createDatabase(): ContextualDatabase {
        val database = ContextualDatabase()
        database.fileUri = databaseUri
        database.createData("shared", "Root", null)
        database.kdfEngine = KdfFactory.aesKdf
        save(database, credential())
        database.loaded = true
        return database
    }

    private fun load(password: String = "password"): ContextualDatabase {
        val database = ContextualDatabase()
        val runnable = LoadDatabaseRunnable(
            context, database, databaseUri, credential(password), noHardwareKey,
            mReadonly = false, mAllowUserVerification = false, mFixDuplicateUUID = false,
            progressTaskUpdater = null
        )
        runnable.run()
        assertTrue("Load failed: ${runnable.result.message}", runnable.result.isSuccess)
        return database
    }

    private fun save(
        database: ContextualDatabase,
        mainCredential: MainCredential? = null
    ): ActionRunnable.Result {
        val runnable = SaveDatabaseRunnable(
            context, database, save = true, mainCredential, noHardwareKey
        )
        runnable.run()
        return runnable.result
    }

    private fun addEntry(database: ContextualDatabase, title: String) {
        val entry = database.createEntry()!!
        entry.title = title
        database.addEntryTo(entry, database.rootGroup!!)
    }

    private fun titles(database: ContextualDatabase): Set<String> {
        return database.rootGroup!!.getChildEntries().map { it.title }.toSet()
    }

    @Test
    fun Should_KeepBothChanges_When_FileModifiedByAnotherDeviceBeforeSave() {
        val phone = createDatabase()
        val laptop = load()

        addEntry(laptop, "From laptop")
        assertTrue(save(laptop).isSuccess)

        // The phone still has the previous content in memory
        addEntry(phone, "From phone")
        val result = save(phone)

        assertTrue("Save failed: ${result.message}", result.isSuccess)
        assertEquals(true, result.data?.getBoolean(SaveDatabaseRunnable.EXTERNAL_CHANGES_MERGED_KEY))
        assertEquals(setOf("From laptop", "From phone"), titles(load()))
        assertEquals(setOf("From laptop", "From phone"), titles(phone))
    }

    @Test
    fun Should_NotMerge_When_FileUnchangedSinceLastSave() {
        val phone = createDatabase()

        addEntry(phone, "First")
        assertTrue(save(phone).isSuccess)
        addEntry(phone, "Second")
        val result = save(phone)

        assertTrue(result.isSuccess)
        assertEquals(false, result.data?.getBoolean(SaveDatabaseRunnable.EXTERNAL_CHANGES_MERGED_KEY) ?: false)
        assertEquals(setOf("First", "Second"), titles(load()))
    }

    @Test
    fun Should_RefuseToOverwrite_When_RemoteChangesCannotBeMerged() {
        val phone = createDatabase()
        val laptop = load()

        // The laptop changes the master password, the phone can no longer read the file
        assertTrue(save(laptop, credential("new password")).isSuccess)
        val remoteContent = File(databaseUri.path!!).readBytes()

        addEntry(phone, "From phone")
        val result = save(phone)

        assertTrue(!result.isSuccess && result.exception is ExternalChangeDatabaseException)
        assertArrayEquals(remoteContent, File(databaseUri.path!!).readBytes())
    }

    @Test
    fun Should_KeepBackupOfRemote_When_Saving() {
        val phone = createDatabase()
        val laptop = load()
        addEntry(laptop, "From laptop")
        assertTrue(save(laptop).isSuccess)
        val laptopContent = File(databaseUri.path!!).readBytes()

        addEntry(phone, "From phone")
        assertTrue(save(phone).isSuccess)

        val backup = RemoteDatabaseFile.lastBackup(context, databaseUri)!!
        assertArrayEquals(laptopContent, backup.readBytes())
    }
}

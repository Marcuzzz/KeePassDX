package com.kunzisoft.keepass.database.sync.server

import android.content.Context
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.MasterCredential
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import com.kunzisoft.keepass.utils.AppUtil.getLimits
import java.io.File
import java.io.FileOutputStream

/** [KpsDatabaseSync.OpenDatabase] backed by the database opened in the app. */
class KpsOpenDatabase(
    private val context: Context,
    private val database: ContextualDatabase,
    private val vault: KpsVault,
    /** Not cached: a downloaded file has its own challenge seed. */
    private val mergeChallengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
    private val writeChallengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
    private val masterCredential: MasterCredential? = null,
    private val progressTaskUpdater: ProgressTaskUpdater? = null
) : KpsDatabaseSync.OpenDatabase {

    override fun merge(remote: File) {
        remote.inputStream().use { input ->
            database.mergeData(
                databaseToMergeStream = input,
                databaseToMergeMasterCredential = null,
                databaseToMergeChallengeResponseRetriever = mergeChallengeResponseRetriever,
                limits = context.getLimits(),
                progressTaskUpdater = progressTaskUpdater
            )
        }
        database.wasReloaded = true
    }

    override fun write() {
        writeDatabase(context, database, vault, masterCredential, writeChallengeResponseRetriever)
        database.indicateUpToDateData()
    }

    companion object {
        /** Writes [database] to the vault's local file atomically (partial file, then rename). */
        fun writeDatabase(
            context: Context,
            database: ContextualDatabase,
            vault: KpsVault,
            masterCredential: MasterCredential?,
            challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
            onWritten: ((File) -> Unit)? = null
        ) {
            database.saveData(
                cacheFile = File.createTempFile("db_", ".tmp", context.cacheDir),
                databaseOutputStream = { FileOutputStream(vault.partialFile) },
                masterCredential = masterCredential,
                challengeResponseRetriever = challengeResponseRetriever,
                limits = context.getLimits(),
                onWritten = { cacheFile ->
                    vault.commitPartial()
                    onWritten?.invoke(cacheFile)
                }
            )
        }
    }
}

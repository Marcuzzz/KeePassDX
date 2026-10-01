/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.database.action

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.element.MasterCredential
import com.kunzisoft.keepass.database.exception.DatabaseException
import com.kunzisoft.keepass.database.exception.ExternalChangeDatabaseException
import com.kunzisoft.keepass.database.exception.StorageProviderDatabaseException
import com.kunzisoft.keepass.database.sync.RemoteDatabaseFile
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import com.kunzisoft.keepass.utils.AppUtil.getLimits
import com.kunzisoft.keepass.utils.clear
import com.kunzisoft.keepass.utils.getUriOutputStream
import com.kunzisoft.keepass.utils.withContentScheme
import java.io.File
import java.io.IOException

open class SaveDatabaseRunnable(
    protected var context: Context,
    protected var database: ContextualDatabase,
    private var save: Boolean,
    private var mainCredential: MainCredential?, // If null, uses composite Key
    protected var challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
    private var databaseCopyUri: Uri? = null,
    private var dataModified: Boolean = !save,
    protected var progressTaskUpdater: ProgressTaskUpdater? = null
) : ActionRunnable() {

    private var mMasterCredential: MasterCredential? = null
    var afterSaveDatabase: ((Result) -> Unit)? = null

    private var cachedHardwareResponse: ByteArray? = null
    protected val cachingRetriever: (HardwareKey, ByteArray?) -> ByteArray = { hardwareKey, seed ->
        cachedHardwareResponse ?: challengeResponseRetriever(hardwareKey, seed).also {
            cachedHardwareResponse = it
        }
    }

    override fun onStartRun() {}

    override fun onActionRun() {
        database.checkVersion()
        // Save database in all cases if it's a copy
        if ((databaseCopyUri != null || save) && result.isSuccess) {
            try {
                val contentResolver = context.contentResolver
                val targetUri = databaseCopyUri ?: database.fileUri
                val safeSync = targetUri != null
                        && databaseCopyUri == null
                        && PreferencesUtil.isSafeCloudSyncEnabled(context)
                if (safeSync) {
                    mergeExternalChanges(targetUri!!)
                }
                mMasterCredential = mainCredential?.toMasterCredential(contentResolver)
                var writtenHash: ByteArray? = null
                // Build temp database file to avoid file corruption if error
                database.saveData(
                    cacheFile = File.createTempFile("db_", ".tmp", context.cacheDir),
                    databaseOutputStream = {
                        try {
                            contentResolver.getUriOutputStream(targetUri)
                        } catch (e: IOException) {
                            throw StorageProviderDatabaseException(e)
                        }
                    },
                    masterCredential = mMasterCredential,
                    challengeResponseRetriever = cachingRetriever,
                    limits = context.getLimits(),
                    onWritten = { cacheFile ->
                        if (safeSync && targetUri!!.withContentScheme()) {
                            RemoteDatabaseFile.verifyWrite(context, targetUri, cacheFile)
                        }
                        writtenHash = RemoteDatabaseFile.sha256(cacheFile)
                    }
                )
                // Indicate data was saved only if it's not a new location
                if (databaseCopyUri == null) {
                    database.syncedContentHash = writtenHash
                    database.indicateUpToDateData()
                }
            } catch (e: DatabaseException) {
                setError(e)
            }
        } else if (dataModified) {
            database.indicateNotSavedData()
        }
    }

    /**
     * Merge the modifications made by another device since the last load or save,
     * to not overwrite them. Keep a backup of the remote content before overwriting it.
     */
    @Throws(DatabaseException::class)
    private fun mergeExternalChanges(fileUri: Uri) {
        val syncedHash = database.syncedContentHash
        val remote = try {
            RemoteDatabaseFile.download(context, fileUri)
        } catch (e: StorageProviderDatabaseException) {
            // Nothing to merge if the file was deleted, it will be recreated
            if (!RemoteDatabaseFile.exists(context, fileUri))
                return
            throw e
        }
        try {
            RemoteDatabaseFile.backup(context, fileUri, remote)
            if (syncedHash == null
                || syncedHash.contentEquals(remote.sha256)
                || !database.isMergeDataAllowed())
                return
            Log.i(TAG, "Database file modified externally, merge before saving")
            try {
                database.mergeData(
                    databaseToMergeStream = remote.inputStream(),
                    databaseToMergeMasterCredential = null,
                    // Not the cached retriever, the remote file has its own challenge seed
                    databaseToMergeChallengeResponseRetriever = challengeResponseRetriever,
                    limits = context.getLimits(),
                    progressTaskUpdater = progressTaskUpdater
                )
            } catch (e: DatabaseException) {
                throw ExternalChangeDatabaseException(e)
            }
            database.syncedContentHash = remote.sha256
            database.wasReloaded = true
            result.data = (result.data ?: Bundle()).apply {
                putBoolean(EXTERNAL_CHANGES_MERGED_KEY, true)
            }
        } finally {
            remote.delete()
        }
    }

    override fun onFinishRun() {
        // Need to call super.onFinishRun() in child class
        mMasterCredential?.clear()
        cachedHardwareResponse?.clear()
        afterSaveDatabase?.invoke(result)
    }

    companion object {
        private val TAG = SaveDatabaseRunnable::class.java.name
        const val EXTERNAL_CHANGES_MERGED_KEY = "EXTERNAL_CHANGES_MERGED_KEY"
    }
}

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
import android.util.Log
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.element.MasterCredential
import com.kunzisoft.keepass.database.exception.CorruptedDatabaseException
import com.kunzisoft.keepass.database.exception.DatabaseException
import com.kunzisoft.keepass.database.exception.HeaderHmacMismatchException
import com.kunzisoft.keepass.database.exception.SignatureDatabaseException
import com.kunzisoft.keepass.database.exception.XMLMalformedDatabaseException
import com.kunzisoft.keepass.database.sync.RemoteDatabaseFile
import com.kunzisoft.keepass.database.sync.server.KpsDatabaseSync
import com.kunzisoft.keepass.database.sync.server.KpsOpenDatabase
import com.kunzisoft.keepass.database.sync.server.KpsVault
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import com.kunzisoft.keepass.utils.AppUtil.getLimits
import com.kunzisoft.keepass.utils.getBinaryDir
import com.kunzisoft.keepass.utils.withContentScheme

class LoadDatabaseRunnable(
    private val context: Context,
    private val mDatabase: ContextualDatabase,
    private val mDatabaseUri: Uri,
    private val mMainCredential: MainCredential,
    private val mChallengeResponseRetriever: (hardwareKey: HardwareKey, seed: ByteArray?) -> ByteArray,
    private val mReadonly: Boolean,
    private val mAllowUserVerification: Boolean,
    private val mFixDuplicateUUID: Boolean,
    private val progressTaskUpdater: ProgressTaskUpdater?
) : ActionRunnable() {

    private var masterCredential: MasterCredential? = null
    private val binaryDir = context.getBinaryDir()
    var afterLoadDatabase : ((Result) -> Unit)? = null

    override fun onActionRun() {
        try {
            val contentResolver = context.contentResolver
            masterCredential = mMainCredential.toMasterCredential(contentResolver)
            // Database from a keepass-server: refresh the local copy first (falls back to it offline)
            val serverVault = KpsVault.fromUri(context, mDatabaseUri)
            val pull = serverVault?.let { KpsDatabaseSync.beforeLoad(context, it) }
            var attempt = 0
            while (true) {
                // Copy the remote file first, the provider may fail or serve a partial file
                val snapshot = RemoteDatabaseFile.download(context, mDatabaseUri)
                try {
                    loadSnapshot(snapshot)
                    mDatabase.syncedContentHash = snapshot.sha256
                    break
                } catch (e: DatabaseException) {
                    if (!isIncompleteFileError(e)
                        || !mDatabaseUri.withContentScheme()
                        || attempt >= RemoteDatabaseFile.RETRY_DELAYS_MS.size)
                        throw e
                    Log.w(TAG, "Database file seems incomplete, retry", e)
                    Thread.sleep(RemoteDatabaseFile.RETRY_DELAYS_MS[attempt])
                    attempt++
                } finally {
                    snapshot.delete()
                }
            }
            if (serverVault != null && pull != null) {
                syncServerVault(serverVault, pull)
            }
        } catch (e: Exception) {
            setError(e)
        }

        if (!result.isSuccess) {
            mDatabase.clearAndClose(binaryDir)
        }
    }

    /** Uploads changes made offline (merging newer server changes into the loaded database). */
    private fun syncServerVault(vault: KpsVault, pull: KpsDatabaseSync.Result) {
        if (!vault.state.dirty || mReadonly || pull.outcome != KpsDatabaseSync.Outcome.UP_TO_DATE) {
            KpsDatabaseSync.notify(context, pull)
            return
        }
        try {
            val push = KpsDatabaseSync.push(context, vault, KpsOpenDatabase(
                context = context,
                database = mDatabase,
                vault = vault,
                mergeChallengeResponseRetriever = mChallengeResponseRetriever,
                writeChallengeResponseRetriever = mChallengeResponseRetriever,
                progressTaskUpdater = progressTaskUpdater
            ))
            KpsDatabaseSync.notify(context, push)
        } catch (e: Exception) {
            // The database is loaded from the local copy; the upload is retried on the next save
            Log.w(TAG, "Unable to upload local changes", e)
        }
    }

    private fun loadSnapshot(snapshot: RemoteDatabaseFile.Snapshot) {
        mDatabase.apply {
            // Clear binaries before database loading
            clearAndClose(binaryDir)
            // Save database URI
            fileUri = mDatabaseUri
            loadData(
                databaseStream = snapshot.inputStream(),
                masterCredential = masterCredential!!,
                challengeResponseRetriever = mChallengeResponseRetriever,
                readOnly = mReadonly,
                allowUserVerification = mAllowUserVerification,
                cacheDirectory = binaryDir,
                limits = context.getLimits(),
                fixDuplicateUUID = mFixDuplicateUUID,
                progressTaskUpdater = progressTaskUpdater
            )
            indicateUpToDateData()
        }
    }

    /**
     * Errors that can be caused by a file still being written or downloaded
     */
    private fun isIncompleteFileError(e: DatabaseException): Boolean {
        return e is HeaderHmacMismatchException
                || e is CorruptedDatabaseException
                || e is SignatureDatabaseException
                || e is XMLMalformedDatabaseException
    }

    override fun onFinishRun() {
        masterCredential?.clear()
        afterLoadDatabase?.invoke(result)
    }

    companion object {
        private val TAG = LoadDatabaseRunnable::class.java.name
    }
}

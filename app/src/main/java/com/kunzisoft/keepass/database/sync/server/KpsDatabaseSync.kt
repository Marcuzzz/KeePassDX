package com.kunzisoft.keepass.database.sync.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.database.exception.DatabaseException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sync of a [KpsVault] local copy with keepass-server, called from the load and save runnables.
 *
 * - Before loading: download the newer server revision when there are no local changes.
 *   Unreachable server → open the local copy (offline).
 * - After saving: upload with If-Match = base revision. On 412 another device uploaded first:
 *   download it, merge it into the open database (KeePass merge, per entry), write and retry.
 *   Unreachable server → the change stays local (dirty) and is uploaded on the next load or save.
 * - Merge impossible (other master key): upload ours as a conflict copy, keep it locally too,
 *   and continue with the server version after the user unlocks it again.
 */
object KpsDatabaseSync {

    private const val TAG = "KpsDatabaseSync"
    private const val MAX_ATTEMPTS = 5

    enum class Outcome {
        UP_TO_DATE, PULLED, PUSHED, MERGED, OFFLINE, WORK_OFFLINE, READ_ONLY, NOT_SIGNED_IN, NOT_FOUND, KEY_CHANGED
    }

    data class Result(val outcome: Outcome, val revision: Int, val detail: String? = null)

    /** Lets the sync merge into and write the open database without knowing the runnable. */
    interface OpenDatabase {
        /** Merges the database in [remote] into the open one; DatabaseException if impossible. */
        @Throws(DatabaseException::class)
        fun merge(remote: File)

        /** Writes the open database to the vault's local file. */
        @Throws(DatabaseException::class)
        fun write()
    }

    class SyncException(message: String) : IOException(message)

    /**
     * Refreshes the local copy before it is opened. Only throws when there is no local copy
     * to fall back on.
     */
    @Throws(SyncException::class)
    fun beforeLoad(context: Context, vault: KpsVault): Result {
        val state = vault.state
        if (state.needsReopen) {
            state.needsReopen = false
            vault.save()
        }
        if (state.workOffline) {
            if (!vault.hasLocalCopy()) throw SyncException(context.getString(R.string.kps_error_no_local_copy))
            return Result(Outcome.WORK_OFFLINE, state.baseRevision)
        }
        val client = KpsAccount.clientFor(context, vault)
        if (client == null) {
            if (!vault.hasLocalCopy()) throw SyncException(context.getString(R.string.kps_error_not_signed_in))
            return Result(Outcome.NOT_SIGNED_IN, state.baseRevision)
        }
        // Local changes not uploaded yet: open them, the upload (and merge) runs after loading.
        if (state.dirty && vault.hasLocalCopy()) return Result(Outcome.UP_TO_DATE, state.baseRevision)

        val temp = vault.tempFile("download_")
        try {
            val known = if (vault.hasLocalCopy()) state.baseRevision else null
            val download = client.download(state.vaultId, known, temp)
                ?: return Result(Outcome.UP_TO_DATE, state.baseRevision)
            vault.adopt(temp, download.revision, download.sha256)
            return Result(Outcome.PULLED, download.revision)
        } catch (e: KpsClient.OfflineException) {
            if (!vault.hasLocalCopy()) throw SyncException(context.getString(R.string.kps_error_offline_no_copy, e.message))
            return Result(Outcome.OFFLINE, state.baseRevision, e.message)
        } catch (e: KpsClient.ApiException) {
            val fallback = when (e.status) {
                401 -> Outcome.NOT_SIGNED_IN
                404 -> if (e.code == "empty_vault") Outcome.UP_TO_DATE else Outcome.NOT_FOUND
                else -> null
            }
            if (fallback == null || !vault.hasLocalCopy()) {
                throw SyncException(
                    if (e.code == "empty_vault") context.getString(R.string.kps_error_empty_vault)
                    else context.getString(R.string.kps_error_server, e.message)
                )
            }
            return Result(fallback, state.baseRevision, e.message)
        } finally {
            temp.delete()
        }
    }

    /**
     * Uploads the local file, merging with newer server revisions as needed. Call after the open
     * database was written to [KpsVault.file] (or after loading a copy with unsynced changes).
     */
    fun push(context: Context, vault: KpsVault, database: OpenDatabase): Result {
        val state = vault.state
        if (!state.dirty) {
            state.dirty = true
            vault.save()
        }
        if (state.workOffline) return Result(Outcome.WORK_OFFLINE, state.baseRevision)
        val client = KpsAccount.clientFor(context, vault)
            ?: return Result(Outcome.NOT_SIGNED_IN, state.baseRevision)

        var merged = false
        repeat(MAX_ATTEMPTS) {
            try {
                val upload = client.upload(state.vaultId, vault.file, state.baseRevision, KpsAccount.deviceName)
                state.baseRevision = upload.revision
                state.baseSha256 = upload.sha256
                state.dirty = false
                vault.save()
                return Result(if (merged) Outcome.MERGED else Outcome.PUSHED, upload.revision)
            } catch (e: KpsClient.OfflineException) {
                return Result(Outcome.OFFLINE, state.baseRevision, e.message)
            } catch (e: KpsClient.ApiException) {
                when (e.status) {
                    412 -> Log.i(TAG, "Server has revision ${e.currentRevision}, merging")
                    403 -> return Result(Outcome.READ_ONLY, state.baseRevision, e.message)
                    401 -> return Result(Outcome.NOT_SIGNED_IN, state.baseRevision, e.message)
                    404 -> return Result(Outcome.NOT_FOUND, state.baseRevision, e.message)
                    else -> throw SyncException(context.getString(R.string.kps_error_server, e.message))
                }
            }

            // Another device uploaded first: merge its version into ours.
            val remote = vault.tempFile("remote_")
            try {
                val download = try {
                    client.download(state.vaultId, null, remote)
                        ?: throw SyncException("Server returned no content")
                } catch (e: KpsClient.OfflineException) {
                    return Result(Outcome.OFFLINE, state.baseRevision, e.message)
                }
                try {
                    database.merge(remote)
                } catch (e: DatabaseException) {
                    Log.w(TAG, "Unable to merge the server version", e)
                    return keepAsConflict(context, client, vault, remote, download)
                }
                // Write first, then move the base: a failed write must not skip the server changes.
                database.write()
                state.baseRevision = download.revision
                state.baseSha256 = download.sha256
                vault.save()
                merged = true
            } finally {
                remote.delete()
            }
        }
        throw SyncException(context.getString(R.string.kps_error_too_many_conflicts))
    }

    private fun keepAsConflict(
        context: Context,
        client: KpsClient,
        vault: KpsVault,
        remote: File,
        download: KpsClient.Download
    ): Result {
        val state = vault.state
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        vault.file.copyTo(File(vault.dir, "conflict-$stamp.kdbx"), overwrite = true)
        try {
            client.uploadConflict(state.vaultId, vault.file, state.baseRevision,
                "Could not merge on ${KpsAccount.deviceName} (master key changed?)")
        } catch (e: IOException) {
            Log.w(TAG, "Unable to upload the conflict copy, it is kept on the device", e)
        }
        vault.adopt(remote, download.revision, download.sha256)
        state.needsReopen = true
        vault.save()
        return Result(Outcome.KEY_CHANGED, download.revision)
    }

    fun message(context: Context, result: Result): String? = when (result.outcome) {
        Outcome.PUSHED -> context.getString(R.string.kps_sync_pushed, result.revision)
        Outcome.MERGED -> context.getString(R.string.kps_sync_merged, result.revision)
        Outcome.PULLED -> context.getString(R.string.kps_sync_pulled, result.revision)
        Outcome.OFFLINE -> context.getString(R.string.kps_sync_offline)
        Outcome.WORK_OFFLINE -> context.getString(R.string.kps_sync_work_offline)
        Outcome.READ_ONLY -> context.getString(R.string.kps_sync_read_only)
        Outcome.NOT_SIGNED_IN -> context.getString(R.string.kps_sync_not_signed_in)
        Outcome.NOT_FOUND -> context.getString(R.string.kps_sync_not_found)
        Outcome.KEY_CHANGED -> context.getString(R.string.kps_sync_key_changed)
        Outcome.UP_TO_DATE -> null
    }

    /** Short status toast; the runnables have no access to the current activity. */
    fun notify(context: Context, result: Result) {
        val message = message(context, result) ?: return
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
}

package com.kunzisoft.keepass.database.sync

import android.content.Context
import android.net.Uri
import com.kunzisoft.keepass.app.database.CipherDatabaseAction
import com.kunzisoft.keepass.app.database.FileDatabaseHistoryAction
import com.kunzisoft.keepass.model.DatabaseFile
import com.kunzisoft.keepass.settings.PreferencesUtil

/**
 * A storage provider can give a new identity to a file replaced by another device
 * (e.g. Google Drive for desktop when KeePass saves with file transactions), the old URI then
 * points to the previous version. Reconnecting moves everything linked to the old URI
 * (key file, hardware key, device unlock, default database, backups) to the selected one.
 */
object DatabaseFileReconnect {

    fun reconnect(
        context: Context,
        oldUri: Uri,
        newUri: Uri,
        onReconnected: (DatabaseFile?) -> Unit
    ) {
        val applicationContext = context.applicationContext
        if (PreferencesUtil.getDefaultDatabasePath(applicationContext) == oldUri.toString()) {
            PreferencesUtil.saveDefaultDatabasePath(applicationContext, newUri)
        }
        RemoteDatabaseFile.moveBackups(applicationContext, oldUri, newUri)
        moveDeviceUnlock(applicationContext, oldUri, newUri) {
            moveHistory(applicationContext, oldUri, newUri, onReconnected)
        }
    }

    private fun moveDeviceUnlock(
        context: Context,
        oldUri: Uri,
        newUri: Uri,
        onMoved: () -> Unit
    ) {
        // The device unlock key is global, the URI is only the lookup key of the cipher
        val cipherDatabaseAction = CipherDatabaseAction.getInstance(context)
        cipherDatabaseAction.getCipherDatabase(oldUri) { cipherDatabase ->
            if (cipherDatabase == null) {
                onMoved()
            } else {
                cipherDatabase.databaseUri = newUri
                cipherDatabaseAction.addOrUpdateCipherDatabase(cipherDatabase) {
                    cipherDatabaseAction.deleteByDatabaseUri(oldUri) {
                        onMoved()
                    }
                }
            }
        }
    }

    private fun moveHistory(
        context: Context,
        oldUri: Uri,
        newUri: Uri,
        onMoved: (DatabaseFile?) -> Unit
    ) {
        val historyAction = FileDatabaseHistoryAction.getInstance(context)
        historyAction.getDatabaseFile(oldUri) { oldFile ->
            historyAction.addOrUpdateDatabaseFile(
                DatabaseFile(
                    databaseUri = newUri,
                    keyFileUri = oldFile?.keyFileUri,
                    hardwareKey = oldFile?.hardwareKey,
                    readOnly = oldFile?.readOnly,
                    userVerification = oldFile?.userVerification
                )
            ) { newFile ->
                if (oldFile != null) {
                    historyAction.deleteDatabaseFile(oldFile) {
                        onMoved(newFile)
                    }
                } else {
                    onMoved(newFile)
                }
            }
        }
    }
}

package com.kunzisoft.keepass.database.sync.server

import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kunzisoft.keepass.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Start screen dialogs for keepass-server: sign in (with "Test connection"), list the vaults,
 * open one (downloading it the first time) or create a new database on the server.
 */
class KpsServerDialogs(
    private val activity: AppCompatActivity,
    /** Opens the unlock screen for a local vault copy. */
    private val openDatabase: (Uri) -> Unit,
    /** Starts the "new database" flow (master key dialog) writing to a local vault copy. */
    private val createDatabase: (Uri) -> Unit
) {

    private val context get() = activity.applicationContext

    /** "KeePass Server" button: vault list, signing in first if needed. */
    fun show() {
        if (KpsAccount.load(context) != null) showVaults() else showConnect { showVaults() }
    }

    /** "Create database → On KeePass Server". */
    fun createOnServer() {
        if (KpsAccount.load(context) != null) askNewDatabaseName() else showConnect { askNewDatabaseName() }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // --- sign in -------------------------------------------------------------------------------

    private fun showConnect(onConnected: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_keepass_server_connect, null)
        val urlView = view.findViewById<EditText>(R.id.kps_server_url)
        val usernameView = view.findViewById<EditText>(R.id.kps_username)
        val passwordView = view.findViewById<EditText>(R.id.kps_password)
        val testButton = view.findViewById<Button>(R.id.kps_test_button)
        val progress = view.findViewById<ProgressBar>(R.id.kps_progress)
        val status = view.findViewById<TextView>(R.id.kps_status)
        val (lastUrl, lastUsername) = KpsAccount.lastUsed(context)
        urlView.setText(lastUrl)
        usernameView.setText(lastUsername)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.kps_title)
            .setView(view)
            .setPositiveButton(R.string.kps_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun showStatus(message: String) {
            status.text = message
            status.visibility = View.VISIBLE
        }

        fun run(connect: Boolean) {
            val url = urlView.text.toString().trim()
            val username = usernameView.text.toString().trim()
            val password = passwordView.text.toString()
            if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
                showStatus(activity.getString(R.string.kps_fields_required))
                return
            }
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            testButton.isEnabled = false
            positive.isEnabled = false
            progress.visibility = View.VISIBLE
            showStatus(activity.getString(R.string.kps_testing))
            activity.lifecycleScope.launch {
                val test = withContext(Dispatchers.IO) {
                    KpsClient.testConnection(url, username, password, KpsAccount.deviceName)
                }
                var message = when (test.step) {
                    KpsClient.ConnectionTest.Step.DONE -> activity.getString(R.string.kps_test_ok, test.message)
                    KpsClient.ConnectionTest.Step.REACH -> activity.getString(R.string.kps_test_failed_reach, test.message)
                    KpsClient.ConnectionTest.Step.SERVER -> activity.getString(R.string.kps_test_failed_server)
                    KpsClient.ConnectionTest.Step.LOGIN -> activity.getString(R.string.kps_test_failed_login, test.message)
                }
                if (KpsClient.isInsecure(url)) message += "\n\n" + activity.getString(R.string.kps_insecure_warning)
                if (test.ok && connect) {
                    val account = withContext(Dispatchers.IO) {
                        try {
                            val client = KpsClient(url)
                            KpsAccount(client.baseUrl, username, client.login(username, password, KpsAccount.deviceName))
                        } catch (e: Exception) {
                            message = e.message ?: message
                            null
                        }
                    }
                    if (account != null) {
                        KpsAccount.save(context, account)
                        dialog.dismiss()
                        onConnected()
                        return@launch
                    }
                }
                progress.visibility = View.GONE
                testButton.isEnabled = true
                positive.isEnabled = true
                showStatus(message)
            }
        }

        dialog.setOnShowListener {
            testButton.setOnClickListener { run(connect = false) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { run(connect = true) }
        }
        dialog.show()
    }

    // --- vault list ----------------------------------------------------------------------------

    private class Row(val label: String, val vaultId: String, val name: String, val revision: Int, val canWrite: Boolean)

    private fun showVaults() {
        val account = KpsAccount.load(context) ?: return showConnect { showVaults() }
        activity.lifecycleScope.launch {
            var offline = false
            var error: String? = null
            val remote = withContext(Dispatchers.IO) {
                try {
                    account.client().listVaults()
                } catch (e: KpsClient.OfflineException) {
                    offline = true
                    null
                } catch (e: Exception) {
                    if (e !is KpsClient.ApiException || e.status != 401) error = e.message
                    null
                }
            }
            error?.let {
                toast(activity.getString(R.string.kps_error_server, it))
                return@launch
            }
            if (remote == null && !offline) {
                // Token revoked or expired
                KpsAccount.signOut(context)
                showConnect { showVaults() }
                return@launch
            }
            val local = KpsVault.all(context).filter {
                KpsClient.normalizeUrl(it.state.serverUrl) == KpsClient.normalizeUrl(account.serverUrl)
            }.associateBy { it.state.vaultId }
            val rows = if (remote != null) {
                remote.map { info -> row(info.id, info.name, info.role, info.revision, info.conflicts, info.canWrite, local[info.id]) }
            } else {
                local.values.map { v -> row(v.state.vaultId, v.state.vaultName, "", v.state.baseRevision, 0, true, v) }
            }
            showVaultList(account, rows, offline)
        }
    }

    private fun row(id: String, name: String, role: String, revision: Int, conflicts: Int, canWrite: Boolean, local: KpsVault?): Row {
        val parts = mutableListOf(name)
        if (role.isNotEmpty()) parts += role
        parts += "rev $revision"
        if (conflicts > 0) parts += activity.getString(R.string.kps_vault_conflicts, conflicts)
        if (local?.state?.dirty == true) parts += activity.getString(R.string.kps_vault_local_changes)
        if (local?.state?.workOffline == true) parts += activity.getString(R.string.kps_vault_offline_mode)
        return Row(parts.joinToString(" · "), id, name, revision, canWrite)
    }

    private fun showVaultList(account: KpsAccount, rows: List<Row>, offline: Boolean) {
        val host = try { URL(account.serverUrl).host } catch (e: Exception) { account.serverUrl }
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.kps_vaults_title, account.username, host))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.kps_sign_out) { _, _ ->
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    try { account.client().logout() } catch (e: Exception) { /* signed out locally anyway */ }
                }
                KpsAccount.signOut(context)
            }
        if (!offline) builder.setPositiveButton(R.string.kps_new_database) { _, _ -> askNewDatabaseName() }
        when {
            rows.isNotEmpty() -> builder.setItems(rows.map { it.label }.toTypedArray()) { _, which -> openVault(account, rows[which]) }
            offline -> builder.setMessage(R.string.kps_vaults_offline)
            else -> builder.setMessage(R.string.kps_vaults_empty)
        }
        builder.show()
        if (offline && rows.isNotEmpty()) toast(activity.getString(R.string.kps_vaults_offline))
    }

    private fun openVault(account: KpsAccount, row: Row) {
        val vault = KpsVault.getOrCreate(context, account.serverUrl, row.vaultId, row.name)
        if (vault.hasLocalCopy()) {
            // Refreshed from the server while loading, or opened offline
            openDatabase(vault.uri)
            return
        }
        if (row.revision == 0 && row.canWrite) {
            // Empty vault: this device creates the first version
            createDatabase(vault.uri)
            return
        }
        toast(activity.getString(R.string.kps_downloading))
        activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    KpsDatabaseSync.beforeLoad(context, vault)
                    null
                } catch (e: KpsDatabaseSync.SyncException) {
                    e.message
                }
            }
            if (error == null) openDatabase(vault.uri) else toast(error)
        }
    }

    // --- new database --------------------------------------------------------------------------

    private fun askNewDatabaseName() {
        val account = KpsAccount.load(context) ?: return showConnect { askNewDatabaseName() }
        val input = EditText(activity).apply {
            setText(R.string.kps_new_database_default_name)
            selectAll()
            setSingleLine()
        }
        val padding = activity.resources.getDimensionPixelSize(R.dimen.default_margin)
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.kps_new_database_name)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.kps_new_database) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { activity.getString(R.string.kps_new_database_default_name) }
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            Result.success(account.client().createVault(name))
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                    result.onSuccess { info ->
                        createDatabase(KpsVault.getOrCreate(context, account.serverUrl, info.id, info.name).uri)
                    }.onFailure { e ->
                        toast(activity.getString(R.string.kps_error_server, e.message))
                    }
                }
            }
            .show()
    }
}

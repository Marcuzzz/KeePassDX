package com.kunzisoft.keepass.database.sync.server

import android.content.Context
import android.os.Build

/**
 * The signed-in keepass-server account. Only the device token is stored (app private storage,
 * revocable from the server's web UI), never the account password.
 */
data class KpsAccount(val serverUrl: String, val username: String, val token: String) {

    fun client(): KpsClient = KpsClient(serverUrl, token)

    companion object {
        private const val PREFS = "keepass_server_account"
        private const val KEY_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_TOKEN = "token"

        val deviceName: String
            get() = "KeePassDX on ${Build.MANUFACTURER} ${Build.MODEL}".trim()

        fun load(context: Context): KpsAccount? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = prefs.getString(KEY_URL, null) ?: return null
            val username = prefs.getString(KEY_USERNAME, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            return KpsAccount(url, username, token)
        }

        /** The last server URL and username, also after signing out, to prefill the connect dialog. */
        fun lastUsed(context: Context): Pair<String, String> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return (prefs.getString(KEY_URL, null) ?: "") to (prefs.getString(KEY_USERNAME, null) ?: "")
        }

        fun save(context: Context, account: KpsAccount) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_URL, account.serverUrl)
                .putString(KEY_USERNAME, account.username)
                .putString(KEY_TOKEN, account.token)
                .apply()
        }

        fun signOut(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply()
        }

        /** Client for a vault, only if it belongs to the signed-in server. */
        fun clientFor(context: Context, vault: KpsVault): KpsClient? {
            val account = load(context) ?: return null
            if (KpsClient.normalizeUrl(account.serverUrl) != KpsClient.normalizeUrl(vault.state.serverUrl)) return null
            return account.client()
        }
    }
}

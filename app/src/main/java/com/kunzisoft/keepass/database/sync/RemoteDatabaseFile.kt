package com.kunzisoft.keepass.database.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kunzisoft.keepass.database.exception.StorageProviderDatabaseException
import com.kunzisoft.keepass.database.exception.StorageVerificationDatabaseException
import com.kunzisoft.keepass.utils.UriUtil.getDocumentFile
import com.kunzisoft.keepass.utils.getUriInputStream
import com.kunzisoft.keepass.utils.getUriOutputStream
import com.kunzisoft.keepass.utils.withContentScheme
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Safe access to a database file hosted by a storage provider (Google Drive, Nextcloud...).
 * The remote content is always copied to a local cache before being parsed, so provider
 * failures are retried and detected before any decryption, and the content hash is known
 * to detect modifications made by another device.
 */
object RemoteDatabaseFile {

    private const val TAG = "RemoteDatabaseFile"
    private const val BACKUP_DIRECTORY = "backup"
    private const val BACKUP_EXTENSION = ".kdbx"
    const val BACKUPS_TO_KEEP = 3

    // Delays before each new attempt, a provider may still be downloading the file
    val RETRY_DELAYS_MS = longArrayOf(1000L, 3000L, 6000L)

    class Snapshot(val file: File, val sha256: ByteArray) {
        fun inputStream(): InputStream = file.inputStream()
        fun delete() {
            try {
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to delete $file", e)
            }
        }
    }

    /**
     * Copy the content of [uri] to a cache file and compute its hash,
     * retry with the [retryDelaysMs] delays if the provider fails
     */
    @Throws(StorageProviderDatabaseException::class)
    fun download(
        context: Context,
        uri: Uri,
        retryDelaysMs: LongArray = RETRY_DELAYS_MS
    ): Snapshot {
        var attempt = 0
        while (true) {
            val cacheFile = File.createTempFile("remote_", ".tmp", context.cacheDir)
            try {
                val inputStream = context.contentResolver.getUriInputStream(uri)
                    ?: throw IOException("No input stream for $uri")
                val sha256 = inputStream.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyToWithDigest(output)
                    }
                }
                return Snapshot(cacheFile, sha256)
            } catch (e: Exception) {
                cacheFile.delete()
                if (e !is IOException && e !is SecurityException && e !is IllegalStateException)
                    throw StorageProviderDatabaseException(e)
                if (attempt >= retryDelaysMs.size)
                    throw StorageProviderDatabaseException(e)
                Log.w(TAG, "Unable to read $uri, retry in ${retryDelaysMs[attempt]} ms", e)
                Thread.sleep(retryDelaysMs[attempt])
                attempt++
            }
        }
    }

    fun exists(context: Context, uri: Uri): Boolean {
        return try {
            uri.getDocumentFile(context)?.exists() ?: true
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Read back [uri] and compare it with the [writtenFile].
     * Only the known provider bug (previous longer content not truncated) is repaired,
     * a provider that uploads asynchronously (Google Drive) may still serve the previous
     * content or nothing at all for a while, the written content must not be touched then.
     */
    @Throws(StorageVerificationDatabaseException::class, StorageProviderDatabaseException::class)
    fun verifyWrite(context: Context, uri: Uri, writtenFile: File) {
        val snapshot = try {
            download(context, uri, retryDelaysMs = longArrayOf(RETRY_DELAYS_MS[0]))
        } catch (e: StorageProviderDatabaseException) {
            Log.w(TAG, "Unable to read back $uri, upload probably pending", e)
            return
        }
        val trailingBytes = try {
            if (snapshot.sha256.contentEquals(sha256(writtenFile)))
                return
            snapshot.file.length() > writtenFile.length()
                    && sha256(snapshot.file, writtenFile.length()).contentEquals(sha256(writtenFile))
        } finally {
            snapshot.delete()
        }
        if (!trailingBytes) {
            Log.w(TAG, "Read back of $uri not yet updated, upload probably pending")
            return
        }
        Log.w(TAG, "Trailing bytes after the written content of $uri, rewrite with truncation")
        rewriteTruncated(context, uri, writtenFile)
        Thread.sleep(RETRY_DELAYS_MS[0])
        if (!readBackMatches(context, uri, sha256(writtenFile)))
            throw StorageVerificationDatabaseException()
    }

    private fun readBackMatches(context: Context, uri: Uri, expected: ByteArray): Boolean {
        val snapshot = download(context, uri, retryDelaysMs = longArrayOf(RETRY_DELAYS_MS[0]))
        return try {
            snapshot.sha256.contentEquals(expected)
        } finally {
            snapshot.delete()
        }
    }

    private fun rewriteTruncated(context: Context, uri: Uri, writtenFile: File) {
        if (uri.withContentScheme() && rewriteWithDescriptor(context, uri, writtenFile))
            return
        try {
            context.contentResolver.getUriOutputStream(uri)?.use { output ->
                writtenFile.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("No output stream for $uri")
        } catch (e: IOException) {
            throw StorageProviderDatabaseException(e)
        }
    }

    private fun rewriteWithDescriptor(context: Context, uri: Uri, writtenFile: File): Boolean {
        return try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rwt")
                ?: return false
            descriptor.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    output.channel.truncate(0)
                    writtenFile.inputStream().use { it.copyTo(output) }
                    output.channel.truncate(writtenFile.length())
                    output.fd.sync()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "File descriptor truncation not supported for $uri", e)
            false
        }
    }

    /**
     * Keep a copy of [snapshot] as the last known content of [uri]
     */
    fun backup(context: Context, uri: Uri, snapshot: Snapshot) {
        try {
            backup(backupDirectory(context), uri.toString(), snapshot.file)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to backup $uri", e)
        }
    }

    fun lastBackup(context: Context, uri: Uri): File? {
        return listBackups(backupDirectory(context), uri.toString()).firstOrNull()
    }

    fun isBackup(context: Context, uri: Uri?): Boolean {
        val path = uri?.takeIf { it.scheme == "file" }?.path ?: return false
        return path.startsWith(backupDirectory(context).absolutePath)
    }

    /**
     * Link the backups of [oldUri] to [newUri], when the file was selected again
     */
    fun moveBackups(context: Context, oldUri: Uri, newUri: Uri) {
        try {
            moveBackups(backupDirectory(context), oldUri.toString(), newUri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Unable to move backups of $oldUri", e)
        }
    }

    internal fun moveBackups(directory: File, oldKey: String, newKey: String) {
        val oldPrefix = backupPrefix(oldKey)
        val newPrefix = backupPrefix(newKey)
        listBackups(directory, oldKey).forEach { backup ->
            backup.renameTo(File(directory, newPrefix + backup.name.removePrefix(oldPrefix)))
        }
        listBackups(directory, newKey).drop(BACKUPS_TO_KEEP).forEach { it.delete() }
    }

    private fun backupDirectory(context: Context): File {
        return File(context.filesDir, BACKUP_DIRECTORY)
    }

    internal fun backup(directory: File, key: String, file: File, now: Long = System.currentTimeMillis()) {
        directory.mkdirs()
        val prefix = backupPrefix(key)
        // Don't duplicate an identical last backup
        val last = listBackups(directory, key).firstOrNull()
        if (last != null && sha256(last).contentEquals(sha256(file)))
            return
        file.copyTo(File(directory, "$prefix$now$BACKUP_EXTENSION"), overwrite = true)
        listBackups(directory, key).drop(BACKUPS_TO_KEEP).forEach { it.delete() }
    }

    /**
     * Backups of [key], most recent first
     */
    internal fun listBackups(directory: File, key: String): List<File> {
        val prefix = backupPrefix(key)
        return directory.listFiles { file ->
            file.name.startsWith(prefix) && file.name.endsWith(BACKUP_EXTENSION)
        }?.sortedByDescending {
            it.name.removePrefix(prefix).removeSuffix(BACKUP_EXTENSION).toLongOrNull() ?: 0L
        } ?: emptyList()
    }

    private fun backupPrefix(key: String): String {
        return sha256(key.toByteArray()).toHex().take(16) + "_"
    }

    fun sha256(file: File): ByteArray {
        return file.inputStream().use { input ->
            input.copyToWithDigest(null)
        }
    }

    private fun sha256(file: File, length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        file.inputStream().use { input ->
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest()
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun InputStream.copyToWithDigest(output: java.io.OutputStream?): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = read(buffer)
        while (read >= 0) {
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
            read = read(buffer)
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

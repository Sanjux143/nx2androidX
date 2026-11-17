package com.nx.android.data

import com.nx.android.domain.ProfilesPackage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The game profiles package, fetched over the network.
 *
 * The download lands in a .part file and is only put in place when it is whole: a package that was cut short
 * never reaches the importer. When the caller knows the digest the package must have, it is checked before the
 * file is put in place - a server that serves something else is refused.
 */
object ProfilesDownloader {

    /** The names of the packages a release carries (its assets page is a plain web page) */
    fun packagesOf(releaseTag: String): List<String> {
        val html = body(ProfilesPackage.assetsPage(releaseTag)) ?: return emptyList()
        return ProfilesPackage.packageNamesIn(html)
    }

    /**
     * Downloads a package into [dest].
     *
     * @param expectedSha256 the digest the package must have; empty when it is not known (the package of another
     *   release: the importer takes it and the row says it is not this build's)
     * @param onProgress how far along it is, 0 to 1
     * @param cancelled whether the download was called off; it stops at the next chunk
     */
    fun download(
        url: String,
        dest: File,
        expectedSha256: String,
        onProgress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Result<File> {
        val tmp = File(dest.path + ".part")
        return runCatching {
            tmp.parentFile?.mkdirs()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("User-Agent", "nx2android")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            if (cancelled()) throw IOException("cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256.isNotEmpty() && sha != expectedSha256) {
                throw IOException("the package is not the one this build made")
            }
            if (dest.exists() && !dest.delete()) throw IOException("the old package could not be replaced")
            if (!tmp.renameTo(dest)) throw IOException("the package could not be put in place")
            dest
        }.also { if (it.isFailure) tmp.delete() }
    }

    /** A page's body (the asset list is a small one); null when the server did not answer */
    private fun body(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "nx2android")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        null
    }
}

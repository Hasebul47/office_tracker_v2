package com.officetracker.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.officetracker.BuildConfig
import com.officetracker.data.local.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val checksumUrl: String?,
    val sizeBytes: Long,
)

/**
 * In-app updates from GitHub Releases. The downloaded APK is verified against the published
 * SHA-256 checksum and must be signed with the same key as the installed app.
 */
class UpdateManager(
    private val context: Context,
    private val store: SessionStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(force: Boolean = false): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            if (!force && now - store.lastUpdateCheck() < 6 * 3_600_000L) return@runCatching null
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OfficeTracker/${BuildConfig.VERSION_NAME}")
                .build()
            val body = http.newCall(request).execute().use { response ->
                if (response.code == 404) return@runCatching null
                check(response.isSuccessful) { "Update server returned ${response.code}" }
                response.body?.string().orEmpty()
            }
            store.setLastUpdateCheck(now)
            val json = JSONObject(body)
            val version = json.optString("tag_name").removePrefix("v").removePrefix("V")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return@runCatching null
            if (!force && store.skippedVersion() == version) return@runCatching null

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkName: String? = null
            var size = 0L
            val urls = mutableMapOf<String, String>()
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    val url = a.optString("browser_download_url")
                    urls[name] = url
                    if (name.endsWith(".apk") && (apkUrl == null || name.contains("release"))) {
                        apkUrl = url; apkName = name; size = a.optLong("size")
                    }
                }
            }
            val apk = apkUrl ?: return@runCatching null
            UpdateInfo(
                version = version,
                notes = json.optString("body").take(2000),
                apkUrl = apk,
                checksumUrl = apkName?.let { urls["$it.sha256"] },
                sizeBytes = size,
            )
        }
    }

    suspend fun skip(version: String) = store.setSkippedVersion(version)

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "office-tracker-${info.version}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed (${response.code})" }
                val body = response.body ?: error("Empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            info.checksumUrl?.let { url ->
                val expected = http.newCall(Request.Builder().url(url).build()).execute().use {
                    it.body?.string().orEmpty().trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
                }
                if (expected.isNotEmpty() && expected != actual) {
                    file.delete()
                    error("Downloaded file is corrupted (checksum mismatch). Try again.")
                }
            }
            if (signatureMismatch(file)) {
                file.delete()
                error("This update is not signed with the company key and was blocked.")
            }
            file
        }
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** True only when both signatures could be read and they differ. */
    @Suppress("DEPRECATION")
    private fun signatureMismatch(apk: File): Boolean {
        val pm = context.packageManager
        return try {
            val (archive, installed) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val a = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?.signingInfo?.apkContentsSigners
                val i = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
                a to i
            } else {
                val a = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures
                val i = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
                a to i
            }
            if (archive.isNullOrEmpty() || installed.isNullOrEmpty()) return false
            archive.toCertSet() != installed.toCertSet()
        } catch (e: Exception) {
            false
        }
    }

    private fun Array<out Signature>.toCertSet(): Set<String> = map { it.toCharsString() }.toSet()

    companion object {
        fun isNewer(remote: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val r = parts(remote)
            val c = parts(current)
            for (i in 0 until maxOf(r.size, c.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = c.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}

package com.avcan.fiyatetiket

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object UpdateManager {
    private const val RELEASE_API =
        "https://api.github.com/repos/avcangida/avcan-app-updates/releases/latest"

    private data class ReleaseInfo(
        val tag: String,
        val downloadUrl: String,
        val sha256: String
    )

    fun checkForUpdate(activity: ComponentActivity) {
        activity.lifecycleScope.launch {
            cacheDirectory = activity.cacheDir
            val release = try {
                withContext(Dispatchers.IO) { fetchLatestRelease() }
            } catch (_: Exception) {
                return@launch // Network/API errors do not block normal app use.
            }

            val currentVersion = try {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
            } catch (_: Exception) {
                "0.0.0"
            }

            if (compareVersions(release.tag.removePrefix("v"), currentVersion) <= 0) return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch

            AlertDialog.Builder(activity)
                .setTitle("AVCAN gÃ¼ncellemesi")
                .setMessage("Yeni sÃ¼rÃ¼m ${release.tag} hazÄ±r. UygulamayÄ± ÅŸimdi gÃ¼ncellemek ister misiniz?")
                .setPositiveButton("GÃ¼ncelle") { _, _ ->
                    activity.lifecycleScope.launch {
                        try {
                            Toast.makeText(activity, "GÃ¼ncelleme indiriliyor...", Toast.LENGTH_LONG).show()
                            val apk = withContext(Dispatchers.IO) { downloadAndVerify(release) }
                            openInstaller(activity, apk)
                        } catch (e: Exception) {
                            Toast.makeText(
                                activity,
                                "GÃ¼ncelleme baÅŸarÄ±sÄ±z: ${e.localizedMessage ?: "Bilinmeyen hata"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton("Daha sonra", null)
                .show()
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub yanÄ±tÄ±: HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                throw IllegalStateException("YayÄ±n taslak veya Ã¶n sÃ¼rÃ¼m.")
            }
            val assets = json.getJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until assets.length()) {
                val candidate = assets.getJSONObject(i)
                if (candidate.optString("name") == "app-release.apk") {
                    apk = candidate
                    break
                }
            }
            val asset = apk ?: throw IllegalStateException("Release iÃ§inde app-release.apk bulunamadÄ±.")
            val digest = asset.optString("digest")
            if (!digest.startsWith("sha256:")) {
                throw IllegalStateException("GitHub SHA-256 Ã¶zeti bulunamadÄ±; gÃ¼venlik iÃ§in kurulum durduruldu.")
            }
            return ReleaseInfo(
                tag = json.getString("tag_name"),
                downloadUrl = asset.getString("browser_download_url"),
                sha256 = digest.removePrefix("sha256:").lowercase(Locale.US)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndVerify(release: ReleaseInfo): File {
        val folder = File(appCacheDir(), "updates")
        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("GÃ¼ncelleme klasÃ¶rÃ¼ oluÅŸturulamadÄ±.")
        }
        val apk = File(folder, "app-release.apk")
        val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("APK indirilemedi: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }

        val actual = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(release.sha256, ignoreCase = true)) {
            apk.delete()
            throw SecurityException("SHA-256 doÄŸrulamasÄ± baÅŸarÄ±sÄ±z. APK kurulmayacak.")
        }
        return apk
    }

    // Uses the app's cache directory and is set immediately before download.
    private lateinit var cacheDirectory: File
    private fun appCacheDir(): File = cacheDirectory

    private fun openInstaller(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("Kurulum izni gerekli")
                .setMessage("AVCAN uygulamasÄ±nÄ±n gÃ¼ncelleme kurabilmesi iÃ§in bu kaynaktan uygulama yÃ¼kleme iznini aÃ§Ä±n. Ä°zin verdikten sonra AVCAN'Ä± yeniden aÃ§Ä±p gÃ¼ncellemeyi kontrol edin.")
                .setPositiveButton("AyarlarÄ± aÃ§") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                }
                .setNegativeButton("Ä°ptal", null)
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(installIntent)
    }

    private fun compareVersions(first: String, second: String): Int {
        val a = first.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toLongOrNull() ?: 0L }
        val b = second.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0L }
            val right = b.getOrElse(i) { 0L }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
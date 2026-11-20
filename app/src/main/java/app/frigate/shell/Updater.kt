package app.frigate.shell

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update from GitHub Releases: at launch, compare the latest release tag (vX.Y.Z)
 * with this build's version and, if newer, download its .apk asset and hand it to
 * Android's PackageInstaller, which asks the user to confirm the update.
 */
class Updater(private val context: Context) {

    data class Release(val version: String, val apkUrl: String, val notes: String)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Calls [onNewer] on the main thread if the latest release is newer than this build. */
    fun check(onNewer: (Release) -> Unit) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        io.execute {
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "FrigateAndroid/${BuildConfig.VERSION_NAME}")
                if (conn.responseCode != 200) return@execute
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()

                val version = json.optString("tag_name").removePrefix("v")
                val assets = json.optJSONArray("assets") ?: return@execute
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@execute
                val release = Release(
                    version = version,
                    apkUrl = apk.getString("browser_download_url"),
                    notes = json.optString("body").trim(),
                )
                if (isNewer(version, BuildConfig.VERSION_NAME)) main.post { onNewer(release) }
            } catch (_: Exception) {
                // Offline, rate-limited or no releases yet: try again next launch.
            }
        }
    }

    /** Downloads the APK to the cache, reporting 0..100 (or -1 if size unknown). */
    fun download(release: Release, onProgress: (Int) -> Unit, onDone: (File) -> Unit, onError: (String) -> Unit) {
        io.execute {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "frigate-${release.version}.apk")
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "FrigateAndroid/${BuildConfig.VERSION_NAME}")
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -2
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                main.post { onProgress(pct) }
                            }
                        }
                    }
                }
                conn.disconnect()
                main.post { onDone(file) }
            } catch (e: Exception) {
                main.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Streams the APK into a PackageInstaller session; Android shows the confirm screen. */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    companion object {
        /** True if dotted version [a] is greater than [b] ("1.10.0" > "1.9.3"). */
        fun isNewer(a: String, b: String): Boolean {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}

/** Receives PackageInstaller results; opens Android's confirm screen when it asks. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts nothing; the user reopens the app.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // user tapped Cancel
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, context.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}

package app.frigate.shell

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.util.concurrent.Executors

/**
 * Saves Frigate downloads (exports, clips, snapshots, logs) to the public Downloads folder.
 * Done natively because Android's DownloadManager would reject Frigate's self-signed
 * certificate and doesn't carry the WebView's login cookie.
 */
class Downloader(private val context: Context, private val prefs: Prefs) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun fromUrl(
        url: String,
        suggestedName: String?,
        userAgent: String?,
        contentDisposition: String? = null,
        mimeType: String? = null,
    ) {
        val first = suggestedName?.takeIf { it.isNotBlank() }
            ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        toast(R.string.download_started, first)
        io.execute {
            try {
                val pin = prefs.pinFor(Prefs.hostPort(Uri.parse(url)))
                val conn = Tls.open(URL(url), pin)
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val type = conn.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: mimeType
                val name = suggestedName?.takeIf { it.isNotBlank() }
                    ?: URLUtil.guessFileName(url, conn.getHeaderField("Content-Disposition") ?: contentDisposition, type)
                conn.inputStream.use { input ->
                    save(name, type ?: mimeFor(name)) { out -> input.copyTo(out, 64 * 1024) }
                }
                conn.disconnect()
                toast(R.string.download_done, name)
            } catch (e: Exception) {
                toast(R.string.download_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** data: URLs, which Frigate uses for snapshot and log downloads. */
    fun fromDataUrl(dataUrl: String, suggestedName: String?) {
        io.execute {
            try {
                val comma = dataUrl.indexOf(',')
                if (!dataUrl.startsWith("data:") || comma < 0) throw IOException("bad data URL")
                val meta = dataUrl.substring(5, comma)
                val payload = dataUrl.substring(comma + 1)
                val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
                val bytes = if (meta.endsWith(";base64")) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray()
                }
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                val name = suggestedName?.takeIf { it.isNotBlank() } ?: "frigate-download.$ext"
                save(name, mime) { it.write(bytes) }
                toast(R.string.download_done, name)
            } catch (e: Exception) {
                toast(R.string.download_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun save(rawName: String, mime: String, write: (OutputStream) -> Unit) {
        val name = rawName.replace('/', '_').replace('\\', '_')
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("can't create the file in Downloads")
        try {
            val out = resolver.openOutputStream(item) ?: throw IOException("can't open the file")
            out.use(write)
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        } catch (e: Exception) {
            resolver.delete(item, null, null)
            throw e
        }
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun toast(res: Int, arg: String) = main.post {
        Toast.makeText(context, context.getString(res, arg), Toast.LENGTH_SHORT).show()
    }
}

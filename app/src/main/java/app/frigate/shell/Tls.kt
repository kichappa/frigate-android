package app.frigate.shell

import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Tls {
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** Four lines of eight bytes, so a fingerprint fits a dialog. */
    fun pretty(fingerprint: String): String =
        fingerprint.split(":").chunked(8).joinToString("\n") { it.joinToString(":") }

    /**
     * Opens a connection that accepts the server's certificate when its SHA-256 matches
     * [pin] (the one the user trusted in the WebView), and otherwise falls back to normal
     * Android trust and hostname checks.
     */
    fun open(url: URL, pin: String?): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && pin != null) {
            conn.sslSocketFactory = pinnedContext(pin).socketFactory
            val default = HttpsURLConnection.getDefaultHostnameVerifier()
            conn.hostnameVerifier = HostnameVerifier { host, session ->
                val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
                (leaf is X509Certificate && fingerprint(leaf) == pin) || default.verify(host, session)
            }
        }
        return conn
    }

    private fun pinnedContext(pin: String): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val pinned = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isNotEmpty() && fingerprint(chain[0]) == pin) return
                system.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned), null) }
    }
}

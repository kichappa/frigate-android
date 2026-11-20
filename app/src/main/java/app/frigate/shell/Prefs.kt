package app.frigate.shell

import android.content.Context
import android.net.Uri

/** Server address and the per-server pinned certificate (trust on first use). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("frigate_shell", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = sp.getString(KEY_SERVER, null)
        set(value) = sp.edit().putString(KEY_SERVER, value).apply()

    fun pinFor(hostPort: String): String? = sp.getString(PIN_PREFIX + hostPort, null)

    fun setPin(hostPort: String, fingerprint: String) =
        sp.edit().putString(PIN_PREFIX + hostPort, fingerprint).apply()

    fun clearPin(hostPort: String) = sp.edit().remove(PIN_PREFIX + hostPort).apply()

    companion object {
        /** Pre-filled on the setup screen; set defaultServer in local.properties (not in git). */
        val DEFAULT_SERVER: String = BuildConfig.DEFAULT_SERVER
        private const val KEY_SERVER = "server_url"
        private const val PIN_PREFIX = "pin:"

        /** "host:port" with the scheme's default port filled in; the key for pins. */
        fun hostPort(uri: Uri): String {
            val port = when {
                uri.port != -1 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
            return "${uri.host?.lowercase()}:$port"
        }

        /** "scheme://host[:port]", as the browser writes an origin. */
        fun origin(uri: Uri): String {
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port"
        }

        /**
         * Adds https:// when no scheme is given, drops a default port, query and trailing
         * slash. Keeps a path, for Frigate served under a sub-path by a reverse proxy.
         * Returns null if the input can't be a server address.
         */
        fun normalize(input: String): String? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "https://$s"
            val uri = Uri.parse(s)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
            val path = (uri.path ?: "").trimEnd('/')
            return "$scheme://$host$port$path"
        }
    }
}

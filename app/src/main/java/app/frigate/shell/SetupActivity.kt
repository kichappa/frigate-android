package app.frigate.shell

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

/**
 * Server address entry, styled after Frigate's login page. Opened on first launch, from
 * the "Server" launcher shortcut (long-press the app icon), and from the error screen.
 * Returns RESULT_OK whenever the main screen should reload.
 */
class SetupActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var certInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        val input = findViewById<EditText>(R.id.server_url)
        val error = findViewById<TextView>(R.id.setup_error)
        certInfo = findViewById(R.id.cert_info)

        input.setText(prefs.serverUrl ?: Prefs.DEFAULT_SERVER)
        input.setSelection(input.text.length)

        fun connect() {
            val url = Prefs.normalize(input.text.toString())
            if (url == null) {
                error.setText(R.string.setup_invalid)
                error.isVisible = true
                return
            }
            prefs.serverUrl = url
            setResult(RESULT_OK)
            finish()
        }

        findViewById<View>(R.id.connect).setOnClickListener { connect() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connect()
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.forget_cert).setOnClickListener {
            prefs.serverUrl?.let { prefs.clearPin(Prefs.hostPort(Uri.parse(it))) }
            setResult(RESULT_OK)
            refreshCertInfo()
            Toast.makeText(this, R.string.setup_cert_forgotten, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.sign_out).setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            setResult(RESULT_OK)
            Toast.makeText(this, R.string.setup_signed_out, Toast.LENGTH_SHORT).show()
        }

        refreshCertInfo()
    }

    private fun refreshCertInfo() {
        val pin = prefs.serverUrl?.let { prefs.pinFor(Prefs.hostPort(Uri.parse(it))) }
        certInfo.isVisible = pin != null
        if (pin != null) certInfo.text = getString(R.string.setup_trusted_fmt, pin.take(23))
    }
}

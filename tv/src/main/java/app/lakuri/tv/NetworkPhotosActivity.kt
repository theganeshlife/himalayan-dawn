package app.lakuri.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.lakuri.tv.smb.LocalNetwork
import app.lakuri.tv.smb.Smb2Client
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Launcher › Photos: choose a shared folder on a computer or NAS on the home network.
 * Everything is reachable with the remote: find the computer, sign in, then pick a share and folder from lists.
 */
class NetworkPhotosActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private var client: Smb2Client? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = Tiles.dp(this, 48f)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Tiles.dp(this@NetworkPhotosActivity, 32f), pad, pad)
        }
        setContentView(ScrollView(this).apply { setBackgroundResource(R.color.night); addView(list) })

        val saved = prefs.networkPhotos
        text("Photos from your network", 28f, R.font.mukta_light, R.color.snow, bottom = 8f)
        note("Show photos from a shared folder on a computer or NAS in your home. Only computers on your home network can be used; internet addresses are refused.")
        if (saved != null) note("Now showing: ${location(saved.host, saved.share, saved.folder)}")

        section("Computer")
        val findButton = button("Find computers on this network") { findComputers() }
        host = field("Address, for example 192.168.1.50", saved?.host.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        section("Sign in")
        user = field("User name (leave empty for guest access)", saved?.user.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        password = field("Password", saved?.password.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.imeOptions = EditorInfo.IME_ACTION_DONE
        connectButton = button("Connect and choose a folder") { connect() }
        status = text("", 14f, R.font.mukta_regular, R.color.saffron, top = 8f)
        if (saved != null) {
            section("Built-in photos")
            button("Stop using network photos") {
                prefs.networkPhotos = null
                NetworkPhotos.disconnect()
                Toast.makeText(this, "Showing the built-in photos of Nepal", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        // Start on a button, not a text field, so the on-screen keyboard doesn't cover the screen at once.
        (if (saved == null) findButton else connectButton).requestFocus()
    }

    override fun onDestroy() {
        val c = client
        worker.execute { c?.close() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun location(host: String, share: String, folder: String) =
        "\\\\$host\\$share" + if (folder.isEmpty()) "" else "\\$folder"

    /** Runs [work] off the main thread; shows its error, if any, on screen. */
    private fun background(busy: String, work: () -> Unit): Unit {
        status.text = busy
        connectButton.isEnabled = false
        worker.execute {
            try {
                work()
            } catch (e: IOException) {
                ui { status.text = e.message ?: "Couldn't connect" }
            } finally {
                ui { connectButton.isEnabled = true }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }

    private fun findComputers() = background("Looking for computers that share files…") {
        val found = LocalNetwork.findSmbServers()
        ui {
            if (found.isEmpty()) {
                status.text = "No computers sharing files were found. Check that file sharing is on, or type the address."
                return@ui
            }
            status.text = ""
            val names = found.map { it.hostAddress ?: "" }
            dialog("Computers on this network", names) { i -> host.setText(names[i]); user.requestFocus() }
        }
    }

    private fun connect() {
        val h = host.text.toString().trim()
        val u = user.text.toString().trim()
        val p = password.text.toString()
        background("Connecting to $h…") {
            client?.close()
            val c = Smb2Client.connect(h, u, p)
            client = c
            val shares = try { c.listShares() } catch (e: IOException) { emptyList() }
            ui {
                status.text = ""
                val items = shares + "Type a share name…"
                dialog("Shared folders on $h", items) { i ->
                    if (i < shares.size) browse(h, u, p, shares[i], "") else typeShare(h, u, p)
                }
            }
        }
    }

    private fun typeShare(h: String, u: String, p: String) {
        val input = EditText(this).apply { setSingleLine(); hint = "Photos" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Name of the shared folder")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { browse(h, u, p, it, "") } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lists the subfolders of [path] in [share]; the first item uses the current folder. */
    private fun browse(h: String, u: String, p: String, share: String, path: String): Unit = background("Opening ${location(h, share, path)}…") {
        val c = client ?: throw IOException("Not connected")
        val entries = c.list(share, path)
        val folders = entries.filter { it.isDirectory }.map { it.name }.sortedBy { it.lowercase() }
        val photos = entries.count(NetworkPhotos::isPhoto)
        ui {
            status.text = ""
            val use = "✓ Use this folder ($photos ${if (photos == 1) "photo" else "photos"}${if (folders.isEmpty()) "" else ", plus subfolders"})"
            val items = listOf(use) + (if (path.isEmpty()) emptyList() else listOf("↑ Up one folder")) + folders.map { "▸ $it" }
            dialog(location(h, share, path), items) { i ->
                val up = if (path.isEmpty()) 0 else 1
                when {
                    i == 0 -> save(NetworkPhotos.Config(h, share, path, u, p))
                    i == up -> browse(h, u, p, share, path.substringBeforeLast('\\', ""))
                    else -> browse(h, u, p, share, NetworkPhotos.join(path, folders[i - 1 - up]))
                }
            }
        }
    }

    private fun save(config: NetworkPhotos.Config) {
        prefs.networkPhotos = config
        NetworkPhotos.disconnect()
        Toast.makeText(this, "Photos will come from ${location(config.host, config.share, config.folder)}", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dialog(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Views, styled like SettingsActivity ----

    private fun section(t: String) = text(t, 13f, R.font.mukta_medium, R.color.saffron, top = 18f, bottom = 6f)
    private fun note(t: String) = text(t, 13f, R.font.mukta_light, R.color.snow_dim, top = 4f, bottom = 4f)

    private fun text(t: String, size: Float, font: Int, color: Int, top: Float = 0f, bottom: Float = 0f) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = resources.getFont(font)
        setTextColor(getColor(color))
        setPadding(0, Tiles.dp(this@NetworkPhotosActivity, top), 0, Tiles.dp(this@NetworkPhotosActivity, bottom))
        list.addView(this, LinearLayout.LayoutParams(Tiles.dp(this@NetworkPhotosActivity, 640f), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun button(label: String, action: () -> Unit) = Button(this, null, 0, R.style.Dawn_Button).apply {
        text = label
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setOnClickListener { action() }
        list.addView(this, LinearLayout.LayoutParams(Tiles.dp(this@NetworkPhotosActivity, 520f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Tiles.dp(this@NetworkPhotosActivity, 6f)
        })
    }

    private fun field(hintText: String, value: String, type: Int) = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = type
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_NEXT
        setBackgroundResource(R.drawable.tile_bg)
        setTextColor(getColorStateList(R.color.button_text))
        setHintTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(0x991B1F3B.toInt(), getColor(R.color.snow_dim))))
        typeface = resources.getFont(R.font.mukta_regular)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val p = Tiles.dp(this@NetworkPhotosActivity, 20f)
        setPadding(p, p / 2, p, p / 2)
        list.addView(this, LinearLayout.LayoutParams(Tiles.dp(this@NetworkPhotosActivity, 520f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Tiles.dp(this@NetworkPhotosActivity, 6f)
        })
    }
}

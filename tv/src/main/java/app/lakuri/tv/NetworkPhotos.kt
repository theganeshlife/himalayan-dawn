package app.lakuri.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import app.lakuri.tv.smb.Smb2Client
import app.lakuri.tv.smb.SmbEntry
import app.lakuri.tv.smb.SmbException
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Background photos from a shared folder on the home network (SMB), chosen in Launcher › Photos.
 * One connection is shared by the home screen, welcome screen and screensaver, and reopened if
 * the computer drops it.
 */
object NetworkPhotos {
    private const val MAX_PHOTOS = 2000
    private const val MAX_DEPTH = 3
    private const val MAX_BYTES = 40L shl 20
    val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    data class Config(val host: String, val share: String, val folder: String, val user: String, val password: String)

    private var client: Smb2Client? = null
    private var clientFor: Config? = null

    fun isPhoto(e: SmbEntry) = !e.isDirectory && e.name.substringAfterLast('.').lowercase() in EXTENSIONS

    /** Every photo in the chosen folder and a few levels of subfolders; empty if the share can't be reached. */
    fun list(context: Context): List<Scene> {
        val config = Prefs(context).networkPhotos ?: return emptyList()
        return try {
            val out = mutableListOf<Scene>()
            withClient(config) { c -> out.clear(); walk(c, config.share, config.folder, 0, out) }
            out.sortedBy { it.id }
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun walk(c: Smb2Client, share: String, dir: String, depth: Int, out: MutableList<Scene>) {
        val entries = c.list(share, dir)
        for (e in entries) {
            if (out.size >= MAX_PHOTOS) return
            if (isPhoto(e) && e.size <= MAX_BYTES) {
                val path = join(dir, e.name)
                out += Scene("smb:$path", e.name.substringBeforeLast('.'), "", "", "", remotePath = path)
            }
        }
        if (depth < MAX_DEPTH) for (e in entries) if (e.isDirectory && out.size < MAX_PHOTOS) walk(c, share, join(dir, e.name), depth + 1, out)
    }

    fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir\\$name"

    /** Downloads and decodes a photo at roughly screen size, turned upright. Null if it can't be read. */
    fun decode(context: Context, path: String, reqW: Int, reqH: Int): Bitmap? {
        val config = Prefs(context).networkPhotos ?: return null
        return try {
            val bytes = withClient(config) { it.read(config.share, path, MAX_BYTES) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.let { upright(it, orientation(bytes)) }
        } catch (e: IOException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Runs [block] on the shared connection, reconnecting once if the old one has gone stale. */
    @Synchronized
    private fun <T> withClient(config: Config, block: (Smb2Client) -> T): T {
        if (clientFor != config) disconnect()
        client?.let {
            try {
                return block(it)
            } catch (e: SmbException) {
                if (e.status != 0) throw e // the computer answered (e.g. file not found): not a dead connection
                disconnect()
            } catch (e: IOException) {
                disconnect() // the computer may have closed an idle connection; try once more
            }
        }
        val fresh = Smb2Client.connect(config.host, config.user, config.password)
        client = fresh
        clientFor = config
        return block(fresh)
    }

    @Synchronized
    fun disconnect() {
        client?.close()
        client = null
        clientFor = null
    }

    private fun orientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun upright(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (turned !== bmp) bmp.recycle()
        return turned
    }
}

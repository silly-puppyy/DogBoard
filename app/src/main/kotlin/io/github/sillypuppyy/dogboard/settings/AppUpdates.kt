package io.github.sillypuppyy.dogboard.settings

import android.content.Context
import android.content.Intent
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates for the build published on GitHub (build type "github": BuildConfig.SELF_UPDATE). Once a day, while the
 * settings app is open and the check is on (About), the latest GitHub release is read; a newer version shows on the
 * home page, and "Update" downloads its APK, checks its SHA-256 (GitHub's digest) and hands it to Android's installer,
 * which also refuses an APK signed with another key. Store builds (F-Droid) have none of this: the store updates them.
 */
object AppUpdates {
    const val REPO = "silly-puppyy/DogBoard"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    class Release(val version: String, val apkUrl: String, val apkSize: Long, val sha256: String?)

    private val _available = MutableStateFlow<Release?>(null)
    /** A release newer than this build, once a check found one. */
    val available: StateFlow<Release?> = _available

    /** Whether this build can update itself at all (not a store build). */
    val supported: Boolean get() = BuildConfig.SELF_UPDATE

    /** The check's own scope: it outlives the screen that asks (the home screen's first composition is replaced at once). */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    /**
     * Checks when a day has passed since the last successful check (and the check is on). 1.0 checked inside the home
     * screen's composition, which the app replaces right after it starts: the request was cancelled every time, and
     * the time was stored first, so the next try waited a day (2026-11-24).
     */
    fun checkIfDue() {
        if (!supported || running) return
        running = true
        scope.launch {
            try {
                val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
                if (!prefs.other.updateCheck.get()) return@launch
                val now = System.currentTimeMillis()
                if (now - prefs.other.updateLastCheck.get() < DAY_MS && _available.value == null) return@launch
                val r = runCatching { latest() }.onFailure { android.util.Log.w("DogBoardPerf", "update check failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
                android.util.Log.i("DogBoardPerf", "update check: latest ${r?.version} (this ${BuildConfig.VERSION_NAME})")
                // only an answer counts as a check (a failed one tries again next time the settings open)
                if (r != null) prefs.other.updateLastCheck.set(now)
                _available.value = r?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            } finally {
                running = false
            }
        }
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) { android.util.Log.w("DogBoardPerf", "update check: HTTP ${c.responseCode}"); return@withContext null }
            val o = Json.parseToJsonElement(c.inputStream.use { it.readBytes() }.decodeToString()) as JsonObject
            fun str(j: JsonObject?, k: String) = (j?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val tag = str(o, "tag_name") ?: return@withContext null
            val apk = (o["assets"] as? JsonArray)?.map { it as JsonObject }?.firstOrNull { str(it, "name")?.endsWith(".apk") == true }
                ?: return@withContext null
            Release(
                tag.removePrefix("v"), str(apk, "browser_download_url") ?: return@withContext null,
                (apk["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1,
                str(apk, "digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        } finally {
            c.disconnect()
        }
    }

    /** "0.6.10" after "0.6.9": by number, part by part. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads [r]'s APK (checked) and opens Android's installer; false when the download or its check failed. */
    suspend fun install(context: Context, r: Release, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, "dogboard-${r.version}.apk")
        val ok = runCatching {
            val c = URL(r.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                c.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (r.apkSize > 0) onProgress(done.toFloat() / r.apkSize)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            r.sha256 == null || sha.equals(r.sha256, ignoreCase = true)
        }.getOrDefault(false)
        if (!ok) { apk.delete(); return@withContext false }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider.file", apk)
        withContext(Dispatchers.Main) {
            // Android's installer itself: ACTION_VIEW offered any app that opens APKs (an "Open with" chooser: Termux,
            // file managers) before the installer (emulator, 2026-11-24)
            @Suppress("DEPRECATION")
            context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        true
    }
}

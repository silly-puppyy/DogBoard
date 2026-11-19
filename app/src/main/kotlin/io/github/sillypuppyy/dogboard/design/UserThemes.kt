package io.github.sillypuppyy.dogboard.design

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's own themes, kept in the user's own packs (notes/design/user-themes.md; user 2026-11-19: photo themes
 * become saved themes, and "packs, they become easily shareable"). Each theme is a folder under
 * files/dogboard/themes/<id> (theme.json, and photo.jpg + analysis.txt for a photo theme); packs.json keeps the
 * packs and their themes in order.
 *
 * A photo theme is drawn by the existing Photo look: [apply] copies it into the single photo slot the keyboard
 * draws (GlassPhoto's files and the glass settings) and [saveBack] stores the slot's changes into the theme it
 * came from ([FlorisPreferenceModel.Keyboard.userTheme]).
 */
object UserThemes {
    /** [spec]: a gradient theme's theme id (GradientSpec), null for a photo theme. */
    data class Theme(val id: String, val name: String, val kind: String, val spec: String? = null)
    data class Pack(val id: String, val name: String, val themes: List<Theme>)

    const val DEFAULT_PACK = "My themes"

    private fun root(context: Context) = File(context.filesDir, "dogboard/themes").apply { mkdirs() }
    private fun dir(context: Context, id: String) = File(root(context), id)
    private fun packsFile(context: Context) = File(root(context), "packs.json")
    fun photo(context: Context, id: String) = File(dir(context, id), "photo.jpg")

    @Synchronized
    fun packs(context: Context): List<Pack> {
        val f = packsFile(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val ids = p.getJSONArray("themes")
                Pack(p.getString("id"), p.getString("name"), (0 until ids.length()).mapNotNull { j -> theme(context, ids.getString(j)) })
            }
        }.getOrDefault(emptyList())
    }

    fun theme(context: Context, id: String): Theme? = runCatching {
        val j = JSONObject(File(dir(context, id), "theme.json").readText())
        Theme(id, j.getString("name"), j.getString("kind"), j.optJSONObject("params")?.optString("spec")?.takeIf { GradientSpec.parse(it) != null })
    }.getOrNull()

    @Synchronized
    private fun writePacks(context: Context, packs: List<Pair<String, Pair<String, List<String>>>>) {
        val arr = JSONArray()
        for ((id, p) in packs) arr.put(JSONObject().put("id", id).put("name", p.first).put("themes", JSONArray(p.second)))
        packsFile(context).writeText(arr.toString(1))
    }

    private fun rawPacks(context: Context): MutableList<Pair<String, Pair<String, List<String>>>> {
        val f = packsFile(context)
        if (!f.isFile) return mutableListOf()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val ids = p.getJSONArray("themes")
            p.getString("id") to (p.getString("name") to (0 until ids.length()).map { ids.getString(it) })
        }.toMutableList()
    }

    /** A new photo theme from what the photo slot holds now, added to [pack] (the default pack when null). */
    @Synchronized
    fun createPhoto(context: Context, prefs: FlorisPreferenceModel, name: String, pack: String? = null): String? {
        if (!GlassPhoto.file(context).isFile) return null
        val id = "p" + System.currentTimeMillis().toString(36)
        val d = dir(context, id).apply { mkdirs() }
        GlassPhoto.file(context).copyTo(File(d, "photo.jpg"), overwrite = true)
        GlassPhoto.analysisFile(context).takeIf { it.isFile }?.copyTo(File(d, "analysis.txt"), overwrite = true)
        File(d, "theme.json").writeText(JSONObject().put("name", name).put("kind", "photo").put("created", System.currentTimeMillis())
            .put("params", photoParams(prefs)).toString(1))
        addToPack(context, id, pack)
        return id
    }

    /** What a photo theme's tile draws over its photo: the keyboard as the theme sets it (Glass tokens). */
    data class PhotoLook(val dim: Float, val keyOpacity: Float, val luminance: Float, val labels: String, val halo: Boolean, val actColor: String?, val actFg: String?, val pos: String)

    fun photoLook(context: Context, id: String): PhotoLook? = runCatching {
        val p = JSONObject(File(dir(context, id), "theme.json").readText()).getJSONObject("params")
        val acc = File(dir(context, id), "analysis.txt").takeIf { it.isFile }?.let { GlassPhoto.Analysis.decode(it.readText()) }?.accents
        val a = acc?.getOrNull(p.optInt("accent", 0)) ?: acc?.firstOrNull()
        PhotoLook(p.optInt("dim", 35) / 100f, p.optInt("keyOpacity", 30) / 100f, p.optInt("luminance", 300) / 1000f,
            p.optString("labels", "auto"), p.optBoolean("halo", true), a?.first, a?.second, p.optString("pos", "center"))
    }.getOrNull()

    /** A new gradient theme ([spec]: its GradientSpec id) in [pack] (the default pack when null). */
    @Synchronized
    fun createGradient(context: Context, name: String, spec: String, pack: String? = null): String {
        val id = "g" + System.currentTimeMillis().toString(36)
        dir(context, id).apply { mkdirs() }.resolve("theme.json").writeText(JSONObject().put("name", name).put("kind", "gradient")
            .put("created", System.currentTimeMillis()).put("params", JSONObject().put("spec", spec)).toString(1))
        addToPack(context, id, pack)
        return id
    }

    /** Gradient theme [id] given new colours ([spec]) and [name]. */
    @Synchronized
    fun updateGradient(context: Context, id: String, name: String, spec: String) {
        val f = File(dir(context, id), "theme.json")
        val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        f.writeText(j.put("name", name).put("params", JSONObject().put("spec", spec)).toString(1))
    }

    /** How many saved gradients there are (names a new one "Gradient N"). */
    fun gradientCount(context: Context): Int = packs(context).sumOf { p -> p.themes.count { it.kind == "gradient" } }

    private fun addToPack(context: Context, id: String, pack: String?) {
        val packs = rawPacks(context)
        val target = pack ?: packs.firstOrNull { it.second.first == DEFAULT_PACK }?.first
        val i = packs.indexOfFirst { it.first == target }
        if (i >= 0) packs[i] = packs[i].first to (packs[i].second.first to packs[i].second.second + id)
        else packs.add(0, "k" + System.currentTimeMillis().toString(36) to (DEFAULT_PACK to listOf(id)))
        writePacks(context, packs)
    }

    private fun photoParams(prefs: FlorisPreferenceModel): JSONObject = JSONObject()
        .put("dim", prefs.keyboard.glassDim.get()).put("blur", prefs.keyboard.glassBlur.get())
        .put("keyOpacity", prefs.keyboard.glassKeyOpacity.get()).put("luminance", prefs.keyboard.glassPhotoLuminance.get())
        .put("labels", prefs.keyboard.glassLabels.get()).put("halo", prefs.keyboard.glassHalo.get())
        .put("accent", prefs.keyboard.glassAccent.get()).put("pos", prefs.keyboard.glassPhotoPos.get())

    /** Loads photo theme [id] into the photo slot the keyboard draws, and marks it as the one being used. */
    suspend fun apply(context: Context, prefs: FlorisPreferenceModel, id: String): Boolean {
        val d = dir(context, id)
        val j = runCatching { JSONObject(File(d, "theme.json").readText()) }.getOrNull() ?: return false
        if (j.getString("kind") != "photo") return false
        val p = j.getJSONObject("params")
        File(d, "photo.jpg").copyTo(GlassPhoto.file(context), overwrite = true)
        File(d, "analysis.txt").takeIf { it.isFile }?.copyTo(GlassPhoto.analysisFile(context), overwrite = true)
        prefs.keyboard.glassDim.set(p.optInt("dim", 35)); prefs.keyboard.glassBlur.set(p.optInt("blur", 12))
        prefs.keyboard.glassKeyOpacity.set(p.optInt("keyOpacity", 30)); prefs.keyboard.glassPhotoLuminance.set(p.optInt("luminance", 300))
        prefs.keyboard.glassLabels.set(p.optString("labels", "auto")); prefs.keyboard.glassHalo.set(p.optBoolean("halo", true))
        prefs.keyboard.glassAccent.set(p.optInt("accent", 0)); prefs.keyboard.glassPhotoPos.set(p.optString("pos", "center"))
        prefs.keyboard.userTheme.set(id)
        prefs.keyboard.glassEnabled.set(true)
        prefs.keyboard.glassPhotoStamp.set(System.currentTimeMillis())
        return true
    }

    /** The photo slot's settings (and photo, when [photoToo]) stored back into the theme in use. */
    @Synchronized
    fun saveBack(context: Context, prefs: FlorisPreferenceModel, photoToo: Boolean = false) {
        val id = prefs.keyboard.userTheme.get().takeIf { it.isNotEmpty() } ?: return
        val d = dir(context, id)
        val f = File(d, "theme.json")
        val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        f.writeText(j.put("params", photoParams(prefs)).toString(1))
        if (photoToo) {
            GlassPhoto.file(context).takeIf { it.isFile }?.copyTo(File(d, "photo.jpg"), overwrite = true)
            GlassPhoto.analysisFile(context).takeIf { it.isFile }?.copyTo(File(d, "analysis.txt"), overwrite = true)
        }
    }

    // managing packs and themes (the Themes page's pack and theme menus)
    @Synchronized
    fun newPack(context: Context, name: String): String {
        val packs = rawPacks(context)
        val id = "k" + System.currentTimeMillis().toString(36)
        packs.add(id to (name to emptyList()))
        writePacks(context, packs)
        return id
    }

    @Synchronized
    fun renamePack(context: Context, id: String, name: String) =
        writePacks(context, rawPacks(context).map { if (it.first == id) id to (name to it.second.second) else it })

    /** Moves pack [id] [delta] places (-1 up, +1 down). */
    @Synchronized
    fun movePack(context: Context, id: String, delta: Int) {
        val packs = rawPacks(context)
        val i = packs.indexOfFirst { it.first == id }
        val j = i + delta
        if (i < 0 || j !in packs.indices) return
        packs.add(j, packs.removeAt(i))
        writePacks(context, packs)
    }

    /** Deletes pack [id] and its themes; returns the theme ids deleted. */
    @Synchronized
    fun deletePack(context: Context, id: String): List<String> {
        val packs = rawPacks(context)
        val gone = packs.firstOrNull { it.first == id }?.second?.second.orEmpty()
        gone.forEach { dir(context, it).deleteRecursively() }
        writePacks(context, packs.filter { it.first != id })
        return gone
    }

    @Synchronized
    fun renameTheme(context: Context, id: String, name: String) {
        val f = File(dir(context, id), "theme.json")
        runCatching { f.writeText(JSONObject(f.readText()).put("name", name).toString(1)) }
    }

    /** Moves theme [id] to the end of pack [to]. */
    @Synchronized
    fun moveTheme(context: Context, id: String, to: String) =
        writePacks(context, rawPacks(context).map { (pid, p) ->
            val without = p.second.filter { it != id }
            pid to (p.first to (if (pid == to) without + id else without))
        })

    @Synchronized
    fun deleteTheme(context: Context, id: String) {
        dir(context, id).deleteRecursively()
        writePacks(context, rawPacks(context).map { (pid, p) -> pid to (p.first to p.second.filter { it != id }) })
    }

    // sharing (user 2026-11-19: packs "become easily shareable"): a pack is one .dogtheme file, a zip of pack.json
    // (format, name, its themes in order) and each theme's folder (theme.json, photo.jpg, analysis.txt)
    private const val FORMAT = 1
    private val ENTRY = Regex("^[a-z0-9]{1,24}/(theme\\.json|photo\\.jpg|analysis\\.txt)$")
    private const val MAX_FILE = 20L * 1024 * 1024
    private const val MAX_THEMES = 100

    /** Pack [id] as a .dogtheme file in the cache's shared folder (the FileProvider's "shared" path), or null. */
    @Synchronized
    fun exportPack(context: Context, id: String): File? {
        val (name, ids) = rawPacks(context).firstOrNull { it.first == id }?.second ?: return null
        val out = File(context.cacheDir, "shared").apply { mkdirs() }
            .resolve(name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Themes" } + ".dogtheme")
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val themes = JSONArray()
            for (t in ids) {
                val th = theme(context, t) ?: continue
                themes.put(JSONObject().put("id", t).put("name", th.name).put("kind", th.kind))
                for (f in listOf("theme.json", "photo.jpg", "analysis.txt")) {
                    val src = File(dir(context, t), f)
                    if (!src.isFile) continue
                    zip.putNextEntry(java.util.zip.ZipEntry("$t/$f")); src.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            zip.putNextEntry(java.util.zip.ZipEntry("pack.json"))
            zip.write(JSONObject().put("format", FORMAT).put("name", name).put("themes", themes).toString(1).toByteArray())
            zip.closeEntry()
        }
        return out
    }

    /**
     * A shared pack read from [input] and added as a new pack (its themes get new ids); its name, or null when the
     * file isn't a theme pack. A file from someone else: only the expected entries (pack.json, <id>/theme.json,
     * photo.jpg, analysis.txt with plain ids) are read, each at most [MAX_FILE], at most [MAX_THEMES] themes.
     */
    @Synchronized
    fun importPack(context: Context, input: java.io.InputStream): String? {
        val tmp = File(context.cacheDir, "import-" + System.currentTimeMillis()).apply { mkdirs() }
        try {
            var packJson: JSONObject? = null
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val bytes = readLimited(zip) ?: return null
                    when {
                        e.name == "pack.json" -> packJson = runCatching { JSONObject(String(bytes)) }.getOrNull()
                        ENTRY.matches(e.name) -> File(tmp, e.name).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                    }
                }
            }
            val pj = packJson ?: return null
            if (pj.optInt("format", 0) != FORMAT) return null
            val themes = pj.optJSONArray("themes") ?: return null
            val newIds = ArrayList<String>()
            for (i in 0 until minOf(themes.length(), MAX_THEMES)) {
                val old = themes.optJSONObject(i)?.optString("id").orEmpty()
                if (!Regex("^[a-z0-9]{1,24}$").matches(old)) continue
                val src = File(tmp, old)
                val meta = runCatching { JSONObject(File(src, "theme.json").readText()) }.getOrNull() ?: continue
                val gradient = meta.optString("kind") == "gradient"
                if (gradient) {
                    if (GradientSpec.parse(meta.optJSONObject("params")?.optString("spec").orEmpty()) == null) continue
                } else if (meta.optString("kind") != "photo" || !File(src, "photo.jpg").isFile) continue
                if (!gradient) {
                    // the photo must decode as an image
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(File(src, "photo.jpg").absolutePath, o)
                    if (o.outWidth <= 0) continue
                }
                val id = (if (gradient) "g" else "p") + System.currentTimeMillis().toString(36) + i
                // a gradient is its theme.json alone
                if (gradient) dir(context, id).apply { mkdirs() }.resolve("theme.json").writeText(meta.toString(1))
                else src.copyRecursively(dir(context, id), overwrite = true)
                newIds.add(id)
            }
            if (newIds.isEmpty()) return null
            val name = pj.optString("name").take(40).ifBlank { "Shared themes" }
            val packs = rawPacks(context)
            packs.add("k" + System.currentTimeMillis().toString(36) to (name to newIds))
            writePacks(context, packs)
            return name
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n; if (total > MAX_FILE) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** First run with saved themes: an existing photo becomes the first theme, "My photo". */
    suspend fun migrate(context: Context, prefs: FlorisPreferenceModel) {
        if (packsFile(context).isFile || !GlassPhoto.file(context).isFile || prefs.keyboard.glassPhotoStamp.get() == 0L) return
        createPhoto(context, prefs, "My photo")?.let { id -> prefs.keyboard.userTheme.set(id) }
    }
}

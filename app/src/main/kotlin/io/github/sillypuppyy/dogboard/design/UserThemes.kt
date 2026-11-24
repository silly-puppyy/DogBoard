package io.github.sillypuppyy.dogboard.design

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import java.io.File

/**
 * The user's own themes, kept in the user's own packs (notes/design/user-themes.md; photo themes
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
    data class Theme(val id: String, val name: String, val kind: String, val spec: String? = null, val credit: String? = null)
    /** [shared]: imported from someone else (read-only: edits go to a copy); [hidden]: left off the Themes page. */
    data class Pack(val id: String, val name: String, val themes: List<Theme>, val shared: Boolean = false, val hidden: Boolean = false)

    const val DEFAULT_PACK = "My themes"

    private fun root(context: Context) = File(context.filesDir, "dogboard/themes").apply { mkdirs() }
    private fun dir(context: Context, id: String) = File(root(context), id)
    private fun packsFile(context: Context) = File(root(context), "packs.json")

    // packs' flags beside packs.json (shared packs read-only, packs hidden from the Themes page)
    private fun flagsFile(context: Context) = File(root(context), "packflags.json")
    private fun flags(context: Context): JSONObject = runCatching { JSONObject(flagsFile(context).readText()) }.getOrDefault(JSONObject())
    private fun setFlag(context: Context, id: String, key: String, on: Boolean) {
        val f = flags(context)
        val o = f.optJSONObject(id) ?: JSONObject()
        f.put(id, o.put(key, on))
        flagsFile(context).writeText(f.toString(1))
    }

    @Synchronized
    fun setPackHidden(context: Context, id: String, hidden: Boolean) = setFlag(context, id, "hidden", hidden)

    /** A copy of [id]'s theme folder, a new theme id (its name kept). */
    private fun copyFolder(context: Context, id: String): String? {
        val src = dir(context, id).takeIf { it.isDirectory } ?: return null
        val nid = id.take(1) + System.currentTimeMillis().toString(36) + (0..999).random()
        src.copyRecursively(dir(context, nid), overwrite = true)
        return nid
    }

    /** Theme [id] (from a shared pack) copied into the user's default pack: the copy's id, to edit. */
    @Synchronized
    fun copyToMine(context: Context, id: String): String? {
        val nid = copyFolder(context, id) ?: return null
        addToPack(context, nid, null)
        return nid
    }

    /** Pack [id] copied as a pack of the user's own (" (copy)"), its themes copied. */
    @Synchronized
    fun copyPack(context: Context, id: String): String? {
        val packs = rawPacks(context)
        val (name, ids) = packs.firstOrNull { it.first == id }?.second ?: return null
        val nids = ids.mapNotNull { copyFolder(context, it) }
        val pid = "k" + System.currentTimeMillis().toString(36)
        packs.add(packs.indexOfFirst { it.first == id } + 1, pid to ("$name (copy)" to nids))
        writePacks(context, packs)
        return pid
    }

    /** Whether theme [id] is in a shared (read-only) pack. */
    fun isShared(context: Context, id: String): Boolean {
        val f = flags(context)
        return rawPacks(context).any { (pid, p) -> id in p.second && f.optJSONObject(pid)?.optBoolean("shared") == true }
    }
    fun photo(context: Context, id: String) = File(dir(context, id), "photo.jpg")

    @Synchronized
    fun packs(context: Context): List<Pack> {
        val f = packsFile(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val fl = flags(context)
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val ids = p.getJSONArray("themes")
                val pf = fl.optJSONObject(p.getString("id"))
                Pack(p.getString("id"), p.getString("name"), (0 until ids.length()).mapNotNull { j -> theme(context, ids.getString(j)) },
                    pf?.optBoolean("shared") == true, pf?.optBoolean("hidden") == true)
            }
        }.getOrDefault(emptyList())
    }

    fun theme(context: Context, id: String): Theme? = runCatching {
        val j = JSONObject(File(dir(context, id), "theme.json").readText())
        Theme(id, j.getString("name"), j.getString("kind"), j.optJSONObject("params")?.optString("spec")?.takeIf { GradientSpec.valid(it) || ThemeModel.decode(it) != null },
            j.optString("credit").takeIf { it.isNotEmpty() })
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

    /** A theme folder's files and the photo slot's: the photo and analysis, and the dark version's own when it has one. */
    private val SLOT_FILES = listOf(Triple("photo.jpg", false, true), Triple("analysis.txt", false, false), Triple("photo-dark.jpg", true, true), Triple("analysis-dark.txt", true, false))

    private fun slotFile(context: Context, dark: Boolean, photo: Boolean) = if (photo) GlassPhoto.file(context, dark) else GlassPhoto.analysisFile(context, dark)

    /** The photo slot's files into theme folder [d] (dark ones only when the dark version has its own photo). */
    private fun copySlotTo(context: Context, d: File, darkPhoto: Boolean) {
        for ((name, dark, photo) in SLOT_FILES) {
            val src = slotFile(context, dark, photo)
            if ((!dark || darkPhoto) && src.isFile) src.copyTo(File(d, name), overwrite = true) else if (dark) File(d, name).delete()
        }
    }

    /** A new photo theme from what the photo slot holds now, added to [pack] (the default pack when null). */
    @Synchronized
    fun createPhoto(context: Context, prefs: FlorisPreferenceModel, name: String, pack: String? = null): String? {
        if (!GlassPhoto.file(context).isFile) return null
        val id = "p" + System.currentTimeMillis().toString(36)
        val d = dir(context, id).apply { mkdirs() }
        val params = photoParams(context, prefs)
        copySlotTo(context, d, versionsOf(params, null).darkPhoto)
        File(d, "theme.json").writeText(JSONObject().put("name", name).put("kind", "photo").put("created", System.currentTimeMillis())
            .put("params", params).toString(1))
        addToPack(context, id, pack)
        return id
    }

    /** One version of a photo theme as its tile draws it: the keyboard as the theme sets it (Glass tokens). */
    data class PhotoLook(val dim: Float, val keyOpacity: Float, val luminance: Float, val labels: String, val halo: Boolean, val actColor: String?, val actFg: String?, val pos: String, val wash: Float = 0f, val sat: Float = 1f, val contrast: Float = 1f, val keyDark: Boolean = false)

    /** A photo theme's tile: its versions in order (bright first), each with the photo it draws (dark: maybe its own). */
    data class PhotoTile(val versions: List<Pair<PhotoLook, Boolean>>)

    /** A saved theme's versions; a theme saved before versions is read from its one look ([a]: its photo's analysis). */
    private fun versionsOf(p: JSONObject, a: GlassPhoto.Analysis?): PhotoVersions {
        p.optJSONObject("looks")?.let { return PhotoVersions.decode(it.toString()) }
        val old = PhotoReadability.Look(1 - p.optInt("dim", 35) / 100.0, p.optInt("keyOpacity", 30) / 100.0, p.optInt("blur", 12).toFloat(),
            p.optString("labels", "auto"), p.optBoolean("halo", true), p.optInt("accent", 0))
        return PhotoVersions.decode(null, old, a)
    }

    private fun analysisIn(d: File, dark: Boolean) = File(d, if (dark) "analysis-dark.txt" else "analysis.txt").takeIf { it.isFile }?.let { GlassPhoto.Analysis.decode(it.readText()) }

    fun photoTile(context: Context, id: String): PhotoTile? = runCatching {
        val d = dir(context, id)
        val p = JSONObject(File(d, "theme.json").readText()).getJSONObject("params")
        val v = versionsOf(p, analysisIn(d, false))
        val list = ArrayList<Pair<PhotoLook, Boolean>>()
        for (dark in listOf(false, true)) {
            if (if (dark) !v.hasDark else !v.hasBright) continue
            val own = dark && v.darkPhoto && File(d, "photo-dark.jpg").isFile
            val l = v.look(dark)
            val a = PhotoReadability.accent(l, analysisIn(d, own)?.accents)
            val lum = if (own) v.darkLuminance.toFloat() else p.optInt("luminance", 300) / 1000f
            list.add(PhotoLook(l.dim.toFloat(), l.tint.toFloat(), lum, l.labels, l.halo, a?.first, a?.second, p.optString("pos", "center"), l.wash.toFloat(), l.sat.toFloat(), l.contrast.toFloat(), l.keyDark) to own)
        }
        PhotoTile(list)
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

    /** A theme made in the theme editor ([spec]: its "te:" theme id) as a new theme in [pack]. */
    fun createEditor(context: Context, name: String, spec: String, pack: String? = null): String {
        val id = "e" + System.currentTimeMillis().toString(36)
        dir(context, id).apply { mkdirs() }.resolve("theme.json").writeText(JSONObject().put("name", name).put("kind", "editor")
            .put("created", System.currentTimeMillis()).put("params", JSONObject().put("spec", spec)).toString(1))
        addToPack(context, id, pack)
        return id
    }

    /** Gradient (or editor) theme [id] given new colours ([spec]) and [name]. */
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

    /** The photo slot's settings as a theme's params: its versions ("looks"), the photo's brightness and position. */
    private fun photoParams(context: Context, prefs: FlorisPreferenceModel): JSONObject = JSONObject()
        .put("looks", JSONObject(PhotoSlot.versions(context, prefs).encode()))
        .put("luminance", prefs.keyboard.glassPhotoLuminance.get()).put("pos", prefs.keyboard.glassPhotoPos.get())

    /** Loads photo theme [id] into the photo slot the keyboard draws, and marks it as the one being used. */
    suspend fun apply(context: Context, prefs: FlorisPreferenceModel, id: String): Boolean {
        val d = dir(context, id)
        val j = runCatching { JSONObject(File(d, "theme.json").readText()) }.getOrNull() ?: return false
        if (j.getString("kind") != "photo") return false
        val p = j.getJSONObject("params")
        val v = versionsOf(p, analysisIn(d, false))
        for ((name, dark, photo) in SLOT_FILES) {
            val src = File(d, name)
            if (src.isFile && (!dark || v.darkPhoto)) src.copyTo(slotFile(context, dark, photo), overwrite = true) else if (dark) slotFile(context, dark, photo).delete()
        }
        prefs.keyboard.glassLooks.set(v.encode())
        ThemeChoice.glassMode = v.mode
        prefs.keyboard.glassPhotoLuminance.set(p.optInt("luminance", 300)); prefs.keyboard.glassPhotoPos.set(p.optString("pos", "center"))
        prefs.keyboard.userTheme.set(id)
        prefs.keyboard.glassEnabled.set(true)
        prefs.keyboard.glassPhotoStamp.set(System.currentTimeMillis())
        return true
    }

    /** What a photo theme's preview changed, to put back ([previewEnd]): the slot's files and settings. */
    class PreviewSnapshot(
        val dir: File, val userTheme: String, val enabled: Boolean, val looks: String, val lum: Int, val pos: String,
        val stamp: Long, val mode: String?,
    )

    /**
     * Holding a photo theme: it goes into the photo slot for a while, so the real keyboard shows it (as a built-in's
     * sheet does); the slot's files and settings are kept aside first. Null when [id] isn't a photo theme.
     */
    suspend fun previewStart(context: Context, prefs: FlorisPreferenceModel, id: String): PreviewSnapshot? {
        val back = File(context.cacheDir, "theme-preview").apply { deleteRecursively(); mkdirs() }
        for ((name, dark, photo) in SLOT_FILES) slotFile(context, dark, photo).takeIf { it.isFile }?.copyTo(File(back, name), overwrite = true)
        val k = prefs.keyboard
        val snap = PreviewSnapshot(back, k.userTheme.get(), k.glassEnabled.get(), k.glassLooks.get(), k.glassPhotoLuminance.get(),
            k.glassPhotoPos.get(), k.glassPhotoStamp.get(), ThemeChoice.glassMode)
        return if (apply(context, prefs, id)) snap else { back.deleteRecursively(); null }
    }

    /** The preview is over: the slot as it was, unless the theme was taken ([keep]). */
    suspend fun previewEnd(context: Context, prefs: FlorisPreferenceModel, snap: PreviewSnapshot, keep: Boolean) {
        if (!keep) {
            for ((name, dark, photo) in SLOT_FILES) {
                val f = File(snap.dir, name)
                if (f.isFile) f.copyTo(slotFile(context, dark, photo), overwrite = true) else slotFile(context, dark, photo).delete()
            }
            val k = prefs.keyboard
            k.userTheme.set(snap.userTheme); k.glassEnabled.set(snap.enabled); k.glassLooks.set(snap.looks)
            k.glassPhotoLuminance.set(snap.lum); k.glassPhotoPos.set(snap.pos)
            snap.mode?.let { ThemeChoice.glassMode = it }
            // a new stamp: the keyboard reloads the slot's photo (the old stamp would keep the preview's in memory)
            k.glassPhotoStamp.set(if (snap.stamp == 0L) 0L else System.currentTimeMillis())
        }
        snap.dir.deleteRecursively()
    }

    /** The photo slot's settings (and photo, when [photoToo]) stored back into the theme in use. */
    @Synchronized
    fun saveBack(context: Context, prefs: FlorisPreferenceModel, photoToo: Boolean = false) {
        val id = prefs.keyboard.userTheme.get().takeIf { it.isNotEmpty() } ?: return
        val d = dir(context, id)
        val f = File(d, "theme.json")
        val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        val params = photoParams(context, prefs)
        f.writeText(j.put("params", params).toString(1))
        if (photoToo) copySlotTo(context, d, versionsOf(params, null).darkPhoto)
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

    // sharing (packs "become easily shareable"): a pack is one .dogtheme file, a zip of pack.json
    // (format, name, its themes in order) and each theme's folder (theme.json, photo.jpg, analysis.txt)
    private const val FORMAT = 1
    private val ENTRY = Regex("^[a-z0-9]{1,24}/(theme\\.json|photo(-dark)?\\.jpg|analysis(-dark)?\\.txt)$")
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
                for (f in listOf("theme.json", "photo.jpg", "analysis.txt", "photo-dark.jpg", "analysis-dark.txt")) {
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
                    if (!GradientSpec.valid(meta.optJSONObject("params")?.optString("spec").orEmpty())) continue
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
            val pid = "k" + System.currentTimeMillis().toString(36)
            packs.add(pid to (name to newIds))
            setFlag(context, pid, "shared", true) // someone else's pack: read-only, edits make a copy
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

    /**
     * The built-in photos (assets dogboard/photos, chosen with ) as a pack "Photos", once: each
     * analysed and matched for readability as a picked photo is, so they edit, move and share like the user's
     * own. A marker keeps a deleted pack deleted.
     */
    @Synchronized
    fun installPhotos(context: Context) {
        val marker = File(root(context), ".photos-1")
        if (marker.isFile) return
        val list = runCatching { org.json.JSONArray(context.assets.open("dogboard/photos/photos.json").bufferedReader().readText()) }.getOrNull() ?: return
        val ids = ArrayList<String>()
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i)
            val bmp = context.assets.open("dogboard/photos/${p.getString("slug")}.jpg").use { android.graphics.BitmapFactory.decodeStream(it) } ?: continue
            val a = GlassPhoto.analyse(bmp)
            // the look tuned by hand (photos.json "looks", pulled from a phone), else matched to the photo
            val v = p.optJSONObject("looks")?.let { PhotoVersions.decode(it.toString()) } ?: PhotoVersions.auto(a)
            val id = "p" + System.currentTimeMillis().toString(36) + i
            val d = dir(context, id).apply { mkdirs() }
            context.assets.open("dogboard/photos/${p.getString("slug")}.jpg").use { inp -> File(d, "photo.jpg").outputStream().use { inp.copyTo(it) } }
            File(d, "analysis.txt").writeText(a.encode())
            val params = JSONObject().put("looks", JSONObject(v.encode())).put("luminance", (GlassPhoto.luminance(bmp) * 1000).roundToInt()).put("pos", p.optString("pos", "center"))
            val credit = p.getString("by") + ", " + if (p.getString("source") == "Unsplash") "Unsplash" else p.getString("license")
            File(d, "theme.json").writeText(JSONObject().put("name", p.getString("name")).put("kind", "photo").put("created", System.currentTimeMillis())
                .put("credit", credit).put("params", params).toString(1))
            ids.add(id)
        }
        if (ids.isNotEmpty()) {
            val packs = rawPacks(context)
            packs.add("k" + System.currentTimeMillis().toString(36) + "ph" to ("Photos" to ids))
            writePacks(context, packs)
        }
        marker.writeText("1")
    }

    /** First run with saved themes: an existing photo becomes the first theme, "My photo". */
    suspend fun migrate(context: Context, prefs: FlorisPreferenceModel) {
        if (packsFile(context).isFile || !GlassPhoto.file(context).isFile || prefs.keyboard.glassPhotoStamp.get() == 0L) return
        createPhoto(context, prefs, "My photo")?.let { id -> prefs.keyboard.userTheme.set(id) }
    }
}

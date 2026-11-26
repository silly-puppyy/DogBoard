package io.github.sillypuppyy.dogboard.ime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Dictionaries that arrive in the background (Gboard's way, adding a language never waits on a download).
 * A language is added at once and types with its layout; its pack downloads here, outliving the settings screen, and
 * loads into the engine when that language is in use. A failed download tries again after a while, and again whenever
 * the Languages page is opened ([ensure]); there is no error screen.
 */
object PackDownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One install at a time: each holds an xz decoder, a download and its checks; several languages at once (a first
     * start, or updates of every installed pack) ran the app out of heap (2026-11-24).
     */
    private val installLock = kotlinx.coroutines.sync.Mutex()
    private val _index = MutableStateFlow<PackIndex?>(null)
    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    /** The host's packs (null until fetched, or when there is no host). */
    val index: StateFlow<PackIndex?> = _index.asStateFlow()

    /** Pack tags downloading or waiting to retry ("Getting dictionary"). */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    /** How far each downloading pack is (0..1), for its row on the Languages page; absent while waiting to retry. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Bumped when a pack was installed, so screens re-read the installed packs. */
    private val _installed = MutableStateFlow(0)
    val installed: StateFlow<Int> = _installed.asStateFlow()

    /** The host's pack for a language tag ("it-IT" or "it"), or null. */
    fun offered(tag: String): PackIndexEntry? {
        val packs = _index.value?.packs ?: return null
        return packs.firstOrNull { it.tag.equals(tag, true) } ?: packs.firstOrNull { it.tag.equals(tag.substringBefore('-'), true) }
    }

    /** Fetches the host's index (once a session unless [force]). */
    fun refreshIndex(context: Context, force: Boolean = false) {
        if (_index.value != null && !force) return
        val downloads = PackDownloads.of(context.applicationContext)
        if (!downloads.enabled) return
        scope.launch { downloads.index()?.let { _index.value = it } }
    }

    /**
     * At app start: the packs every keyboard's languages need and the phone lacks (none on the phone, or a built-in one
     * without its word statistics or neural model, as English since 1.2.4), fetched. Nothing is asked of the host when
     * every pack is in place.
     */
    fun ensureKeyboards(context: Context) {
        val app = context.applicationContext
        val subtypes = dev.patrickgold.florisboard.FlorisApplication.getHack().subtypeManager.value.subtypes
        val tags = subtypes.flatMap { s -> listOf(s.primaryLocale) + s.secondaryLocales }.map { it.languageTag() }.distinct()
        // off the main thread: the lookups read the packs' manifests
        scope.launch {
            val missing = tags.filter { t ->
                val have = LanguagePack.find(app, t)?.takeIf { it.hasWords }
                have == null || (have.bundled && (!have.has("ngrams") || !have.has("lm_model")))
            }
            if (missing.isNotEmpty()) ensure(app, missing)
        }
    }

    /** Downloads the packs of [tags] that the host offers and the phone lacks (the languages the user has added). */
    fun ensure(context: Context, tags: Collection<String>) {
        val app = context.applicationContext
        scope.launch {
            if (_index.value == null) PackDownloads.of(app).index()?.let { _index.value = it }
            for (raw in tags) {
                // the tag as a pack folder ("en-US-u-fw-mon": the system's regional preferences, LanguagePack.candidates)
                val tag = LanguagePack.candidates(raw).firstOrNull() ?: raw
                val entry = offered(tag) ?: continue
                // a pack on the phone is fetched again only when the host has a newer version of it (a downloaded one:
                // a built-in pack updates with the app)
                val have = LanguagePack.find(app, entry.tag)?.takeIf { it.hasWords }
                // a built-in pack without its big optional files (a build from source: the English trigram and neural
                // model aren't in git) takes the hosted pack, which has them
                val lacking = have != null && have.bundled && (!have.has("ngrams") || !have.has("lm_model"))
                if (have != null && !lacking && (have.bundled || _index.value?.let { PackDownloads.of(app).updateFor(have, it) } == null)) continue
                start(app, entry)
            }
        }
    }

    /**
     * About > Check for updates now: the host's index fetched again, then every downloaded pack with a newer version
     * there updated (one at a time, in the background), and a built-in pack missing its big files completed. The
     * number of packs started; -1 when the host couldn't be reached.
     */
    suspend fun updateAll(context: Context): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val app = context.applicationContext
        val downloads = PackDownloads.of(app)
        if (!downloads.enabled) return@withContext 0
        val index = downloads.index() ?: return@withContext -1
        _index.value = index
        // per hosted pack, the pack the phone would use for its language (a downloaded one first): a built-in region
        // pack with rules only (fr-FR) must not fetch French again when the downloaded French is current
        val entries = LanguagePack.all(app).mapNotNull { offered(it.tag) }.distinctBy { it.tag }
        var n = 0
        for (entry in entries) {
            val have = LanguagePack.find(app, entry.tag) ?: continue
            val due = if (have.bundled) !have.has("ngrams") || !have.has("lm_model") else downloads.updateFor(have, index) != null
            if (due) { start(app, entry); n++ }
        }
        n
    }

    private fun start(app: Context, entry: PackIndexEntry) {
        var claimed = false
        _pending.update { if (entry.tag in it) it else { claimed = true; it + entry.tag } }
        if (!claimed) return
        scope.launch {
            try {
                for ((attempt, wait) in RETRY_WAITS_MS.withIndex()) {
                    if (attempt > 0) delay(wait)
                    val shade = io.github.sillypuppyy.dogboard.settings.DownloadNotice(
                        app, "Downloading ${entry.name}", io.github.sillypuppyy.dogboard.settings.DownloadNotice.PACK_ID)
                    val r = try {
                        installLock.withLock {
                            _progress.update { it + (entry.tag to 0f) }
                            PackDownloads.of(app).install(entry.tag, entry = entry, onProgress = { done, total ->
                                if (total > 0) {
                                    val f = done.toFloat() / total
                                    // in whole per cent: a state update per buffer recomposed the page thousands of times
                                    if ((f * 100).toInt() != ((_progress.value[entry.tag] ?: 0f) * 100).toInt()) _progress.update { it + (entry.tag to f) }
                                    shade.progress(f)
                                }
                            })
                        }
                    } finally {
                        _progress.update { it - entry.tag }
                        shade.done()
                    }
                    if (r is PackInstaller.Result.Installed) {
                        io.github.sillypuppyy.dogboard.perf.EventLog.add("pack ${r.tag} ${r.version} installed in the background")
                        _installed.update { it + 1 }
                        // in use now: load it (the language typed with its layout alone until here), or load it again (updated)
                        val active = dev.patrickgold.florisboard.FlorisApplication.getHack().subtypeManager.value.activeSubtype
                        runCatching {
                            if (!EngineLoader.reloadIfLoaded(app, entry.tag) &&
                                active.primaryLocale.languageTag().substringBefore('-').equals(entry.tag.substringBefore('-'), true)
                            ) EngineLoader.switchTo(app, active.primaryLocale.languageTag())
                        }.onFailure { io.github.sillypuppyy.dogboard.perf.EventLog.add("pack ${entry.tag}: loading after install failed: ${it.javaClass.simpleName} ${it.message}") }
                        return@launch
                    }
                    io.github.sillypuppyy.dogboard.perf.EventLog.add("pack ${entry.tag}: attempt ${attempt + 1} failed: ${(r as PackInstaller.Result.Failed).reason}")
                }
            } finally {
                _pending.update { it - entry.tag }
            }
        }
    }

    /** Before each attempt: now, then after half a minute, then after five (the page tries again whenever it is opened). */
    private val RETRY_WAITS_MS = listOf(0L, 30_000L, 300_000L)
}

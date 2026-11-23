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

/**
 * Dictionaries that arrive in the background (Gboard's way, user 2026-11-23: adding a language never waits on a download).
 * A language is added at once and types with its layout; its pack downloads here, outliving the settings screen, and
 * loads into the engine when that language is in use. A failed download tries again after a while, and again whenever
 * the Languages page is opened ([ensure]); there is no error screen.
 */
object PackDownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _index = MutableStateFlow<PackIndex?>(null)
    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    /** The host's packs (null until fetched, or when there is no host). */
    val index: StateFlow<PackIndex?> = _index.asStateFlow()

    /** Pack tags downloading or waiting to retry ("Getting dictionary"). */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

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

    /** Downloads the packs of [tags] that the host offers and the phone lacks (the languages the user has added). */
    fun ensure(context: Context, tags: Collection<String>) {
        val app = context.applicationContext
        scope.launch {
            if (_index.value == null) PackDownloads.of(app).index()?.let { _index.value = it }
            for (tag in tags) {
                val entry = offered(tag) ?: continue
                if (LanguagePack.find(app, entry.tag)?.hasWords == true) continue
                start(app, entry)
            }
        }
    }

    private fun start(app: Context, entry: PackIndexEntry) {
        var claimed = false
        _pending.update { if (entry.tag in it) it else { claimed = true; it + entry.tag } }
        if (!claimed) return
        scope.launch {
            try {
                for ((attempt, wait) in RETRY_WAITS_MS.withIndex()) {
                    if (attempt > 0) delay(wait)
                    val r = PackDownloads.of(app).install(entry.tag)
                    if (r is PackInstaller.Result.Installed) {
                        android.util.Log.i("DogBoardPerf", "pack ${r.tag} ${r.version} installed in the background")
                        _installed.update { it + 1 }
                        // in use now: load it (the language typed with its layout alone until here)
                        val active = dev.patrickgold.florisboard.FlorisApplication.getHack().subtypeManager.value.activeSubtype.primaryLocale.languageTag()
                        if (active.substringBefore('-').equals(entry.tag.substringBefore('-'), true)) EngineLoader.switchTo(app, active)
                        return@launch
                    }
                    android.util.Log.w("DogBoardPerf", "pack ${entry.tag}: attempt ${attempt + 1} failed: ${(r as PackInstaller.Result.Failed).reason}")
                }
            } finally {
                _pending.update { it - entry.tag }
            }
        }
    }

    /** Before each attempt: now, then after half a minute, then after five (the page tries again whenever it is opened). */
    private val RETRY_WAITS_MS = listOf(0L, 30_000L, 300_000L)
}

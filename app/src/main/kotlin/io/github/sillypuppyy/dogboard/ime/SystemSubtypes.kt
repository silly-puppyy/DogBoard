package io.github.sillypuppyy.dogboard.ime

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import dev.patrickgold.florisboard.ime.core.Subtype

/**
 * DogBoard's keyboards as Android input method subtypes, so the system's switcher under the keys (the navigation-bar
 * globe) goes through them as it does through Gboard's languages: Android 16 steps to the next subtype on a tap,
 * Android 15 lists them in its picker. Each subtype carries its keyboard's id in its extra value ([idOf]); the IME
 * hears a switch in onCurrentInputMethodSubtypeChanged. AOSP: InputMethodManagerService.onImeSwitchButtonClickLocked,
 * NavigationBar.onImeSwitcherClick (android15/16-release).
 */
object SystemSubtypes {
    private const val KEY = "DogBoardId="

    /**
     * The subtype Android gets for keyboard [s] in the system's "Change keyboard" menu (the one Gboard's space bar
     * opens too): the languages in their own names, a short region ("English (US) + français"); the layout under it
     * only when [showLayout] (another keyboard has the same languages). Tagged with the keyboard's id.
     */
    fun of(s: Subtype, showLayout: Boolean = false, regions: Set<String> = emptySet()): InputMethodSubtype {
        val name = nameOf(s, regions)
        val layoutId = s.layoutId ?: LanguageLayouts.layoutOf(s.primaryLocale.language, "")
        val sub = if (showLayout) GeneratedLayouts.all.firstOrNull { it.id == layoutId }?.label.orEmpty() else ""
        // Latin-script keyboards only: the system looks for an ASCII-capable one for passwords and addresses
        val latin = android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale.forLanguageTag(s.primaryLocale.languageTag())).script == "Latn"
        val b = InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeNameOverride(name)
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue((if (latin) "AsciiCapable," else "") + "$KEY${s.id}")
            .setSubtypeId(("dogboard:" + s.id).hashCode())
            .setIsAsciiCapable(latin)
            .setIsAuxiliary(false)
            .setOverridesImplicitlyEnabledSubtype(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) b.setLanguageTag(s.primaryLocale.languageTag())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && sub.isNotEmpty()) b.setLayoutLabelNonLocalized(sub)
        // the code beside the entry (newer Androids; a missing method on older ones is skipped)
        try { b.setSubtypeShortLabel(shortLabel(s)) } catch (_: Throwable) {}
        @Suppress("DEPRECATION") b.setSubtypeLocale(s.primaryLocale.languageTag().replace('-', '_'))
        return b.build()
    }

    /**
     * A keyboard's name, each language in its own name as Gboard writes them ("English + Français", "Български"); a
     * language's region only when it is in [regions] (two keyboards have it with different regions: "Português (Brasil)").
     */
    fun nameOf(s: Subtype, regions: Set<String> = emptySet()): String =
        (listOf(s.primaryLocale) + s.secondaryLocales).joinToString(" + ") { nativeName(it.languageTag(), it.language in regions) }

    /** A language in its own name ("Français", "Български"); its region only when [showRegion] (a long one as its code). */
    fun nativeName(tag: String, showRegion: Boolean): String {
        val l = java.util.Locale.forLanguageTag(tag)
        val lang = l.getDisplayLanguage(l).replaceFirstChar { it.uppercase(l) }
        val region = l.country.takeIf { it.isNotEmpty() && showRegion }?.let { c -> l.getDisplayCountry(l).takeIf { it.length <= 12 } ?: c }
        return if (region != null) "$lang ($region)" else lang
    }

    /** Languages (by tag) that appear with two regions across [list]'s keyboards: those show their region. */
    fun twoRegions(list: List<Subtype>): Set<String> = list.flatMap { listOf(it.primaryLocale) + it.secondaryLocales }
        .map { it.languageTag() }.distinct().groupBy { it.substringBefore('-') }.filterValues { it.size > 1 }.keys

    /** The short code beside a keyboard in Android's menu, as Gboard's (US, FR, БГ there): the badge's ("EN", "EN•FR"). */
    fun shortLabel(s: Subtype): String = (listOf(s.primaryLocale) + s.secondaryLocales).joinToString("\n") { it.language.uppercase() }

    /** Keyboards sharing their languages with another one: those show their layout to tell them apart. */
    fun sameLanguages(list: List<Subtype>): Set<Long> =
        list.groupBy { (listOf(it.primaryLocale.language) + it.secondaryLocales.map { l -> l.language }).sorted() }.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()

    /** The DogBoard keyboard id a system subtype stands for, or null for another subtype. */
    fun idOf(sub: InputMethodSubtype?): Long? =
        sub?.extraValue?.split(',')?.firstOrNull { it.startsWith(KEY) }?.removePrefix(KEY)?.toLongOrNull()

    /** This IME's id as Android knows it, or null. */
    fun imeId(context: Context): String? =
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList?.firstOrNull { it.packageName == context.packageName }?.id

    /** Publishes [list] as this IME's subtypes and enables exactly those (Android 14+), so the switcher cycles them. */
    fun publish(context: Context, list: List<Subtype>) {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        val id = imeId(context) ?: return
        val twins = sameLanguages(list); val regions = twoRegions(list)
        val subs = list.map { of(it, it.id in twins, regions) }.toTypedArray()
        runCatching {
            @Suppress("DEPRECATION") imm.setAdditionalInputMethodSubtypes(id, subs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) imm.setExplicitlyEnabledInputMethodSubtypes(id, subs.map { it.hashCode() }.toIntArray())
            android.util.Log.i("DogBoardPerf", "system subtypes: ${subs.size} published for $id")
        }.onFailure { android.util.Log.w("DogBoardPerf", "system subtypes: $it") }
    }
}

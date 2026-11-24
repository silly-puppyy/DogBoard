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
     * The subtype Android gets for keyboard [s], as Gboard's read in the system's "Change keyboard" menu: the language
     * in its own name ("Español (España)"), the layout under it, with a combined keyboard's other languages after it
     * ("QWERTY (ñ), with English (US)"); tagged with the keyboard's id.
     */
    fun of(s: Subtype): InputMethodSubtype {
        val name = s.primaryLocale.displayName(s.primaryLocale).replaceFirstChar { it.uppercase() }
        // the entry's own layout, else its language's usual one
        val layoutId = s.layoutId ?: LanguageLayouts.layoutOf(s.primaryLocale.language, "")
        val layout = GeneratedLayouts.all.firstOrNull { it.id == layoutId }?.label
        // the other languages without their region, so the line fits ("with English")
        val others = s.secondaryLocales.map { l -> dev.patrickgold.florisboard.lib.FlorisLocale.from(l.language).let { it.displayName(it) }.replaceFirstChar { c -> c.uppercase() } }
        val with = if (others.isEmpty()) "" else "with " + (if (others.size == 1) others[0] else others.dropLast(1).joinToString(", ") + " and " + others.last())
        val sub = listOfNotNull(layout, with.takeIf { it.isNotEmpty() }).joinToString(", ")
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
        @Suppress("DEPRECATION") b.setSubtypeLocale(s.primaryLocale.languageTag().replace('-', '_'))
        return b.build()
    }

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
        val subs = list.map { of(it) }.toTypedArray()
        runCatching {
            @Suppress("DEPRECATION") imm.setAdditionalInputMethodSubtypes(id, subs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) imm.setExplicitlyEnabledInputMethodSubtypes(id, subs.map { it.hashCode() }.toIntArray())
            android.util.Log.i("DogBoardPerf", "system subtypes: ${subs.size} published for $id")
        }.onFailure { android.util.Log.w("DogBoardPerf", "system subtypes: $it") }
    }
}

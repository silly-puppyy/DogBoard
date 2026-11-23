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

    /** The subtype Android gets for keyboard [s]: named by its languages, tagged with its id. */
    fun of(s: Subtype): InputMethodSubtype {
        val names = (listOf(s.primaryLocale) + s.secondaryLocales).map { it.displayName() }
        val name = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
        val b = InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeNameOverride(name)
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue("AsciiCapable,$KEY${s.id}")
            .setSubtypeId(("dogboard:" + s.id).hashCode())
            .setIsAsciiCapable(true)
            .setIsAuxiliary(false)
            .setOverridesImplicitlyEnabledSubtype(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) b.setLanguageTag(s.primaryLocale.languageTag())
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

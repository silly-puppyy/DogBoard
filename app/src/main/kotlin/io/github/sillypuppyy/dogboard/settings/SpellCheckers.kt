package io.github.sillypuppyy.dogboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.textservice.TextServicesManager

/**
 * The phone's spell checker, one for every keyboard (Settings > System > Languages > Spell checker): DogBoard being the
 * keyboard doesn't make it the spell checker, and Pixels default to Google's in the phone's languages (a Bulgarian
 * keyboard had every word underlined, 2026-11-25).
 */
object SpellCheckers {
    /** [enabled]: spell checking is on; [ours]: DogBoard's checker is the one picked; [name] and [pkg]: the picked one's. */
    data class Current(val enabled: Boolean, val ours: Boolean, val name: String, val pkg: String)

    /** Null below Android 12, which has no public way to read it. */
    fun current(context: Context): Current? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val tsm = context.getSystemService(TextServicesManager::class.java) ?: return null
        val info = tsm.currentSpellCheckerInfo
        val pkg = info?.packageName.orEmpty()
        val name = info?.loadLabel(context.packageManager)?.toString().orEmpty().ifEmpty { "another app" }
        return Current(tsm.isSpellCheckerEnabled && info != null, pkg == context.packageName, name, pkg)
    }

    /** Android's spell checker page; where a phone hides it, its keyboard settings (the page that links to it). */
    fun open(context: Context) {
        val page = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity"))
        val fallback = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        for (intent in listOf(page, fallback)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
}

package io.github.sillypuppyy.dogboard.apps

import android.content.Context
import android.content.Intent

/**
 * Names of the apps on the phone as words the keyboard knows (iOS adds installed app names to its
 * dynamic dictionary, entry type 2): "Duolingo", "Spotify", "WhatsApp" are never corrected. Read from
 * the launcher's app list into memory only; nothing is stored or sent.
 */
object AppNames {
    /** Single words of the launcher apps' labels (letters, apostrophes, hyphens), 2-32 characters. */
    fun read(context: Context): Set<String> {
        val words = HashSet<String>()
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val label = info.loadLabel(pm)?.toString() ?: continue
                for (w in label.split(' ', ' ', ':', '-', '&', '+', '.', ',', '(', ')')) {
                    val word = w.trim()
                    if (word.length in 2..32 && word.all { it.isLetter() || it == '\'' || it == '’' }) words.add(word)
                }
            }
        }
        return words
    }
}

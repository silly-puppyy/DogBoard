package io.github.sillypuppyy.dogboard.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * Names from the user's contacts as words the keyboard knows (iOS suggests contact names).
 * Opt-in: only with the "use contacts" setting on and the contacts permission granted (asked in
 * onboarding). Names are read into memory only; DogBoard never writes them anywhere.
 */
object ContactNames {
    fun isAllowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Single-word first and last names (letters, apostrophes, hyphens), as written in contacts. */
    fun read(context: Context): Set<String> {
        if (!isAllowed(context)) return emptySet()
        val names = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        runCatching {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    for (i in projection.indices) {
                        val name = c.getString(i)?.trim() ?: continue
                        if (name.length in 2..32 && name.all { it.isLetter() || it == '\'' || it == '’' || it == '-' }) names.add(name)
                    }
                }
            }
        }
        return names
    }
}

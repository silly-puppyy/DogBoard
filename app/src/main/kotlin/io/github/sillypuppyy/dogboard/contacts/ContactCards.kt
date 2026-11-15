package io.github.sillypuppyy.dogboard.contacts

import android.content.Context
import android.provider.ContactsContract
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One contact for the bar's contact chips: full name, primary phone number and birthday (as written). */
data class ContactCard(val name: String, val phone: String?, val birthday: String?)

/**
 * The designer's contact chips (More board, Smart chips: "Typing a contact's name: with contacts
 * allowed, the full name, their number and their birthday as chips. Off by default"). Same opt-in as
 * [ContactNames]: the "use contacts" setting and the contacts permission; read into memory only.
 */
object ContactCards {
    /** Contacts by lowercase first name (and by lowercase single-word nickname-like display name). */
    fun read(context: Context): Map<String, List<ContactCard>> {
        if (!ContactNames.isAllowed(context)) return emptyMap()
        data class Acc(var name: String? = null, var given: String? = null, var phone: String? = null, var primary: Boolean = false, var birthday: String? = null)
        val byId = HashMap<Long, Acc>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2, ContactsContract.Data.IS_SUPER_PRIMARY, ContactsContract.Data.DISPLAY_NAME,
        )
        val types = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)"
        runCatching {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, types, null)?.use { c ->
                while (c.moveToNext()) {
                    val acc = byId.getOrPut(c.getLong(0)) { Acc() }
                    acc.name = acc.name ?: c.getString(5)
                    when (c.getString(1)) {
                        types[0] -> acc.given = c.getString(3) ?: acc.given // StructuredName.GIVEN_NAME = DATA2
                        types[1] -> {
                            val primary = c.getInt(4) != 0
                            if (acc.phone == null || (primary && !acc.primary)) { acc.phone = c.getString(2); acc.primary = primary }
                        }
                        types[2] -> if (c.getInt(3) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) acc.birthday = birthday(c.getString(2))
                    }
                }
            }
        }
        val out = HashMap<String, MutableList<ContactCard>>()
        for (a in byId.values) {
            val name = a.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val first = (a.given ?: name.substringBefore(' ')).trim().lowercase()
            if (first.length >= 2) out.getOrPut(first) { ArrayList() }.add(ContactCard(name, a.phone?.trim(), a.birthday))
        }
        return out
    }

    /** "1994-03-14" → "14 March 1994", "--12-03" (no year) → "3 December"; else as stored. */
    private fun birthday(raw: String?): String? {
        val s = raw?.trim() ?: return null
        return runCatching {
            if (s.startsWith("--")) MonthDay.parse(s).let { it.atYear(2000).format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())) }
            else LocalDate.parse(s.take(10)).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        }.getOrDefault(s)
    }
}

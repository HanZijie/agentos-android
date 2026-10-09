package org.agentos.sample.calendar.debug

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars

/**
 * Debug builds only: fake account calendars in the system calendar database, so that the account paths (source, writable, default
 * write calendar, delete / update refusal, sync-style rows) can be exercised on an emulator that has no account.
 *
 * **Emulator only.** On a real phone the system calendar database holds the user's real events; every function here refuses to run
 * there. All fixture calendars use an account name that starts with [ACCOUNT_PREFIX] (fake, `example.com`), so they can be found and
 * removed again without touching anything else.
 */
object ProviderFixtures {
    const val ACCOUNT_PREFIX = "agentos-test"

    fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.PRODUCT.startsWith("sdk_") || Build.FINGERPRINT.contains("emulator")

    private fun require() {
        check(isEmulator()) { "provider fixtures run on an emulator only (this is ${Build.MODEL} / ${Build.HARDWARE})" }
    }

    private fun syncAdapterUri(accountName: String, accountType: String) = Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType)
        .build()

    /** Creates a calendar of the given account type (needs the sync-adapter flag; the provider does not check who the caller is). Returns its `_id`. */
    fun createCalendar(context: Context, displayName: String, accountName: String, accountType: String, accessLevel: Int, color: Int = 0xFF4A7BDB.toInt(), visible: Boolean = true): Long {
        require()
        check(accountName.startsWith(ACCOUNT_PREFIX)) { "fixture accounts must start with $ACCOUNT_PREFIX" }
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, accountType)
            put(Calendars.NAME, displayName)
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(Calendars.CALENDAR_COLOR, color)
            put(Calendars.CALENDAR_ACCESS_LEVEL, accessLevel)
            put(Calendars.VISIBLE, if (visible) 1 else 0)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, "Asia/Shanghai")
            put(Calendars.OWNER_ACCOUNT, accountName)
        }
        val uri = context.contentResolver.insert(syncAdapterUri(accountName, accountType), v) ?: error("provider returned no uri")
        return ContentUris.parseId(uri)
    }

    /** Removes every fixture calendar (and with it their events). Returns the number of calendars removed. */
    fun deleteTestCalendars(context: Context): Int {
        require()
        val accounts = LinkedHashSet<Pair<String, String>>()
        context.contentResolver.query(
            Calendars.CONTENT_URI, arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
            "${Calendars.ACCOUNT_NAME} LIKE ?", arrayOf("$ACCOUNT_PREFIX%"), null,
        )?.use { c -> while (c.moveToNext()) accounts += c.getString(0) to c.getString(1) }
        var n = 0
        for ((name, type) in accounts) n += context.contentResolver.delete(syncAdapterUri(name, type), null, null)
        return n
    }

    fun fixtureCalendarCount(context: Context): Int =
        context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), "${Calendars.ACCOUNT_NAME} LIKE ?", arrayOf("$ACCOUNT_PREFIX%"), null)?.use { it.count } ?: 0
}

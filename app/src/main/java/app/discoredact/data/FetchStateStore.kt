package app.discoredact.data

import android.content.Context
import android.content.SharedPreferences
import app.discoredact.core.history.FetchSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Fetch screen shows about the current fetch, besides the progress rows. */
data class FetchView(
    val snapshot: FetchSnapshot,
    /** When this fetch was begun ("Start fetch"). */
    val createdAt: Long,
    /** The account's own messages this fetch found, new or already present. */
    val found: Int,
)

/**
 * The fetch's own state, in the settings file (excluded from backup and device transfer like everything else, and
 * cleared by Wipe everything with it): the current fetch's times and stop reason, the account id `GET /users/@me`
 * answered with (not a secret, but never printed), and the day's read count for the daily cap. Every write is
 * committed before it returns. The per-DM progress lives in Room (`fetch_progress`).
 */
class FetchStateStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<FetchView?> = _state.asStateFlow()

    private fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun read(): FetchView? {
        val created = long(K_CREATED) ?: return null
        val snapshot = FetchSnapshot(
            startedAt = long(K_STARTED),
            endedAt = long(K_ENDED),
            stopReason = prefs.getString(K_REASON, null),
            resumeNotBefore = long(K_NOT_BEFORE),
            accountId = long(K_ACCOUNT),
            accountCapturedAt = long(K_ACCOUNT_CAPTURED),
            listedAt = long(K_LISTED),
        )
        return FetchView(snapshot, created, prefs.getInt(K_FOUND, 0))
    }

    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.change()
        check(editor.commit()) { "the fetch state could not be written" }
        _state.value = read()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    @Synchronized fun snapshot(): FetchSnapshot? = _state.value?.snapshot

    /** A new fetch: every key of the last one cleared (its account id too), the day's read count kept. */
    @Synchronized fun begin(now: Long) = write {
        for (k in FETCH_KEYS) remove(k)
        putLong(K_CREATED, now)
        putInt(K_FOUND, 0)
    }

    @Synchronized fun started(now: Long) {
        val s = snapshot() ?: return
        write {
            if (s.startedAt == null) putLong(K_STARTED, now)
            remove(K_REASON)
            remove(K_NOT_BEFORE)
        }
    }

    /** An ended fetch is never written again: a Stop that lands during a request keeps its "stopped". */
    @Synchronized fun ended(now: Long, reason: String) {
        val s = snapshot() ?: return
        if (s.endedAt != null) return
        write {
            putLong(K_ENDED, now)
            putString(K_REASON, reason)
            remove(K_NOT_BEFORE)
        }
    }

    @Synchronized fun paused(reason: String, notBefore: Long?) {
        val s = snapshot() ?: return
        if (s.endedAt != null) return
        write {
            putString(K_REASON, reason)
            putOrRemove(K_NOT_BEFORE, notBefore)
        }
    }

    @Synchronized fun account(id: Long, capturedAt: Long) {
        if (snapshot() == null) return
        write {
            putLong(K_ACCOUNT, id)
            putLong(K_ACCOUNT_CAPTURED, capturedAt)
        }
    }

    @Synchronized fun listed(now: Long) {
        if (snapshot() == null) return
        write { putLong(K_LISTED, now) }
    }

    @Synchronized fun addFound(n: Int) {
        if (n <= 0 || snapshot() == null) return
        write { putInt(K_FOUND, prefs.getInt(K_FOUND, 0) + n) }
    }

    @Synchronized fun readSent(dayStartMs: Long) {
        val count = if (long(K_READS_DAY) == dayStartMs) prefs.getInt(K_READS_COUNT, 0) else 0
        val editor = prefs.edit().putLong(K_READS_DAY, dayStartMs).putInt(K_READS_COUNT, count + 1)
        check(editor.commit()) { "the read count could not be written" }
    }

    @Synchronized fun readsOn(dayStartMs: Long): Int = if (long(K_READS_DAY) == dayStartMs) prefs.getInt(K_READS_COUNT, 0) else 0

    /** After Wipe everything cleared the settings file: forget what was shown. */
    @Synchronized fun reload() {
        _state.value = read()
    }

    private companion object {
        /** The settings file: Wipe everything clears it whole (SettingsStore.clear). */
        const val FILE = "settings"
        const val K_CREATED = "fetch_created_at"
        const val K_STARTED = "fetch_started_at"
        const val K_ENDED = "fetch_ended_at"
        const val K_REASON = "fetch_stop_reason"
        const val K_NOT_BEFORE = "fetch_resume_not_before"
        const val K_ACCOUNT = "fetch_account_id"
        const val K_ACCOUNT_CAPTURED = "fetch_account_captured_at"
        const val K_LISTED = "fetch_listed_at"
        const val K_FOUND = "fetch_found"
        const val K_READS_DAY = "fetch_reads_day"
        const val K_READS_COUNT = "fetch_reads_count"
        val FETCH_KEYS = listOf(K_CREATED, K_STARTED, K_ENDED, K_REASON, K_NOT_BEFORE, K_ACCOUNT, K_ACCOUNT_CAPTURED, K_LISTED, K_FOUND)
    }
}

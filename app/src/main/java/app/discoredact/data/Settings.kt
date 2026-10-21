package app.discoredact.data

import android.content.Context
import android.content.SharedPreferences
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.core.pacing.PacingSettings
import app.discoredact.core.pacing.QuietHours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val pacing: PacingSettings = PacingSettings(),
    /** Dry run defaults ON until one dry run has completed (CLAUDE.md rule 10). */
    val dryRun: Boolean = true,
    val dryRunCompleted: Boolean = false,
    /** Set when an import starts, cleared when it completes; still set means the last import did not finish. */
    val importUnfinished: Boolean = false,
)

/** Settings in a private SharedPreferences file (excluded from backup and device transfer like everything else). */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    private fun read(): AppSettings {
        val preset = prefs.getString(K_PRESET, null)?.let { runCatching { PacingPreset.valueOf(it) }.getOrNull() } ?: PacingPreset.DEFAULT
        val defaults = PacingSettings(preset)
        val start = prefs.getInt(K_QUIET_START, QuietHours.DEFAULT.startMinute)
        val end = prefs.getInt(K_QUIET_END, QuietHours.DEFAULT.endMinute)
        val quiet = runCatching { QuietHours(start, end) }.getOrDefault(QuietHours.DEFAULT)
        val completed = prefs.getBoolean(K_DRY_DONE, false)
        return AppSettings(
            pacing = PacingSettings(
                preset = preset,
                dailyCap = prefs.getInt(K_CAP, defaults.dailyCap).coerceIn(1, PacingSettings.MAX_DAILY_CAP),
                quietHours = quiet,
            ),
            dryRun = if (prefs.contains(K_DRY)) prefs.getBoolean(K_DRY, true) else !completed,
            dryRunCompleted = completed,
            importUnfinished = prefs.getBoolean(K_IMPORT_UNFINISHED, false),
        )
    }

    fun setImportUnfinished(unfinished: Boolean) {
        prefs.edit().putBoolean(K_IMPORT_UNFINISHED, unfinished).commit()
        state.value = read()
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        prefs.edit()
            .putString(K_PRESET, next.pacing.preset.name)
            .putInt(K_CAP, next.pacing.dailyCap.coerceIn(1, PacingSettings.MAX_DAILY_CAP))
            .putInt(K_QUIET_START, next.pacing.quietHours.startMinute)
            .putInt(K_QUIET_END, next.pacing.quietHours.endMinute)
            .putBoolean(K_DRY, next.dryRun)
            .putBoolean(K_DRY_DONE, next.dryRunCompleted)
            .commit()
        state.value = read()
    }

    fun clear() {
        prefs.edit().clear().commit()
        state.value = read()
    }

    private companion object {
        const val FILE = "settings"
        const val K_PRESET = "pacing_preset"
        const val K_CAP = "daily_cap"
        const val K_QUIET_START = "quiet_start_minute"
        const val K_QUIET_END = "quiet_end_minute"
        const val K_DRY = "dry_run"
        const val K_DRY_DONE = "dry_run_completed"
        const val K_IMPORT_UNFINISHED = "import_unfinished"
    }
}

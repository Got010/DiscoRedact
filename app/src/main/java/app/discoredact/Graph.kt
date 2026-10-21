package app.discoredact

import android.content.Context
import app.discoredact.data.AppDatabase
import app.discoredact.data.Repository
import app.discoredact.data.SettingsStore

/** Process-wide objects. "Wipe everything" closes and deletes the database; the next access opens a fresh one. */
class Graph private constructor(private val context: Context) {
    val settings = SettingsStore(context)
    private var db: AppDatabase? = null
    private var repo: Repository? = null

    @get:Synchronized
    val repository: Repository
        get() = repo ?: AppDatabase.open(context).let { opened ->
            db = opened
            Repository(opened).also { repo = it }
        }

    /** Deletes the database (every channel, message, selection and run) and every setting. */
    @Synchronized
    fun wipeEverything() {
        db?.close()
        db = null
        repo = null
        check(context.deleteDatabase(AppDatabase.NAME) || !context.getDatabasePath(AppDatabase.NAME).exists()) { "database not deleted" }
        settings.clear()
    }

    companion object {
        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context.applicationContext).also { instance = it } }
    }
}

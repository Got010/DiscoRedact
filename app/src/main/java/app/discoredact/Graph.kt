package app.discoredact

import android.content.Context
import app.discoredact.core.executor.CredentialStore
import app.discoredact.core.executor.RunStore
import app.discoredact.data.KeystoreCredentialStore
import app.discoredact.core.executor.Transport
import app.discoredact.data.AppDatabase
import app.discoredact.data.ImportRunner
import app.discoredact.data.RoomRunStore
import app.discoredact.net.DiscordTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import app.discoredact.data.Repository
import app.discoredact.data.SettingsStore

/** Process-wide objects. "Wipe everything" closes and deletes the database; the next access opens a fresh one. */
class Graph private constructor(private val context: Context) {
    val settings = SettingsStore(context)

    /** The process-wide import: it outlives every screen and ViewModel (see ImportRunner). */
    val imports = ImportRunner(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private var db: AppDatabase? = null
    private var repo: Repository? = null

    @get:Synchronized
    val repository: Repository
        get() = repo ?: AppDatabase.open(context).let { opened ->
            db = opened
            Repository(opened).also { repo = it }
        }

    /** The executor's store, on the same database as [repository]. */
    val runStore: RunStore
        get() = synchronized(this) {
            repository
            RoomRunStore(db!!)
        }

    /** The one network client of the app (M2). Built on first use; a dry run never touches it. */
    val transport: Transport by lazy { DiscordTransport.production() }

    /** The captured header set, AES-GCM under an Android Keystore key (M3). The executor reads it through [credentials]. */
    val credentialStore = KeystoreCredentialStore(context)
    val credentials: CredentialStore get() = credentialStore

    /** One lock for every executor in this process: the guard re-read, the send and its row never interleave. */
    val sendMutex = Mutex()

    /** Deletes the database (every channel, message, selection and run) and every setting. */
    @Synchronized
    fun wipeEverything() {
        check(!imports.isRunning) { "an import is still running" }
        db?.close()
        db = null
        repo = null
        check(context.deleteDatabase(AppDatabase.NAME) || !context.getDatabasePath(AppDatabase.NAME).exists()) { "database not deleted" }
        settings.clear()
        credentialStore.wipe()
    }

    companion object {
        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context.applicationContext).also { instance = it } }
    }
}

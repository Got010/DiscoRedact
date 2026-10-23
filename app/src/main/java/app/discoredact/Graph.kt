package app.discoredact

import android.content.Context
import java.io.File
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

    /**
     * Held by the run worker for its whole body (DeleteWorker.doWork), so "Wipe everything" can wait for the worker's
     * real end: WorkManager reports a cancelled worker as finished before its coroutine has stopped.
     */
    val workerMutex = Mutex()

    /**
     * Deletes the database (every channel, message, selection, run and event), every setting and the stored
     * credentials with their key. Every step is tried even if an earlier one failed, so a failure never leaves the
     * credentials behind; it then throws, naming the steps (never a value). The caller has stopped the import and the
     * run worker first.
     */
    @Synchronized
    fun wipeEverything() {
        check(!imports.isRunning) { "an import is still running" }
        val failed = mutableListOf<String>()
        try {
            db?.close()
        } catch (_: Exception) {
            failed += "database close"
        }
        db = null
        repo = null
        if (!databaseGone()) failed += "database"
        if (!runCatching { settings.clear() }.getOrDefault(false)) failed += "settings"
        if (runCatching { credentialStore.wipe() }.getOrDefault(false).not()) failed += "credentials"
        check(failed.isEmpty()) { "not wiped: ${failed.joinToString(", ")}" }
    }

    /** Deletes the database file with its -journal, -wal and -shm companions (Context.deleteDatabase removes them all) and checks. */
    private fun databaseGone(): Boolean {
        val main = context.getDatabasePath(AppDatabase.NAME)
        val deleted = context.deleteDatabase(AppDatabase.NAME) || !main.exists()
        return deleted && DB_SUFFIXES.none { File(main.path + it).exists() }
    }

    companion object {
        private val DB_SUFFIXES = listOf("", "-journal", "-wal", "-shm")

        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context.applicationContext).also { instance = it } }
    }
}

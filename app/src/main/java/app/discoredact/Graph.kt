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
import app.discoredact.data.SignInStorage
import app.discoredact.core.history.FetchStore
import app.discoredact.core.signin.SignInSweepGate
import app.discoredact.data.FetchStateStore
import app.discoredact.data.RoomFetchStore
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

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

    /** The Fetch import's own state (the current fetch, the account id, the day's reads), in the settings file. */
    val fetchState = FetchStateStore(context)

    /** The fetch session's store, on the same database as [repository]. */
    val fetchStore: FetchStore
        get() = synchronized(this) {
            repository
            RoomFetchStore(db!!, fetchState)
        }

    /** The executor's store, on the same database as [repository]. */
    val runStore: RunStore
        get() = synchronized(this) {
            repository
            RoomRunStore(db!!, fetchState)
        }

    /** The one network client of the app (M2). Built on first use; a dry run never touches it. */
    val transport: Transport by lazy { DiscordTransport.production() }

    /** The captured header set, AES-GCM under an Android Keystore key (M3). The executor reads it through [credentials]. */
    val credentialStore = KeystoreCredentialStore(context)
    val credentials: CredentialStore get() = credentialStore

    /**
     * What a sign-in page left on disk when the process ended with the page open (a kill, a crash), deleted at process
     * start before any page exists. The sign-in screen loads a page only after [signInStorageClean] says so.
     */
    private val signInSweep: Deferred<Boolean> =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).async { runCatching { SignInStorage.wipe(context) }.getOrDefault(false) }

    /**
     * The start sweep counts once; a failed sweep is tried again on each ask; after Wipe everything ([wipeEverything]
     * invalidates it) the next page sweeps again instead of trusting an earlier success, since the wipe deleted the
     * directories while a page may have run earlier in this process (SignInSweepGate, tested in :core).
     */
    private val signInGate = SignInSweepGate(
        startSweep = { signInSweep.await() },
        sweep = { withContext(Dispatchers.IO) { runCatching { SignInStorage.wipe(context) }.getOrDefault(false) } },
    )

    /** True once a sweep has succeeded since process start or the last wipe. False means the page must not load (CLAUDE.md rule 19). */
    suspend fun signInStorageClean(): Boolean = signInGate.clean()

    /** One lock for every executor and fetch session in this process: a request and its rows never interleave with another. */
    val sendMutex = Mutex()

    /**
     * Held by the run worker and the fetch worker for their whole bodies (DeleteWorker.doWork, FetchWorker.doWork), so
     * the two never run at once and "Wipe everything" can wait for a worker's real end: WorkManager reports a
     * cancelled worker as finished before its coroutine has stopped.
     */
    val workerMutex = Mutex()

    /**
     * Deletes the database (every channel, message, selection, run, event and fetch progress), every setting (the
     * fetch's state and account id with them), the stored credentials
     * with their key, and whatever the sign-in page's data directories still hold (SignInStorage). Every step is tried
     * even if an earlier one failed, so a failure never leaves the credentials behind; it then throws, naming the steps
     * (never a value). The caller has stopped the import and the run worker first.
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
        // The settings file held the fetch's state and account id too (FetchStateStore); show it gone.
        runCatching { fetchState.reload() }
        if (runCatching { credentialStore.wipe() }.getOrDefault(false).not()) failed += "credentials"
        // Whatever happens below, the next sign-in page sweeps again before it loads.
        signInGate.invalidate()
        if (!runCatching { SignInStorage.wipe(context) }.getOrDefault(false)) failed += "sign-in page data"
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

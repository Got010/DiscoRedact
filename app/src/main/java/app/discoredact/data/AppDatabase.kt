package app.discoredact.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChannelEntity::class,
        MessageEntity::class,
        RunEntity::class,
        EventEntity::class,
        RunMessageEntity::class,
        SelectionOverrideEntity::class,
        ChannelInclusionEntity::class,
        RunOptionsEntity::class,
        RunResultEntity::class,
        DeleteLogEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun messages(): MessageDao
    abstract fun runs(): RunDao

    companion object {
        const val NAME = "discoredact.db"
        const val VERSION = 2

        /** Schema 2 (M2): the executor's side tables. Nothing in the section-5 tables changes. */
        val MIGRATION_1_2_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `run_options` (`run_id` INTEGER NOT NULL, `dry_run` INTEGER NOT NULL, `oldest_first` INTEGER NOT NULL, " +
                "`resume_not_before` INTEGER, `created_at` INTEGER NOT NULL, PRIMARY KEY(`run_id`), " +
                "FOREIGN KEY(`run_id`) REFERENCES `runs`(`run_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE TABLE IF NOT EXISTS `run_results` (`run_id` INTEGER NOT NULL, `message_id` INTEGER NOT NULL, `verdict` TEXT NOT NULL, " +
                "PRIMARY KEY(`run_id`, `message_id`), " +
                "FOREIGN KEY(`run_id`) REFERENCES `runs`(`run_id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`message_id`) REFERENCES `messages`(`message_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_run_results_message_id` ON `run_results` (`message_id`)",
            "CREATE TABLE IF NOT EXISTS `delete_log` (`message_id` INTEGER NOT NULL, `run_id` INTEGER NOT NULL, `deleted_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`message_id`), " +
                "FOREIGN KEY(`message_id`) REFERENCES `messages`(`message_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_delete_log_deleted_at` ON `delete_log` (`deleted_at`)",
        )

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        /**
         * Every schema change adds a migration here and a test (CLAUDE.md rule 19). There is deliberately no
         * destructive fallback: an upgrade without a migration fails instead of wiping the user's selections.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

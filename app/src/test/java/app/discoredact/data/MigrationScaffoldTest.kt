package app.discoredact.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Migration scaffold (CLAUDE.md rule 19). Schema 1 has no migration. When the version goes up, this fails until the
 * new schema file is committed and a migration covers every step from 1 to the current version; the instrumented
 * MigrationTestHelper test for that migration comes with it.
 */
class MigrationScaffoldTest {
    private val dir = File("schemas/app.discoredact.data.AppDatabase")

    @Test
    fun everyVersionHasACommittedSchema() {
        val versions = dir.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.sorted()
        assertEquals((1..AppDatabase.VERSION).toList(), versions)
    }

    @Test
    fun migrationsChainFromOneToTheCurrentVersion() {
        var v = 1
        val byStart = AppDatabase.MIGRATIONS.associateBy { it.startVersion }
        while (v < AppDatabase.VERSION) {
            val m = byStart[v] ?: throw AssertionError("no migration from version $v")
            assertTrue(m.endVersion > v)
            v = m.endVersion
        }
        assertEquals(AppDatabase.VERSION, v)
    }

    @Test
    fun schemaOneHasNoMigrationYet() {
        assertEquals(1, AppDatabase.VERSION)
        assertEquals(0, AppDatabase.MIGRATIONS.size)
    }

    @Test
    fun searchTextIsEscapedForLike() {
        assertEquals(null, Repository.likeArg("   "))
        assertEquals("50\\% off\\_now \\\\o/", Repository.likeArg(" 50% off_now \\o/ "))
    }
}

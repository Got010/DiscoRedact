package app.discoredact.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun schemaTwoHasTheOneMigration() {
        assertEquals(2, AppDatabase.VERSION)
        assertEquals(1, AppDatabase.MIGRATIONS.size)
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)
    }

    /**
     * The migration's SQL is exactly what Room generates for schema 2's new tables and indices, so a database
     * migrated from 1 and a fresh one are identical (the instrumented MigrationTestHelper check needs a device).
     */
    @Test
    fun migrationOneToTwoCreatesExactlyWhatRoomGeneratesForTheNewTables() {
        val db = Json.parseToJsonElement(File(dir, "2.json").readText()).jsonObject["database"]!!.jsonObject
        val expected = HashSet<String>()
        for (e in db["entities"]!!.jsonArray.map { it.jsonObject }) {
            val name = e["tableName"]!!.jsonPrimitive.content
            if (name !in setOf("run_options", "run_results", "delete_log")) continue
            expected += e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
            val indices = e["indices"]?.jsonArray?.map { it.jsonObject }.orEmpty() // absent when a table has none
            for (i in indices) expected += i["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
        }
        val norm = { s: String -> s.replace(Regex("\\s+"), " ").trim() }
        assertEquals(expected.map(norm).toSet(), AppDatabase.MIGRATION_1_2_SQL.map(norm).toSet())
    }

    @Test
    fun searchTextIsEscapedForLike() {
        assertEquals(null, Repository.likeArg("   "))
        assertEquals("50\\% off\\_now \\\\o/", Repository.likeArg(" 50% off_now \\o/ "))
    }
}

package app.discoredact.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
    fun schemaThreeHasTheTwoMigrations() {
        assertEquals(3, AppDatabase.VERSION)
        assertEquals(2, AppDatabase.MIGRATIONS.size)
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
    }

    private fun entities(version: Int) =
        Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }

    private val norm = { s: String -> s.replace(Regex("\\s+"), " ").trim() }

    /**
     * Migration 2 to 3 (CLAUDE.md rule 19), checked against the committed schemas: it adds exactly the columns schema 3
     * adds to `channels` and `messages`, each as schema 3 declares it (NOT NULL with the default 'PACKAGE', so every
     * existing row reads PACKAGE), creates `fetch_progress` exactly as Room does, and touches no other table.
     */
    @Test
    fun migrationTwoToThreeAddsExactlyWhatSchemaThreeAdds() {
        val two = entities(2)
        val three = entities(3)
        assertEquals(two.keys + "fetch_progress", three.keys)
        val expected = HashSet<String>()
        for ((name, e) in three) {
            val before = two[name]
            if (before == null) {
                expected += e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
                continue
            }
            val oldCols = before["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            val newFields = e["fields"]!!.jsonArray.map { it.jsonObject }
            // Columns are only ever added at the end; nothing is dropped, renamed or retyped.
            assertEquals(name, oldCols, newFields.take(oldCols.size).map { it["columnName"]!!.jsonPrimitive.content })
            assertEquals(name, before["fields"], e["fields"]!!.jsonArray.take(oldCols.size).let { kotlinx.serialization.json.JsonArray(it) })
            for (f in newFields.drop(oldCols.size)) {
                val col = f["columnName"]!!.jsonPrimitive.content
                val notNull = f["notNull"]?.jsonPrimitive?.boolean == true
                val default = f["defaultValue"]?.jsonPrimitive?.content
                // An added NOT NULL column needs a default, or ALTER TABLE fails on a table with rows.
                assertTrue("$name.$col", !notNull || default != null)
                expected += "ALTER TABLE `$name` ADD COLUMN `$col` ${f["affinity"]!!.jsonPrimitive.content}" +
                    (if (notNull) " NOT NULL" else "") + (default?.let { " DEFAULT $it" } ?: "")
                // ...and the same definition is what Room creates for a fresh install.
                assertTrue(e["createSql"]!!.jsonPrimitive.content.contains("`$col` ${f["affinity"]!!.jsonPrimitive.content}" + (if (notNull) " NOT NULL" else "") + (default?.let { " DEFAULT $it" } ?: "")))
            }
            assertEquals(name, before["indices"], e["indices"])
            assertEquals(name, before["foreignKeys"], e["foreignKeys"])
        }
        assertEquals(expected.map(norm).toSet(), AppDatabase.MIGRATION_2_3_SQL.map(norm).toSet())
        assertEquals("'PACKAGE'", three["messages"]!!["fields"]!!.jsonArray.last().jsonObject["defaultValue"]!!.jsonPrimitive.content)
        assertEquals("'PACKAGE'", three["channels"]!!["fields"]!!.jsonArray.last().jsonObject["defaultValue"]!!.jsonPrimitive.content)
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

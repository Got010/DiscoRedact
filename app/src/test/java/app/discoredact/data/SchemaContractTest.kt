package app.discoredact.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the exported Room schema (app/schemas, written by Room at build time and committed) to spec section 5.
 * Unit tests run with the module directory as the working directory.
 */
class SchemaContractTest {
    private val dir = File("schemas/app.discoredact.data.AppDatabase")

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun table(db: JsonObject, name: String): JsonObject =
        db["entities"]!!.jsonArray.map { it.jsonObject }.single { it["tableName"]!!.jsonPrimitive.content == name }

    private fun columns(t: JsonObject): List<String> = t["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }

    @Test
    fun section5TablesHaveExactlyTheSpecColumns() {
        val db = schema(AppDatabase.VERSION)
        assertEquals(
            // Schema 3 adds `source` (which import found the row) after the spec's columns.
            listOf("channel_id", "type", "display_name", "recipient_ids", "message_count", "mode", "state", "last_error", "source"),
            columns(table(db, "channels")),
        )
        assertEquals(
            listOf(
                "message_id", "channel_id", "timestamp", "content", "has_attachments", "selected", "keep", "status",
                "attempts", "last_http_status", "last_discord_code", "updated_at", "source",
            ),
            columns(table(db, "messages")),
        )
        assertEquals(listOf("run_id", "started_at", "ended_at", "deleted_count", "stop_reason"), columns(table(db, "runs")))
        // events: the spec's eight columns plus Room's required surrogate key.
        assertEquals(
            listOf("event_id", "ts", "run_id", "http_status", "discord_code", "bucket", "remaining", "reset_after", "action_taken"),
            columns(table(db, "events")),
        )
    }

    @Test
    fun primaryKeysAndMessageIndices() {
        val db = schema(AppDatabase.VERSION)
        fun pk(t: String) = table(db, t)["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("channel_id"), pk("channels"))
        assertEquals(listOf("message_id"), pk("messages"))
        assertEquals(listOf("run_id"), pk("runs"))
        assertEquals(listOf("run_id", "message_id"), pk("run_messages"))
        val idx = table(db, "messages")["indices"]!!.jsonArray.map { i -> i.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content } }
        assertTrue(idx.contains(listOf("channel_id", "status")))
        assertTrue(idx.contains(listOf("channel_id", "timestamp")))
    }

    @Test
    fun frozenSetPositionsAreUniquePerRun() {
        val db = schema(AppDatabase.VERSION)
        val unique = table(db, "run_messages")["indices"]!!.jsonArray.map { it.jsonObject }
            .single { i -> i["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("run_id", "position") }
        assertTrue(unique["unique"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun schemaFileVersionMatchesTheDatabase() {
        assertEquals(AppDatabase.VERSION, schema(AppDatabase.VERSION)["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun theSourceColumnsAndTheFetchProgressTable() {
        val db = schema(AppDatabase.VERSION)
        for (t in listOf("channels", "messages")) {
            val source = table(db, t)["fields"]!!.jsonArray.map { it.jsonObject }.single { it["columnName"]!!.jsonPrimitive.content == "source" }
            assertEquals("TEXT", source["affinity"]!!.jsonPrimitive.content)
            assertTrue(source["notNull"]!!.jsonPrimitive.boolean)
            assertEquals("'PACKAGE'", source["defaultValue"]!!.jsonPrimitive.content)
        }
        val progress = table(db, "fetch_progress")
        assertEquals(listOf("channel_id", "oldest_fetched_id", "done", "updated_at"), columns(progress))
        assertEquals(listOf("channel_id"), progress["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content })
        val nullable = progress["fields"]!!.jsonArray.map { it.jsonObject }.filter { it["notNull"]?.jsonPrimitive?.boolean != true }
            .map { it["columnName"]!!.jsonPrimitive.content }
        assertEquals(listOf("oldest_fetched_id"), nullable)
        // The values the source column holds are the enum's names, as Room stores enums.
        assertEquals(listOf("PACKAGE", "API", "BOTH"), app.discoredact.core.model.RowSource.entries.map { it.name })
    }
}

package app.discoredact.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keep beats everything: the leftovers set must exclude a kept message whatever its status. Unit tests run in the module directory. */
class LeftoverQueryTest {
    private val source = File("src/main/java/app/discoredact/data/Daos.kt").readText()

    private fun leftoverWhere(): String {
        val m = Regex("private const val LEFTOVER_WHERE = \"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).find(source)
        return m!!.groupValues[1].trim().replace(Regex("\\s+"), " ")
    }

    @Test
    fun keepZeroGuardsBothThePendingAndTheFailedBranch() {
        assertEquals(
            "m.keep = 0 AND ((m.status = 'PENDING' AND c.state IN ('INACCESSIBLE', 'GONE')) OR m.status = 'FAILED')",
            leftoverWhere(),
        )
    }

    @Test
    fun bothLeftoverQueriesUseTheSameWhere() {
        assertEquals(2, Regex(Regex.escape("WHERE \$LEFTOVER_WHERE")).findAll(source).count())
        assertTrue(source.contains("fun leftovers()") && source.contains("fun observeLeftoverCount()"))
    }
}

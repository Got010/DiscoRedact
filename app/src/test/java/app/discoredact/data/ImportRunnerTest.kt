package app.discoredact.data

import app.discoredact.core.importer.ImportProgress
import app.discoredact.core.importer.ImportReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The process-wide import runner, on the JVM: what a second screen sees, and how Wipe stops an import. */
class ImportRunnerTest {
    private val pool = Executors.newFixedThreadPool(2)
    private val runner = ImportRunner(CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher()))

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun report(inserted: Long) = ImportReport(1, 1, 0, 0, 0, mapOf(1L to inserted.toInt()), inserted, 0, 0, 0)

    private fun awaitState(predicate: (ImportState) -> Boolean): ImportState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val s = runner.state.value
            if (predicate(s)) return s
            Thread.sleep(5)
        }
        throw AssertionError("state never matched; last ${runner.state.value}")
    }

    @Test
    fun aSecondObserverSeesTheRunningImportAndASecondImportIsRefused() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var completed = false
        assertTrue(
            runner.start(work = { _, progress ->
                progress(ImportProgress(0, 1, 42))
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                report(42)
            }, onCompleted = { completed = true }),
        )
        assertTrue(started.await(10, TimeUnit.SECONDS))
        // A new screen (a new ViewModel) only has the shared runner: it sees Running, with progress.
        val seenBySecondScreen = awaitState { it is ImportState.Running && it.progress != null }
        assertEquals(42L, (seenBySecondScreen as ImportState.Running).progress!!.messagesRead)
        assertTrue(runner.isRunning)
        assertFalse(runner.start(work = { _, _ -> report(0) }))
        runner.reset() // refused while running
        assertTrue(runner.state.value is ImportState.Running)
        release.countDown()
        val done = awaitState { it is ImportState.Done }
        assertEquals(42L, (done as ImportState.Done).report.messagesInserted)
        assertTrue(completed)
        runner.reset()
        assertEquals(ImportState.Idle, runner.state.value)
    }

    @Test
    fun stopAndJoinStopsTheSharedImportAtItsNextCheckpoint() {
        val batches = AtomicInteger()
        val started = CountDownLatch(1)
        var completed = false
        runner.start(work = { checkpoint, _ ->
            var n = 0L
            while (n >= 0) { // until the checkpoint throws
                checkpoint()
                batches.incrementAndGet()
                started.countDown()
                Thread.sleep(2)
                n++
            }
            report(n)
        }, onCompleted = { completed = true })
        assertTrue(started.await(10, TimeUnit.SECONDS))
        runBlocking { runner.stopAndJoin() }
        assertEquals(ImportState.Idle, runner.state.value)
        assertFalse(runner.isRunning)
        assertFalse(completed)
        val after = batches.get()
        Thread.sleep(50)
        assertEquals(after, batches.get())
        // The signal is cleared afterwards: a new import runs normally.
        assertTrue(runner.start(work = { checkpoint, _ -> checkpoint(); report(1) }))
        assertTrue(awaitState { it is ImportState.Done } is ImportState.Done)
    }

    @Test
    fun aFailedImportIsReportedAndDoesNotCountAsCompleted() {
        var completed = false
        runner.start(work = { _, _ -> throw IllegalStateException("disk full") }, onCompleted = { completed = true })
        val failed = awaitState { it is ImportState.Failed } as ImportState.Failed
        assertEquals("IllegalStateException: disk full", failed.message)
        assertFalse(completed)
    }
}

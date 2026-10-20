package com.liftoff.app.ui.control

import com.liftoff.app.data.ImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The busy/result logic driven with fake work and a named worker dispatcher. No Compose, no Android:
 * the view model's data calls are lambdas, so plain JUnit can hold them on a latch and read the state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val scopeJob = Job()
    private val workerPool: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, WORKER_THREAD) }
    private val worker: ExecutorCoroutineDispatcher = workerPool.asCoroutineDispatcher()
    private val fileBytes = """{"header":{},"missions":[],"sorties":[]}""".toByteArray()

    @After
    fun tearDown() {
        scopeJob.cancel()
        worker.close()
    }

    private fun newVm(
        exportWork: suspend (OutputStream) -> Unit = {},
        importWork: suspend (InputStream) -> ImportResult = { ImportResult.Restored(0, 0) }
    ): BackupViewModel = BackupViewModel(
        scope = CoroutineScope(scopeJob + Dispatchers.Unconfined),
        dispatcher = worker,
        exportWork = exportWork,
        importWork = importWork
    )

    /** Every wait here is bounded, so a state that never settles reads as a failure, not a hang. */
    private fun awaitState(
        vm: BackupViewModel,
        wanted: String,
        matches: (BackupState) -> Boolean
    ): BackupState {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val current = vm.state.value
            if (matches(current)) return current
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $wanted; state was ${vm.state.value}")
    }

    private fun awaitResult(vm: BackupViewModel, wanted: String): BackupState =
        awaitState(vm, wanted) { it is BackupState.Succeeded || it is BackupState.Failed }

    /** Coroutines append their own name to the thread while they run, so the worker is read by prefix. */
    private fun ranOnWorker(step: String, thread: String?, callingThread: String) {
        assertTrue("$step ran on ${thread ?: "no thread at all"}", thread != null && thread.startsWith(WORKER_THREAD))
        assertTrue("$step ran on the thread that called the action ($thread)", thread != callingThread)
    }

    /** The row is this app's own sentence, not the exception's text, and the state moved off Busy. */
    private fun assertFailureRow(vm: BackupViewModel, expected: String, rawText: String) {
        val row = awaitState(vm, "the failure row") { it is BackupState.Failed }
        assertEquals(expected, row.message)
        assertFalse("the row showed raw exception text: $row", row.message.orEmpty().contains(rawText))
    }

    // Brief: "Both run off the main thread." The stream opener and the data call both recorded running
    // on the injected dispatcher's own thread; the thread that called the action never appears.
    @Test
    fun backupWorkRunsOffTheCallingThread() {
        val callingThread = Thread.currentThread().name
        val exportOpenerThread = AtomicReference<String>()
        val exportWorkThread = AtomicReference<String>()
        val importOpenerThread = AtomicReference<String>()
        val importWorkThread = AtomicReference<String>()

        val vm = newVm(
            exportWork = { exportWorkThread.set(Thread.currentThread().name) },
            importWork = {
                importWorkThread.set(Thread.currentThread().name)
                ImportResult.Restored(missions = 1, sorties = 1)
            }
        )

        vm.runExport {
            exportOpenerThread.set(Thread.currentThread().name)
            ByteArrayOutputStream()
        }
        awaitResult(vm, "the export to finish")
        vm.runImport {
            importOpenerThread.set(Thread.currentThread().name)
            ByteArrayInputStream(fileBytes)
        }
        awaitResult(vm, "the import to finish")

        assertTrue("the test thread is named like the worker", !callingThread.startsWith(WORKER_THREAD))
        ranOnWorker("the export's stream opener", exportOpenerThread.get(), callingThread)
        ranOnWorker("the export call", exportWorkThread.get(), callingThread)
        ranOnWorker("the import's stream opener", importOpenerThread.get(), callingThread)
        ranOnWorker("the import call", importWorkThread.get(), callingThread)
    }

    // Brief: "show that they are working while they run" and "after an export, that it was saved". The
    // fake export is parked on a latch, so Busy is read while it is still going, then the saved row.
    @Test
    fun exportShowsBusyThenThatItWasSaved() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val vm = newVm(exportWork = {
            started.countDown()
            assertTrue("the parked export was never released", release.await(WAIT_MS, TimeUnit.MILLISECONDS))
        })
        try {
            vm.runExport { ClosingOutput(closes) }
            assertEquals(BackupState.Busy(BackupAction.Export), vm.state.value)
            assertTrue("the fake export never started", started.await(WAIT_MS, TimeUnit.MILLISECONDS))
            assertEquals("Exporting…", vm.state.value.message)
        } finally {
            release.countDown()
        }

        val saved = awaitState(vm, "the saved row") { it is BackupState.Succeeded }
        assertEquals("Exported the whole database.", saved.message)
        assertEquals("the export stream was left open", 1, closes.get())
    }

    // Brief: "after an import, how much was restored" — the counts the fake returned, singular for a
    // count of one.
    @Test
    fun importShowsBusyThenHowMuchWasRestored() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val vm = newVm(importWork = {
            started.countDown()
            assertTrue("the parked import was never released", release.await(WAIT_MS, TimeUnit.MILLISECONDS))
            ImportResult.Restored(missions = 3, sorties = 12)
        })
        try {
            vm.runImport { ClosingInput(fileBytes, closes) }
            assertEquals(BackupState.Busy(BackupAction.Import), vm.state.value)
            assertTrue("the fake import never started", started.await(WAIT_MS, TimeUnit.MILLISECONDS))
            assertEquals("Importing…", vm.state.value.message)
        } finally {
            release.countDown()
        }

        val restored = awaitState(vm, "the restored row") { it is BackupState.Succeeded }
        assertEquals("Restored 3 missions and 12 sorties.", restored.message)
        assertEquals("the import stream was left open", 1, closes.get())

        val one = newVm(importWork = { ImportResult.Restored(missions = 1, sorties = 1) })
        one.runImport { ByteArrayInputStream(fileBytes) }
        val single = awaitState(one, "the one-mission row") { it is BackupState.Succeeded }
        assertEquals("Restored 1 mission and 1 sortie.", single.message)
    }

    // Brief: "after a failure, the data layer's message". A Refused becomes a failure row carrying that
    // sentence unchanged — not a reworded version of it.
    @Test
    fun refusedImportShowsTheDataLayersMessageUnchanged() {
        val dataLayersMessage = "Import needs an empty database. This phone already has training data."
        val vm = newVm(importWork = { ImportResult.Refused(dataLayersMessage) })
        vm.runImport { ByteArrayInputStream(fileBytes) }

        val row = awaitState(vm, "the refusal row") { it is BackupState.Failed }
        assertEquals(dataLayersMessage, row.message)
    }

    // A failure has to show as a row, so work that throws must not escape to the caller or leave the
    // state stuck on Busy. Each one ends as a failure carrying this app's own readable wording.
    @Test
    fun workThatThrowsBecomesAFailureRowNotACrash() {
        val throwingExport = newVm(exportWork = { throw IOException("No space left on device") })
        throwingExport.runExport { ByteArrayOutputStream() }
        assertFailureRow(throwingExport, "Export failed — the file could not be written.", "No space left")

        // The picker handed back no stream at all: the opener ran but produced nothing to write to.
        val noStreamAtAll = newVm()
        noStreamAtAll.runExport { null }
        assertFailureRow(noStreamAtAll, "Export failed — the file could not be written.", "no stream")

        val revokedFile = newVm(importWork = { throw SecurityException("the picker took the file back") })
        revokedFile.runImport { ByteArrayInputStream(fileBytes) }
        assertFailureRow(revokedFile, "Import failed — the file could not be read.", "took the file back")

        // The scope and the busy guard both survived the throw, so the same view model can export again.
        val attempts = AtomicInteger()
        val retried = newVm(exportWork = {
            if (attempts.incrementAndGet() == 1) throw IOException("the disk was full")
        })
        retried.runExport { ByteArrayOutputStream() }
        awaitState(retried, "the first failure row") { it is BackupState.Failed }
        retried.runExport { ByteArrayOutputStream() }
        awaitState(retried, "the retry to succeed") { it is BackupState.Succeeded }
    }

    // Brief: "Cancelling the picker does nothing." No busy state, no new row, and the result the owner
    // may still be reading stays where it was.
    @Test
    fun aCancelledPickerChangesNothing() {
        val exportCalls = AtomicInteger()
        val importCalls = AtomicInteger()
        val vm = newVm(
            exportWork = { exportCalls.incrementAndGet() },
            importWork = {
                importCalls.incrementAndGet()
                ImportResult.Restored(missions = 2, sorties = 5)
            }
        )
        vm.runExport { ByteArrayOutputStream() }
        val saved = awaitState(vm, "the saved row") { it is BackupState.Succeeded }

        vm.runExport(null)
        vm.runImport(null)

        val deadline = System.currentTimeMillis() + 250
        while (System.currentTimeMillis() < deadline) {
            assertEquals("a cancelled picker moved the state", saved, vm.state.value)
            Thread.sleep(10)
        }
        assertEquals(1, exportCalls.get())
        assertEquals(0, importCalls.get())
    }

    // Brief: "The default name carries today's local date, like liftoff-2026-10-19.json". The name ends in
    // .json, its date is the device's own, not the UTC one, and no picker is opened to show it.
    @Test
    fun theDefaultExportFileNameCarriesTodaysLocalDate() {
        // 02:00 UTC on the 20th is still the evening of the 19th on a device set to New York, so only the
        // device's own zone can call it the 19th.
        val deviceClock = Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"), ZoneId.of("America/New_York"))
        val suggested = BackupViewModel.defaultExportFileName(deviceClock)
        assertEquals("liftoff-2026-10-19.json", suggested)
        assertFalse("the UTC date leaked into the name", suggested == "liftoff-2026-10-20.json")

        val openedOn = LocalDate.now()
        val default = BackupViewModel.defaultExportFileName()
        val closedOn = LocalDate.now()
        assertTrue(
            "the name is not today's local date: $default",
            default == "liftoff-$openedOn.json" || default == "liftoff-$closedOn.json"
        )
        assertTrue("the name is not a .json file", default.endsWith(".json"))
    }

    // The card dims both buttons while work runs, and the view model refuses a second action anyway: the
    // parked export holds the worker, so the second call can only hit the guard. The first result survives.
    @Test
    fun aSecondActionWhileWorkIsRunningChangesNothing() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val importOpeners = AtomicInteger()
        val importCalls = AtomicInteger()
        val vm = newVm(
            exportWork = {
                started.countDown()
                assertTrue("the parked export was never released", release.await(WAIT_MS, TimeUnit.MILLISECONDS))
            },
            importWork = {
                importCalls.incrementAndGet()
                ImportResult.Restored(missions = 1, sorties = 1)
            }
        )
        try {
            vm.runExport { ByteArrayOutputStream() }
            assertTrue("the parked export never started", started.await(WAIT_MS, TimeUnit.MILLISECONDS))
            assertEquals(BackupState.Busy(BackupAction.Export), vm.state.value)

            vm.runExport { ByteArrayOutputStream() }
            vm.runImport {
                importOpeners.incrementAndGet()
                ByteArrayInputStream(fileBytes)
            }

            assertEquals(BackupState.Busy(BackupAction.Export), vm.state.value)
        } finally {
            release.countDown()
        }

        val saved = awaitState(vm, "the first action's row") { it is BackupState.Succeeded }
        assertEquals("Exported the whole database.", saved.message)
        assertEquals("a refused action opened a file", 0, importOpeners.get())
        assertEquals("a refused action reached the data layer", 0, importCalls.get())
    }

    /** Counts close(), so the test can see the stream was closed after the data call. */
    private class ClosingOutput(private val closes: AtomicInteger) : ByteArrayOutputStream() {
        override fun close() {
            closes.incrementAndGet()
            super.close()
        }
    }

    private class ClosingInput(bytes: ByteArray, private val closes: AtomicInteger) :
        ByteArrayInputStream(bytes) {
        override fun close() {
            closes.incrementAndGet()
            super.close()
        }
    }

    private companion object {
        private const val WORKER_THREAD = "backup-worker-under-test"
        private const val WAIT_MS = 5_000L
    }
}

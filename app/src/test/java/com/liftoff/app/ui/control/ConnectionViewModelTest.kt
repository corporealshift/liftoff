package com.liftoff.app.ui.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.liftoff.app.rho.ConnectionOutcome
import com.liftoff.app.rho.protocol.PROTOCOL_VERSION
import com.liftoff.app.settings.Settings
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The action driven with a fake handshake and a real SettingsStore on a temp file: its rows, and what it
 * does before and while the handshake runs. No Compose, no Robolectric: the check arrives as a suspend
 * call, so a test can hand in any outcome it likes, park it on a latch, or read the store from inside it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val scopeJob = Job()
    private val workerPool = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, WORKER_THREAD) }
    private val worker: ExecutorCoroutineDispatcher = workerPool.asCoroutineDispatcher()

    private lateinit var store: SettingsStore

    @After
    fun tearDown() {
        scopeJob.cancel()
        worker.close()
    }

    private fun newStore(fileName: String): SettingsStore = createStore(fileName) { it }

    /** The same store, wrapped so every settings write records the thread it ran on. */
    private fun newStoreRecordingItsWrites(fileName: String, writes: CopyOnWriteArrayList<String>): SettingsStore =
        createStore(fileName) { WriteRecordingStore(it, writes) }

    private fun createStore(
        fileName: String,
        wrap: (DataStore<Preferences>) -> DataStore<Preferences>
    ): SettingsStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined + scopeJob)
        ) {
            File(tmp.root, fileName)
        }
        store = SettingsStore(wrap(dataStore))
        return store
    }

    private fun newVm(check: DaemonCheck): ConnectionViewModel = ConnectionViewModel(
        store = store,
        scope = CoroutineScope(scopeJob + Dispatchers.Unconfined),
        checkDaemon = check,
        dispatcher = worker
    )

    /** Every wait here is bounded, so a row that never arrives reads as a failure, not a hang. */
    private fun awaitRow(vm: ConnectionViewModel, wanted: String): ConnectionState.Result = runBlocking {
        val settled = withTimeoutOrNull(WAIT_MS) { vm.state.first { it is ConnectionState.Result } }
            ?: throw AssertionError("Timed out waiting for $wanted; state was ${vm.state.value}")
        settled as ConnectionState.Result
    }

    private fun stored(): Settings = runBlocking { withTimeout(WAIT_MS) { store.settings.first() } }

    /** Records the thread each settings write is asked for, so a test can see it is not the caller's. */
    private class WriteRecordingStore(
        private val delegate: DataStore<Preferences>,
        val writeThreads: CopyOnWriteArrayList<String>
    ) : DataStore<Preferences> {

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writeThreads.add(Thread.currentThread().name)
            return delegate.updateData(transform)
        }
    }

    // Brief: "on success, the daemon version and the protocol version". Both are the versions the
    // handshake answered: the fake answers a MINOR this client doesn't speak, and a daemon version
    // that isn't Liftoff's own, so neither could have been copied off the client.
    @Test
    fun aSuccessfulConnectionTestShowsTheDaemonsVersionAndTheProtocolVersion() {
        store = newStore("connected.preferences_pb")
        runBlocking { store.setDaemonHost("10.0.0.1") }
        val vm = newVm { ConnectionOutcome.Connected(daemonVersion = "0.0.0", protocolVersion = "1.1") }

        vm.testConnection("100.64.0.5", "9911", null, "sekrit")

        val row = awaitRow(vm, "the success row")
        assertEquals("Mission Control answered — daemon 0.0.0, protocol 1.1", row.message)
        // The row is about the daemon the fields point at, so the draft reached the store before it.
        assertEquals("100.64.0.5", stored().daemonHost)
        assertEquals(9911, stored().daemonPort)
        assertEquals("sekrit", stored().daemonToken)

        // A daemon that named no version is a fact about the daemon, not a blank in the owner's row.
        val unnamed = newVm { ConnectionOutcome.Connected(daemonVersion = "", protocolVersion = "1.1") }
        unnamed.testConnection("100.64.0.5", "9911", null, "sekrit")
        assertEquals(
            "Mission Control answered — daemon unknown, protocol 1.1",
            awaitRow(unnamed, "the success row for a daemon that named no version").message
        )
    }

    // Brief: "major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'". The
    // daemon's major is the one its refusal named; Liftoff's own is read off PROTOCOL_VERSION, so a
    // client that speaks 2.0 would say 2.x here rather than a typed 1.
    @Test
    fun aProtocolMajorMismatchShowsBothProtocolMajors() {
        store = newStore("mismatch.preferences_pb")
        val liftoffMajor = PROTOCOL_VERSION.substringBefore('.')
        val vm = newVm {
            ConnectionOutcome.ProtocolMismatch(
                expectedVersion = PROTOCOL_VERSION,
                daemonVersion = "2.1",
                message = "daemon 2.1 does not speak protocol 1.0"
            )
        }

        vm.testConnection("100.64.0.5", "9911", null, "sekrit")

        val row = awaitRow(vm, "the mismatch row")
        assertEquals(
            "Liftoff speaks rho protocol $liftoffMajor.x, the daemon speaks 2.x",
            row.message
        )
        assertNotEquals(
            "a mismatch is not the same row as an offline daemon",
            "Mission Control offline — check the PC and Tailscale",
            row.message
        )
        // Both majors are in the row, and they are two different numbers, not one number twice.
        assertNotEquals("the daemon's major is not Liftoff's", liftoffMajor, "2")

        // A refusal that names no version gets the unknown-version row, not "the daemon speaks .x".
        val unnamed = newVm {
            ConnectionOutcome.ProtocolMismatch(
                expectedVersion = PROTOCOL_VERSION,
                daemonVersion = "",
                message = "the daemon answered protocol_version \"\""
            )
        }
        unnamed.testConnection("100.64.0.5", "9911", null, "sekrit")
        assertEquals(
            "Liftoff speaks rho protocol $liftoffMajor.x, the daemon did not say which version it speaks",
            awaitRow(unnamed, "the mismatch row for a daemon that named no version").message
        )
    }

    // Brief: "unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'". One
    // sentence for a socket that would not connect and for a daemon that never answered; the client's
    // own reason rides under it as the note, naming whichever setting is at fault.
    @Test
    fun anUnreachableOrTimedOutDaemonShowsTheOfflineRow() {
        store = newStore("offline.preferences_pb")
        val timedOut = newVm { ConnectionOutcome.Unreachable("no answer within 10 seconds") }
        timedOut.testConnection("100.64.0.5", "9911", null, "sekrit")

        val row = awaitRow(timedOut, "the offline row")
        assertEquals("Mission Control offline — check the PC and Tailscale", row.message)
        assertEquals("no answer within 10 seconds", row.note)

        val noSocket = newVm { ConnectionOutcome.Unreachable("Failed to connect to /100.64.0.5:9911") }
        noSocket.testConnection("100.64.0.5", "9911", null, "sekrit")
        val other = awaitRow(noSocket, "the offline row for a socket that would not connect")
        assertEquals("Mission Control offline — check the PC and Tailscale", other.message)
        assertEquals("Failed to connect to /100.64.0.5:9911", other.note)
    }

    // Brief: "auth rejected: a clear message saying the token was refused". The daemon said no to the
    // token, not to the address, so this row says the token and is not the offline row.
    @Test
    fun aRefusedTokenShowsThatTheTokenWasRefused() {
        store = newStore("refused.preferences_pb")
        val vm = newVm { ConnectionOutcome.AuthenticationRejected }

        vm.testConnection("100.64.0.5", "9911", null, "the-wrong-sekrit")

        val row = awaitRow(vm, "the refused-token row")
        assertEquals("Mission Control refused this token — check it matches the PC", row.message)
        assertNotEquals(
            "a refused token is not the same row as an offline daemon",
            "Mission Control offline — check the PC and Tailscale",
            row.message
        )
    }

    // Brief: "empty host or token: a clear message saying which is missing". NotConfigured names no
    // field, so the draft says which one was blanked — and a blank field never reaches the daemon.
    @Test
    fun missingHostAndMissingTokenEachSayWhichSettingIsMissing() {
        store = newStore("missing.preferences_pb")
        val checks = AtomicInteger()
        val check: DaemonCheck = { checks.incrementAndGet(); ConnectionOutcome.NotConfigured }

        val noHost = newVm(check)
        noHost.testConnection("", "9911", null, "sekrit")
        assertEquals(
            "No daemon host — set the PC's address",
            awaitRow(noHost, "the missing-host row").message
        )

        val hostIsOnlySpaces = newVm(check)
        hostIsOnlySpaces.testConnection("   ", "9911", null, "sekrit")
        assertEquals(
            "No daemon host — set the PC's address",
            awaitRow(hostIsOnlySpaces, "the row for a host that is only spaces").message
        )

        val noToken = newVm(check)
        noToken.testConnection("100.64.0.5", "9911", null, "")
        assertEquals("No daemon token", awaitRow(noToken, "the missing-token row").message)

        val tokenIsOnlySpaces = newVm(check)
        tokenIsOnlySpaces.testConnection("100.64.0.5", "9911", null, "  ")
        assertEquals(
            "No daemon token",
            awaitRow(tokenIsOnlySpaces, "the row for a token that is only spaces").message
        )

        val neither = newVm(check)
        neither.testConnection("", "9911", null, "")
        assertEquals(
            "No daemon host and no daemon token",
            awaitRow(neither, "the row naming both settings").message
        )

        assertEquals("a blank setting reached the daemon", 0, checks.get())
        assertEquals("a blank setting was stored as the host", "", stored().daemonHost)
        assertEquals("a blank setting was stored as the token", "", stored().daemonToken)
    }

    // Brief: "While the test runs, show that it's working and ignore a second tap." The fake handshake is
    // parked on a latch, so the worker is still inside the check when the second tap arrives and only the
    // guard can stop it: the state stays the busy one, the handshake runs once, and the first answer survives.
    @Test
    fun aSecondTapWhileTheTestIsRunningChangesNothing() {
        store = newStore("second-tap.preferences_pb")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checks = AtomicInteger()
        val vm = newVm {
            checks.incrementAndGet()
            started.countDown()
            assertTrue("the parked handshake was never released", release.await(WAIT_MS, TimeUnit.MILLISECONDS))
            ConnectionOutcome.Connected(daemonVersion = "1.4", protocolVersion = "1.0")
        }
        try {
            vm.testConnection("100.64.0.5", "9911", null, "sekrit")
            assertTrue("the parked handshake never started", started.await(WAIT_MS, TimeUnit.MILLISECONDS))
            assertEquals("Testing the daemon…", vm.state.value.message)

            vm.testConnection("100.64.0.9", "9912", null, "a-different-token")

            assertEquals("a second tap moved the state off the busy row", ConnectionState.Testing, vm.state.value)
        } finally {
            release.countDown()
        }

        val row = awaitRow(vm, "the first test's row")
        assertEquals("Mission Control answered — daemon 1.4, protocol 1.0", row.message)
        assertEquals("a second tap ran a second handshake", 1, checks.get())
        // The guard returned before any write, so the second tap's fields never reached the store either.
        assertEquals("100.64.0.5", stored().daemonHost)
        assertEquals(9911, stored().daemonPort)
        assertEquals("sekrit", stored().daemonToken)
    }

    // Brief: "Run it off the main thread." Both halves of the action — the settings write and the handshake
    // that waits for it — ran on the injected worker dispatcher, and the thread that asked for the test
    // appears in neither.
    @Test
    fun theConnectionTestRunsOffTheCallingThread() {
        val writes = CopyOnWriteArrayList<String>()
        store = newStoreRecordingItsWrites("off-thread.preferences_pb", writes)
        val callingThread = Thread.currentThread().name
        val checkThread = AtomicReference<String>()
        val vm = newVm {
            checkThread.set(Thread.currentThread().name)
            ConnectionOutcome.Connected(daemonVersion = "1.4", protocolVersion = "1.0")
        }

        vm.testConnection("100.64.0.5", "9911", null, "sekrit")

        awaitRow(vm, "the row the worker produced")
        assertTrue("the thread that ran this test is named like the worker", !callingThread.startsWith(WORKER_THREAD))
        val ran = checkThread.get() ?: throw AssertionError("the fake handshake never ran")
        assertTrue("the handshake ran on $ran, not on the worker", ran.startsWith(WORKER_THREAD))
        assertTrue("the handshake ran on the thread that asked for it ($ran)", ran != callingThread)
        assertEquals("the action wrote host, port and token", 3, writes.size)
        for ((index, thread) in writes.withIndex()) {
            assertTrue("settings write $index ran on $thread, not the worker", thread.startsWith(WORKER_THREAD))
            assertTrue("settings write $index ran on the caller ($thread)", thread != callingThread)
        }
    }

    // Brief: "If the owner has unsaved edits in the connection fields, they are saved first." The fields hold
    // a host, port and token the store has never seen; the fake handshake reads the store back, so what it
    // finds is what the action had already written and finished waiting for.
    @Test
    fun theSettingsInTheFieldsAreSavedBeforeTheCheckRuns() {
        store = newStore("saved-first.preferences_pb")
        runBlocking {
            store.setDaemonHost("10.0.0.1")
            store.setDaemonPort(8737)
            store.setDaemonToken("the-old-token")
        }

        val seen = AtomicReference<Settings>()
        val vm = newVm {
            seen.set(withTimeout(WAIT_MS) { store.settings.first() })
            ConnectionOutcome.Connected(daemonVersion = "1.4", protocolVersion = "1.0")
        }

        vm.testConnection("100.64.0.9", "9912", null, "the-new-token")

        awaitRow(vm, "the row the handshake produced")
        val atCheckTime = seen.get() ?: throw AssertionError("the fake handshake never read the store")
        assertEquals(
            "the handshake aimed at the host in the fields, not the stored one",
            "100.64.0.9",
            atCheckTime.daemonHost
        )
        assertEquals("the handshake aimed at the port in the fields", 9912, atCheckTime.daemonPort)
        assertEquals("the handshake aimed at the token in the fields", "the-new-token", atCheckTime.daemonToken)

        // What the action does not save: an invalid port is never stored, so it reports the field's own error
        // instead of checking against the last port that was good.
        val checks = AtomicInteger()
        val check: DaemonCheck = {
            checks.incrementAndGet()
            ConnectionOutcome.Connected(daemonVersion = "1.4", protocolVersion = "1.0")
        }
        val badPort = newVm(check)
        badPort.testConnection("100.64.0.77", "not-a-port", "Port must be a number from 1 to 65535", "the-new-token")
        val row = awaitRow(badPort, "the port row")
        assertEquals("Port must be a number from 1 to 65535", row.message)
        assertEquals("an invalid port opened a socket", 0, checks.get())
        assertEquals("the garbage port reached the store", 9912, stored().daemonPort)
        assertEquals("the host behind an invalid port reached the store", "100.64.0.9", stored().daemonHost)

        // A blank field is the same: it names the setting, reaches no seam, and does not wipe the store.
        val blankHost = newVm(check)
        blankHost.testConnection("", "9912", null, "the-new-token")
        assertEquals(
            "No daemon host — set the PC's address",
            awaitRow(blankHost, "the missing-host row").message
        )
        assertEquals("a blank field reached the seam", 0, checks.get())
        assertEquals("a blank field wiped the stored host", "100.64.0.9", stored().daemonHost)
    }

    private companion object {
        private const val WORKER_THREAD = "connection-worker-under-test"
        private const val WAIT_MS = 5_000L
    }
}

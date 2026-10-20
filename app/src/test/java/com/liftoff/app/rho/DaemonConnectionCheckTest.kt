package com.liftoff.app.rho

import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.protocol.PROTOCOL_VERSION
import com.liftoff.app.rho.protocol.RhoJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DaemonConnectionCheck] against [FakeDaemon]: plain JUnit 4, no Robolectric, no real host.
 *
 * Every wait is bounded, because a check that hangs instead of answering is one of the failures
 * these tests exist to catch, and MockWebServer will not shut down while either end is open.
 */
class DaemonConnectionCheckTest {
    private lateinit var daemon: FakeDaemon

    /** The check's own cap plus room for the fake to answer, so a hang fails this test, not the build. */
    private val answerWindow = DaemonConnectionCheck.TIMEOUT_MS + 5_000

    @Before
    fun startDaemon() {
        daemon = FakeDaemon()
        daemon.start()
    }

    @After
    fun stopDaemon() {
        daemon.stop()
    }

    private fun checkAt(
        host: String = daemon.host(),
        port: Int = daemon.port(),
        token: String = "sekrit",
    ) = DaemonConnectionCheck(daemonHost = host, port = port, daemonToken = token)

    /** "connected, with the daemon version and protocol version": each exactly as the fake sent it. */
    @Test
    fun helloSuccessReportsTheDaemonsVersionAndProtocolVersion(): Unit = runBlocking {
        // A MINOR this client doesn't know is still a daemon it can speak to (DESIGN 10), and a
        // version that isn't PROTOCOL_VERSION is what proves it came off the wire.
        daemon.answerHelloWithProtocol(daemonProtocolVersion = "1.1", daemonVersion = "0.0.0")

        val outcome = withTimeout(answerWindow) { checkAt().check() }

        assertTrue("expected Connected, got $outcome", outcome is ConnectionOutcome.Connected)
        val connected = outcome as ConnectionOutcome.Connected
        assertEquals("0.0.0", connected.daemonVersion)
        assertEquals("1.1", connected.protocolVersion)
    }

    /** "run rho.hello with client liftoff": the frame the fake received, and the token it arrived with. */
    @Test
    fun theHelloNamesTheClientLiftoffAndCarriesTheStoredToken(): Unit = runBlocking {
        val outcome = withTimeout(answerWindow) { checkAt(token = "sekrit").check() }
        assertTrue("expected Connected, got $outcome", outcome is ConnectionOutcome.Connected)

        val hello = RhoJson.decodeFromString(Rpc.serializer(), helloFrame())
        assertEquals("rho.hello", hello.method)
        val params = hello.params?.jsonObject
        assertEquals(DaemonConnectionCheck.CLIENT_NAME, params?.get("client")?.jsonPrimitive?.contentOrNull)
        assertEquals(DaemonConnectionCheck.CLIENT_VERSION, params?.get("client_version")?.jsonPrimitive?.contentOrNull)
        assertEquals(PROTOCOL_VERSION, params?.get("protocol_version")?.jsonPrimitive?.contentOrNull)

        // The token is what the daemon requires from anything but loopback, and it travels in the
        // upgrade request, not in the frame.
        assertEquals("Bearer sekrit", daemon.requests.firstOrNull()?.getHeader("Authorization"))
    }

    /**
     * "the protocol major version doesn't match the one the copied client expects": both the refusal
     * rho's daemon sends (-32006) and a daemon that answers anyway with a major this client doesn't
     * speak. Neither is unreachable, and both versions are named.
     */
    @Test
    fun aProtocolMajorMismatchIsReportedAsAMismatchAndNamesBothVersions(): Unit = runBlocking {
        daemon.refuseProtocolMismatch(daemonProtocolVersion = "2.0")
        val refused = withTimeout(answerWindow) { checkAt().check() }

        assertTrue("expected ProtocolMismatch, got $refused", refused is ConnectionOutcome.ProtocolMismatch)
        val refusal = refused as ConnectionOutcome.ProtocolMismatch
        assertEquals(PROTOCOL_VERSION, refusal.expectedVersion)
        assertEquals("2.0", refusal.daemonVersion)
        assertEquals(
            "rho_protocol_mismatch: protocol version $PROTOCOL_VERSION is incompatible with daemon 2.0",
            refusal.message,
        )

        daemon.refuseMethod.clear()
        daemon.answerHelloWithProtocol(daemonProtocolVersion = "2.0", daemonVersion = "0.0.0")
        val answered = withTimeout(answerWindow) { checkAt().check() }

        assertTrue("expected ProtocolMismatch, got $answered", answered is ConnectionOutcome.ProtocolMismatch)
        val mismatch = answered as ConnectionOutcome.ProtocolMismatch
        assertEquals(PROTOCOL_VERSION, mismatch.expectedVersion)
        assertEquals("2.0", mismatch.daemonVersion)
    }

    /** "unreachable or timed out": nothing is listening on that port, and the check answers anyway. */
    @Test
    fun aConnectionNobodyAcceptsIsReportedAsUnreachableWithinTheChecksTimeout(): Unit = runBlocking {
        val host = daemon.host()
        val port = daemon.port()
        daemon.stop() // the port is free again, so nothing accepts a connection on it

        val outcome = withTimeout(answerWindow) { checkAt(host = host, port = port).check() }

        assertTrue("expected Unreachable, got $outcome", outcome is ConnectionOutcome.Unreachable)
    }

    /** "the host or token is empty": each on its own, and no socket opened at all. */
    @Test
    fun emptySettingsAreReportedAsNotConfiguredAndOpenNoConnection(): Unit = runBlocking {
        val blankHost = withTimeout(answerWindow) { checkAt(host = "").check() }
        val blankToken = withTimeout(answerWindow) { checkAt(token = "   ").check() }

        assertEquals(ConnectionOutcome.NotConfigured, blankHost)
        assertEquals(ConnectionOutcome.NotConfigured, blankToken)
        assertEquals(
            "a connection was opened for settings that should have been refused: ${daemon.requests}",
            0,
            daemon.requestCount(),
        )
    }

    /** "authentication rejected": the daemon answers the upgrade with 401, before any JSON-RPC. */
    @Test
    fun aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable(): Unit = runBlocking {
        daemon.refuseUpgrade = true

        val outcome = withTimeout(answerWindow) { checkAt(token = "not the token").check() }

        assertEquals(ConnectionOutcome.AuthenticationRejected, outcome)
    }

    private fun helloFrame(): String {
        val frames = daemon.framesFor("rho.hello")
        assertTrue("the fake received no rho.hello; it received ${daemon.frames}", frames.isNotEmpty())
        return frames.first()
    }
}

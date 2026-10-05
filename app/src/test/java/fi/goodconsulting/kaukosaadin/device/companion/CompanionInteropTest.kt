package fi.goodconsulting.kaukosaadin.device.companion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Optional cross-implementation check against pinned pyatv's fake Companion Apple TV
 * (tools/companion_fake_atv.py). Skipped unless COMPANION_PYATV_PYTHON (venv python) and
 * COMPANION_PYATV_REF (pinned checkout) are set; see docs/apple-tv-companion.md.
 */
class CompanionInteropTest {
    private lateinit var peer: Process
    private val lines = LinkedBlockingQueue<String>()
    private var port = 0
    private val info = CompanionClientInfo("Kaukosaadin test", "cafecafecafe", "AA:BB:CC:DD:EE:FF")

    @Before fun startPeer() {
        val python = System.getenv("COMPANION_PYATV_PYTHON")
        val reference = System.getenv("COMPANION_PYATV_REF")
        assumeTrue("pyatv interop peer not configured", python != null && reference != null)
        val script =
            generateSequence(
                File("").absoluteFile,
            ) { it.parentFile }.map { File(it, "tools/companion_fake_atv.py") }.first { it.exists() }
        peer = ProcessBuilder(python, script.path, reference).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        Thread { peer.inputStream.bufferedReader().forEachLine { lines.put(it) } }.apply { isDaemon = true }.start()
        port = next().removePrefix("PORT ").toInt()
    }

    @After fun stopPeer() {
        if (::peer.isInitialized) {
            peer.outputStream.close()
            peer.destroy()
        }
    }

    private fun next() = checkNotNull(lines.poll(15, TimeUnit.SECONDS)) { "pyatv peer said nothing" }

    private fun open() = CompanionLink.open(InetAddress.getLoopbackAddress(), port)

    @Test fun pairVerifyAndPressMenuAndHome() {
        val credentials =
            open().use { link ->
                val pending = link.startPairing()
                link.finishPairing(pending, "1111", "Kaukosaadin test")
            }
        assertEquals("PAIRED", next())
        // pyatv must be able to parse what we would save.
        assertEquals(credentials.encode(), CompanionCredentials.decode(credentials.encode()).encode())
        open().use { link ->
            link.verify(credentials)
            assertEquals("VERIFIED", next())
            link.startSession(info, credentials)
            assertEquals("SESSION com.apple.tvremoteservices", next())
            assertEquals("TVRC_SESSION", next())
            assertEquals("TEXT_SESSION", next())
            // One verified session handles successive presses, including a pause between them.
            for (command in listOf(HidCommand.Menu, HidCommand.Home, HidCommand.Menu)) {
                link.press(command)
                assertEquals("BUTTON ${command.name.lowercase()}", next())
                Thread.sleep(100)
            }
            // Volume: the peer reports pyatv's own names (volume_up/volume_down), not our enum names.
            link.press(HidCommand.VolumeUp)
            assertEquals("BUTTON volume_up", next())
            link.press(HidCommand.VolumeDown)
            assertEquals("BUTTON volume_down", next())
            // Power-off is the release event only (pyatv `CompanionPower.turn_off`); the peer accepts it.
            link.sleep()
            link.stopSession()
            assertTrue(link.closed)
            assertThrows(IllegalStateException::class.java) { link.press(HidCommand.Menu) }
        }
        // Returning to the remote starts a fresh session using the same saved pairing.
        for (action in listOf(PressAction.DoubleTap, PressAction.Hold)) {
            open().use { link ->
                link.verify(credentials)
                assertEquals("VERIFIED", next())
                link.startSession(info, credentials)
                assertEquals("SESSION com.apple.tvremoteservices", next())
                assertEquals("TVRC_SESSION", next())
                assertEquals("TEXT_SESSION", next())
                val started = System.nanoTime()
                link.press(HidCommand.Home, action)
                repeat(if (action == PressAction.DoubleTap) 2 else 1) { assertEquals("BUTTON home", next()) }
                if (action == PressAction.Hold) assertTrue(System.nanoTime() - started >= 1_000_000_000L)
                link.stopSession()
            }
        }
    }

    @Test fun wrongPinIsRejectedAndNotRetried() {
        open().use { link ->
            val pending = link.startPairing()
            val error = assertThrows(CompanionRejected::class.java) { link.finishPairing(pending, "2222", "Kaukosaadin test") }
            assertEquals("Wrong PIN. Start pairing again for a new PIN.", error.message)
            assertThrows(IllegalStateException::class.java) { link.finishPairing(pending, "1111", "Kaukosaadin test") }
        }
    }

    @Test fun verifyRejectsCredentialsForAnotherDevice() {
        val credentials = open().use { it.finishPairing(it.startPairing(), "1111", "Kaukosaadin test") }
        val wrongDevice = CompanionCredentials(credentials.ltpk, credentials.ltsk, "someone-else".toByteArray(), credentials.clientId)
        open().use { link -> assertThrows(SecurityException::class.java) { link.verify(wrongDevice) } }
        val wrongKey =
            CompanionCredentials(
                ByteArray(32) { 7 }.let(CompanionCrypto::signingPublic),
                credentials.ltsk,
                credentials.atvId,
                credentials.clientId,
            )
        open().use { link -> assertThrows(SecurityException::class.java) { link.verify(wrongKey) } }
        // The TV checks our signature too: a stale/forgotten pairing must fail closed.
        val revoked = CompanionCredentials(credentials.ltpk, ByteArray(32) { 9 }, credentials.atvId, credentials.clientId)
        open().use { link ->
            val error = assertThrows(CompanionRejected::class.java) { link.verify(revoked) }
            assertEquals("Apple TV no longer accepts this pairing. Forget it and pair again.", error.message)
            assertThrows(IllegalStateException::class.java) { link.startSession(info, revoked) }
        }
        assertEquals("PAIRED", next())
        assertEquals("VERIFY_REJECTED", next())
    }

    @Test fun appListAndLaunchReachThePeer() {
        val credentials = open().use { it.finishPairing(it.startPairing(), "1111", "Kaukosaadin test") }
        assertEquals("PAIRED", next())
        open().use { link ->
            link.verify(credentials)
            assertEquals("VERIFIED", next())
            link.startSession(info, credentials)
            assertEquals("SESSION com.apple.tvremoteservices", next())
            assertEquals("TVRC_SESSION", next())
            assertEquals("TEXT_SESSION", next())
            // pyatv's fake reports three fixed apps; the client sorts them by displayed name.
            assertEquals(
                listOf("Netflix", "TV", "YouTube"),
                link.appList().map { it.name },
            )
            assertEquals("APPS 3", next())
            link.launchApp("com.google.ios.youtube")
            assertEquals("LAUNCH com.google.ios.youtube", next())
            // A blank id is rejected before it reaches the wire; that failure also drops the link by design.
            assertThrows(IllegalArgumentException::class.java) { link.launchApp("  ") }
        }
    }

    @Test fun keyboardFocusAndTextMirrorToPeer() {
        val credentials = open().use { it.finishPairing(it.startPairing(), "1111", "Kaukosaadin test") }
        assertEquals("PAIRED", next())
        open().use { link ->
            val keyboard = LinkedBlockingQueue<CompanionKeyboardState>()
            link.keyboardListener = { keyboard.put(it) }
            link.verify(credentials)
            assertEquals("VERIFIED", next())
            link.startSession(info, credentials)
            assertEquals("SESSION com.apple.tvremoteservices", next())
            assertEquals("TVRC_SESSION", next())
            assertEquals("TEXT_SESSION", next())
            // tvOS reports focus in the _tiStart reply when the keyboard is already up.
            assertEquals(CompanionKeyboardState(true, "Fake Companion Keyboard Text"), keyboard.poll(5, TimeUnit.SECONDS))
            // pyatv reads our NSKeyedArchiver clear/insert payloads back into its own fake keyboard.
            link.typeText("hello")
            assertEquals("TEXT ", next())
            assertEquals("TEXT hello", next())
            peer.outputStream.write("focus off\n".toByteArray())
            peer.outputStream.flush()
            assertEquals("FOCUS off", next())
            assertEquals(CompanionKeyboardState(false, ""), keyboard.poll(5, TimeUnit.SECONDS))
            peer.outputStream.write("focus on\n".toByteArray())
            peer.outputStream.flush()
            assertEquals("FOCUS on", next())
            assertEquals(CompanionKeyboardState(true, "hello"), keyboard.poll(5, TimeUnit.SECONDS))
            link.stopSession()
        }
    }
}

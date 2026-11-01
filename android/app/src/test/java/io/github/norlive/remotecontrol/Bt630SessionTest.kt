package io.github.norlive.remotecontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Bt630SessionTest {
    private class Link(val openAction: suspend () -> Unit = {}) : Bt630Session.Link {
        var closed = false
        var closeAction: () -> Unit = {}
        val frames = mutableListOf<ByteArray>()
        var sendAction: suspend () -> Unit = {}
        override suspend fun open() = openAction()
        override suspend fun send(frame: ByteArray) { frames += frame.copyOf(); sendAction() }
        override fun close() { closed = true; closeAction() }
    }

    private class Harness(scope: TestScope, val create: (Int) -> Link = { Link() }) {
        val links = mutableListOf<Link>()
        val states = mutableListOf<Bt630Session.State>()
        val events = mutableListOf<String>()
        val losses = mutableListOf<(String) -> Unit>()
        var value: Byte = 0
        var enabled = true
        val session = Bt630Session(scope, { lost ->
            assertTrue(links.lastOrNull()?.closed ?: true)
            losses += lost
            create(links.size + 1).also { links += it; events += "open" }
        }, { byteArrayOf(if (enabled) 1 else 0, value) }, {
            enabled = false
            events += "pause"
        }, { states += it }, { events += "sent" })
    }

    @Test fun twoFailuresThenThirdOpenSucceedsAfterOneAndTwoSeconds() = runTest {
        val h = Harness(this) { n -> Link { if (n < 3) error("temporary") } }
        h.session.start(); runCurrent()
        assertEquals(1, h.links.size)
        advanceTimeBy(999); runCurrent(); assertEquals(1, h.links.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, h.links.size)
        advanceTimeBy(1999); runCurrent(); assertEquals(2, h.links.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, h.links.size)
        assertEquals(Bt630Session.State.Ready, h.states.last())
        h.session.stop()
    }

    @Test fun transientFailuresContinuePastSixAttemptsWithFiveSecondCapUntilCancelled() = runTest {
        val h = Harness(this) { Link { error("temporary") } }
        h.session.start(); runCurrent()
        for ((time, count) in listOf(999L to 1, 1_000L to 2, 3_000L to 3, 7_000L to 4,
            11_999L to 4, 12_000L to 5, 16_999L to 5, 17_000L to 6, 22_000L to 7)) {
            advanceTimeBy(time-testScheduler.currentTime);runCurrent()
            assertEquals("Attempts at $time ms",count,h.links.size)
            assertTrue(h.links.all { it.closed })
        }
        assertTrue(h.states.last() is Bt630Session.State.Connecting)
        h.session.stop();advanceTimeBy(100_000);runCurrent()
        assertEquals(7,h.links.size)
        assertEquals(Bt630Session.State.Idle,h.states.last())
    }

    @Test fun unstableReadyAndWriteFailuresKeepRetryingWithControlsPaused() = runTest {
        val h = Harness(this) { Link().apply { sendAction = { error("write") } } }
        h.session.start(); advanceTimeBy(17_000); runCurrent()
        assertEquals(6, h.links.size)
        assertTrue(h.states.last() is Bt630Session.State.Connecting)
        assertFalse(h.enabled)
        assertEquals("pause", h.events[h.events.indexOf("open", h.events.indexOf("open") + 1) - 1])
        h.session.stop()
    }

    @Test fun tenSecondsReadyResetsBackoffForTheNextLoss() = runTest {
        val h = Harness(this) { n -> Link { if (n < 5) error("open") } }
        h.session.start(); advanceTimeBy(12_000); runCurrent()
        assertEquals(5, h.links.size)
        advanceTimeBy(10_000); runCurrent()
        h.losses.last()("lost"); runCurrent()
        advanceTimeBy(999);runCurrent();assertEquals(5,h.links.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(6, h.links.size)
        assertEquals(1, h.states.filterIsInstance<Bt630Session.State.Connecting>().last().attempt)
        h.session.stop()
    }

    @Test fun lossJustBeforeTenSecondsKeepsFiveSecondBackoff() = runTest {
        val h = Harness(this) { n -> Link { if (n < 5) error("open") } }
        h.session.start(); advanceTimeBy(12_000); runCurrent()
        advanceTimeBy(9_999); runCurrent()
        h.losses.last()("lost"); runCurrent()
        advanceTimeBy(4_999);runCurrent();assertEquals(5,h.links.size)
        advanceTimeBy(1);runCurrent();assertEquals(6,h.links.size)
        h.session.stop()
    }

    @Test fun securityFailureStopsWithoutRetry() = runTest {
        val h = Harness(this) { Link { throw SecurityException("permission revoked") } }
        h.session.start();advanceTimeBy(100_000);runCurrent()
        assertEquals(1,h.links.size)
        assertTrue(h.states.last() is Bt630Session.State.Failed)
    }

    @Test fun stopDuringConnectClosesImmediatelyAndIgnoresLateLoss() = runTest {
        val h = Harness(this) { Link { awaitCancellation() } }
        h.session.start(); runCurrent(); h.session.stop()
        assertTrue(h.links.single().closed)
        h.losses.single()("late"); advanceTimeBy(100_000); runCurrent()
        assertEquals(1, h.links.size)
        assertEquals(Bt630Session.State.Idle, h.states.last())
    }

    @Test fun stopDuringBackoffPreventsAnyFurtherOpen() = runTest {
        val h = Harness(this) { Link { error("open") } }
        h.session.start(); runCurrent(); h.session.stop()
        advanceTimeBy(100_000); runCurrent()
        assertEquals(1, h.links.size)
        assertEquals(Bt630Session.State.Idle, h.states.last())
    }

    @Test fun stopDuringSendClosesImmediatelyAndDoesNotRetry() = runTest {
        val h = Harness(this) { Link().apply { sendAction = { awaitCancellation() } } }
        h.session.start(); runCurrent(); h.session.stop()
        assertTrue(h.links.single().closed)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(1, h.links.size)
        assertEquals(Bt630Session.State.Idle, h.states.last())
    }

    @Test fun onlyLatestFrameIsSampledAfterAnInFlightSendAndReconnectIsDisabled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this) { n -> Link().apply { if (n == 1) sendAction = { gate.await() } } }
        h.session.start(); runCurrent()
        val first = h.links.single()
        assertArrayEquals(byteArrayOf(0, 0), first.frames.single())
        h.value = 1; advanceTimeBy(20); h.value = 2; advanceTimeBy(20); h.value = 3
        assertEquals(1, first.frames.size)
        gate.complete(Unit); runCurrent(); advanceTimeBy(20); runCurrent()
        assertArrayEquals(byteArrayOf(0, 3), first.frames.last())
        h.enabled = true
        h.losses.first()("lost"); runCurrent()
        assertFalse(h.enabled)
        advanceTimeBy(1_000); runCurrent()
        assertArrayEquals(byteArrayOf(0, 3), h.links.last().frames.first())
        h.losses.first()("stale"); runCurrent()
        assertEquals(Bt630Session.State.Ready, h.states.last())
        h.session.stop()
    }

    @Test fun permanentFailuresStopWithoutBackoff() = runTest {
        val h = Harness(this) { Link { throw Bt630Failure("UUID mismatch", retryable = false) } }
        h.session.start(); advanceTimeBy(100_000); runCurrent()
        assertEquals(1, h.links.size)
        assertTrue(h.states.last() is Bt630Session.State.Failed)
    }

    @Test fun lostDuringBlockedWriteCancelsItAndPausesBeforeReconnect() = runTest {
        val h = Harness(this) { n -> Link().apply { if (n == 1) sendAction = { awaitCancellation() } } }
        h.session.start(); runCurrent(); h.enabled = true
        h.losses.first()("lost"); runCurrent()
        assertTrue(h.links.first().closed)
        assertFalse(h.enabled)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, h.links.size)
        h.session.stop()
    }

    @Test fun lossImmediatelyLeavesReadyDuringBackoff() = runTest {
        val h = Harness(this)
        h.session.start(); runCurrent()
        assertEquals(Bt630Session.State.Ready, h.states.last())
        h.losses.single()("lost"); runCurrent()
        assertTrue(h.states.last() is Bt630Session.State.Connecting)
        assertEquals(1, h.links.size)
        h.session.stop()
    }

    @Test fun stopPausesAndPublishesIdleEvenWhenLinkCloseThrows() = runTest {
        val h = Harness(this) { Link().apply { closeAction = { throw SecurityException("revoked") } } }
        h.session.start(); runCurrent()
        h.session.stop()
        assertFalse(h.enabled)
        assertEquals(Bt630Session.State.Idle, h.states.last())
    }

    @Test fun lossInsideOpenCannotPublishReadyOrSend() = runTest {
        lateinit var h: Harness
        h = Harness(TestScope(UnconfinedTestDispatcher(testScheduler))) { Link { h.losses.last()("disconnected") } }
        h.session.start(); runCurrent()
        assertFalse(h.states.contains(Bt630Session.State.Ready))
        assertTrue(h.links.first().frames.isEmpty())
        h.session.stop()
    }

    @Test fun restartCancelsOldConnectionWithoutLettingItsCleanupAffectNewLink() = runTest {
        val h = Harness(this) { n -> Link { if (n == 1) awaitCancellation() } }
        h.session.start(); runCurrent()
        h.session.start(); runCurrent()
        assertEquals(2, h.links.size)
        assertTrue(h.links.first().closed)
        assertFalse(h.links.last().closed)
        h.losses.first()("old failure"); runCurrent()
        assertEquals(Bt630Session.State.Ready, h.states.last())
        h.session.stop()
    }

    @Test fun openDeadlineClosesBeforeRetrying() = runTest {
        val h = Harness(this) { Link { awaitCancellation() } }
        h.session.start(); runCurrent()
        advanceTimeBy(9_999); runCurrent(); assertFalse(h.links.single().closed)
        advanceTimeBy(1); runCurrent(); assertTrue(h.links.single().closed)
        advanceTimeBy(1_000); runCurrent(); assertEquals(2, h.links.size)
        h.session.stop()
    }

    @Test fun synchronousStopFromReadyCallbackCannotSendAnyFrame() = runTest {
        val immediateScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val link = Link()
        lateinit var session: Bt630Session
        session = Bt630Session(immediateScope, { link }, { byteArrayOf(1) }, {}, {
            if (it == Bt630Session.State.Ready) session.stop()
        }, {})
        try {
            session.start(); runCurrent()
            assertTrue(link.closed)
            assertTrue(link.frames.isEmpty())
        } finally { immediateScope.cancel() }
    }

    private fun <T> List<T>.indexOf(value: T, start: Int): Int =
        (start until size).first { this[it] == value }
}

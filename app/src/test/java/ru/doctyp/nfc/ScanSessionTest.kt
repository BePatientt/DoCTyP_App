package ru.doctyp.nfc
import org.junit.Assert.*
import org.junit.Test
class ScanSessionTest {
    @Test fun timeoutOnlyBeforeDetection() {
        val s = ScanSession(); val token = s.start()
        assertTrue(s.detected(token)); assertFalse(s.timeout(token))
        assertTrue(s.complete(token)); assertEquals(ScanSession.State.COMPLETE, s.state)
    }
    @Test fun oldTimeoutCannotCancelRepeatedScan() {
        val s = ScanSession(); val old = s.start(); s.cancel(); val fresh = s.start()
        assertFalse(s.timeout(old)); assertFalse(s.detected(old)); assertTrue(s.detected(fresh))
    }
    @Test fun pauseRejectsLateCompletionAndDetection() {
        val s = ScanSession(); val token = s.start(); s.detected(token); s.cancel()
        assertFalse(s.complete(token)); assertFalse(s.detected(token)); assertFalse(s.active())
    }
    @Test fun noTagIsNotDetection() {
        val s = ScanSession(); val token = s.start()
        assertTrue(s.timeout(token)); assertEquals(ScanSession.State.NO_TAG, s.state)
        assertFalse(s.detected(token)); assertFalse(s.complete(token))
    }
    @Test fun onlyOneTagPerSession() {
        val s = ScanSession(); val token = s.start()
        assertTrue(s.detected(token)); assertFalse(s.detected(token))
    }
}

package ru.doctyp.nfc

/** Tokens invalidate queued callbacks and timeouts after stop, pause or a new scan. */
class ScanSession {
    enum class State { IDLE, SCANNING, DETECTED, COMPLETE, NO_TAG, CANCELLED }
    var state = State.IDLE; private set
    private var generation = 0L
    fun start(): Long { generation++; state = State.SCANNING; return generation }
    fun detected(token: Long): Boolean {
        if (token != generation || state != State.SCANNING) return false
        state = State.DETECTED; return true
    }
    fun complete(token: Long): Boolean {
        if (token != generation || state != State.DETECTED) return false
        state = State.COMPLETE; return true
    }
    fun timeout(token: Long): Boolean {
        if (token != generation || state != State.SCANNING) return false
        state = State.NO_TAG; return true
    }
    fun cancel() { generation++; if (state == State.SCANNING || state == State.DETECTED) state = State.CANCELLED }
    fun active() = state == State.SCANNING || state == State.DETECTED
}

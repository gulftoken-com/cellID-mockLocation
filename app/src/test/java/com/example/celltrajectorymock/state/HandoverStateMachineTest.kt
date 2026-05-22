package com.example.celltrajectorymock.state

import com.example.celltrajectorymock.telephony.CellSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoverStateMachineTest {

    /** Captured (previous, current) pairs from onValidHandover. */
    private val events = mutableListOf<Pair<CellSample?, CellSample>>()

    private fun newMachine(guardMillis: Long = 5_000L) =
        HandoverStateMachine(guardMillis) { prev, curr -> events.add(prev to curr) }

    private fun cell(cid: Long, atMillis: Long) = CellSample(
        rat = "LTE", mcc = 234, mnc = 15, lacTac = 1000,
        cid = cid, dbm = -90, timestampMillis = atMillis
    )

    @Test
    fun firstSample_commitsImmediately_withNoPrevious() {
        val machine = newMachine()
        machine.onSample(cell(cid = 1, atMillis = 0))

        assertEquals(1, events.size)
        assertNull(events[0].first)
        assertEquals(1L, events[0].second.cid)
    }

    @Test
    fun stayingOnSameCell_producesNoHandover() {
        val machine = newMachine()
        machine.onSample(cell(cid = 1, atMillis = 0))
        machine.onSample(cell(cid = 1, atMillis = 1_000))
        machine.onSample(cell(cid = 1, atMillis = 2_000))

        assertEquals(1, events.size)   // only the initial lock
    }

    @Test
    fun pingPong_withinGuardWindow_isRejected() {
        val machine = newMachine()
        machine.onSample(cell(cid = 1, atMillis = 0))      // lock on A
        machine.onSample(cell(cid = 2, atMillis = 1_000))  // B candidate
        machine.onSample(cell(cid = 1, atMillis = 2_000))  // bounce back to A
        machine.onSample(cell(cid = 2, atMillis = 3_000))  // B again
        machine.onSample(cell(cid = 1, atMillis = 4_000))  // bounce back to A

        assertEquals(1, events.size)            // no handover committed for B
        assertEquals(1L, events[0].second.cid)
    }

    @Test
    fun candidate_outlivingGuardWindow_commitsHandover() {
        val machine = newMachine()
        machine.onSample(cell(cid = 1, atMillis = 0))      // lock on A
        machine.onSample(cell(cid = 2, atMillis = 1_000))  // B candidate
        machine.onSample(cell(cid = 2, atMillis = 7_000))  // B persisted > 5s

        assertEquals(2, events.size)
        assertEquals(1L, events[1].first?.cid)   // previous = A
        assertEquals(2L, events[1].second.cid)   // current  = B
    }

    @Test
    fun agedCandidate_isPromoted_evenWhenNextCellDiffers() {
        val machine = newMachine()
        machine.onSample(cell(cid = 1, atMillis = 0))      // lock on A
        machine.onSample(cell(cid = 2, atMillis = 1_000))  // B candidate
        machine.onSample(cell(cid = 3, atMillis = 7_000))  // C arrives; B aged out

        assertEquals(2, events.size)
        assertEquals(1L, events[1].first?.cid)   // A -> B still committed
        assertEquals(2L, events[1].second.cid)
    }
}

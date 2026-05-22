package com.example.celltrajectorymock.state

import com.example.celltrajectorymock.telephony.CellSample

/**
 * Debounces raw serving-cell observations into committed handover events.
 *
 * A newly seen cell becomes a "candidate". It is only committed once it has
 * been the serving cell for at least [guardMillis]. This rejects ping-pong —
 * rapid bouncing between two towers near a cell boundary — so the injected mock
 * fix does not teleport back and forth.
 *
 * Deviation from the spec's state diagram: the spec also treated "any unique
 * new cell" as instantly valid, which directly contradicts its own debounce
 * branch. Pure dwell-time debouncing is the self-consistent interpretation and
 * is what is implemented here.
 *
 * Not thread-safe: call [onSample] from a single thread.
 */
class HandoverStateMachine(
    private val guardMillis: Long = 5_000L,
    private val onValidHandover: (previous: CellSample?, current: CellSample) -> Unit
) {
    private var committedCurrent: CellSample? = null
    private var committedPrevious: CellSample? = null

    private var candidate: CellSample? = null
    private var candidateSince: Long = 0L

    fun onSample(sample: CellSample) {
        // First lock — commit immediately so there is an initial fix.
        if (committedCurrent == null) {
            commit(previous = null, next = sample)
            return
        }

        // Promote an aged candidate before evaluating the new sample. This
        // catches the case where cell updates arrive sparsely: a candidate set
        // long ago is genuine even if the next update is a different cell.
        candidate?.let { cand ->
            if (sample.timestampMillis - candidateSince >= guardMillis) {
                commit(previous = committedCurrent, next = cand)
                candidate = null
            }
        }

        val current = committedCurrent ?: return
        when {
            // Still on the committed serving cell: no handover, drop candidate.
            sample.sameCellAs(current) -> candidate = null

            // Candidate confirmed again and old enough: commit the handover.
            sample.sameCellAs(candidate) &&
                sample.timestampMillis - candidateSince >= guardMillis -> {
                commit(previous = committedCurrent, next = sample)
                candidate = null
            }

            // A cell different from both current and candidate: (re)open the
            // evaluation window. Replacing the candidate is how a ping-pong
            // bounce back to the previous tower gets silently rejected.
            !sample.sameCellAs(candidate) -> {
                candidate = sample
                candidateSince = sample.timestampMillis
            }

            // else: candidate seen again but still inside the guard window —
            // keep waiting.
        }
    }

    private fun commit(previous: CellSample?, next: CellSample) {
        committedPrevious = previous
        committedCurrent = next
        onValidHandover(previous, next)
    }
}

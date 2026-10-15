package dev.nanovoice.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Returns once nothing is being answered or read aloud, and has stayed so for [settleMs]. Between two sentences of a
 * reply, or between the end of a reply and the voice starting on it, both can be false for a moment: a short quiet
 * spell does not count.
 */
suspend fun awaitQuiet(busy: Flow<Boolean>, speaking: Flow<Boolean>, settleMs: Long = 1_200) {
    val active = combine(busy, speaking) { answering, talking -> answering || talking }
    while (true) {
        active.first { !it }
        if (withTimeoutOrNull(settleMs) { active.first { it } } == null) return
    }
}

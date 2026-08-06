package com.pearlnode.ui.viewmodels

import kotlinx.coroutines.delay

/**
 * Asks for something live, over and over, and gets going quickly.
 *
 * The awkward part is the first answer. A screen's device is read from the
 * database a moment after the screen appears, so the first attempt always finds
 * nothing to ask -- and a loop that then waits a full interval leaves the figure
 * showing a dash for as long as that interval lasts. Ten seconds of a dash reads
 * as "this is not supported" rather than as "not yet", which is the wrong thing
 * to say and the only thing anyone will remember.
 *
 * So: retry briskly until something arrives, then settle into the real interval.
 * Both screens do this and both got it wrong separately, which is what this is
 * here to stop.
 */
suspend fun pollLive(
    intervalMs: Long,
    firstTryMs: Long = 1_000L,
    attempt: suspend () -> Boolean,
) {
    var haveOne = false
    while (true) {
        if (attempt()) haveOne = true
        delay(if (haveOne) intervalMs else firstTryMs)
    }
}

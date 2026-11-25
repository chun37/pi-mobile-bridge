package com.chun.pibridge

/** Candidate order and exponential backoff used while the relay connection is retried.
 * A successful hello resets the retry count. Each failed pass over all candidates waits
 * 1, 2, 4 ... up to 60 seconds before starting again from the first candidate.
 */
internal class ReconnectPolicy(private val candidateCount: Int) {
    var retry = 0
        private set

    var candidate = 0
        private set

    fun connected() {
        retry = 0
    }

    /** Returns the delay in seconds, or 0 when the next candidate should be tried immediately. */
    fun failed(established: Boolean): Int {
        if (!established && candidate + 1 < candidateCount) {
            candidate++
            return 0
        }
        candidate = 0
        retry++
        return minOf(60, 1 shl minOf(retry - 1, 6))
    }
}

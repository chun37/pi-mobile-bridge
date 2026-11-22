package com.chun.pibridge

/** sessions.changed and agent.state can describe the same transition. Notify exactly once. */
class StateTransitions {
    private val states = mutableMapOf<String, String>()

    fun clear() {
        states.clear()
    }

    fun baseline(key: String, state: String) {
        states.putIfAbsent(key, state)
    }

    fun changed(key: String, state: String): Boolean {
        // Temporary loss of observability is not a new run or a notification baseline.
        if (state == "unknown" || state.isEmpty()) return false
        val previous = states.put(key, state)
        return previous != null && previous != state && state in setOf("blocked", "done")
    }
}

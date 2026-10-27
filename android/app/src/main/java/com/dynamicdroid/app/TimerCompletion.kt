package com.dynamicdroid.app

/** One completion presentation per run; a restarted timer rearms the same notification key. */
class TimerCompletion {
    private val completed = HashSet<String>()
    fun observe(key: String, remaining: Long?, paused: Boolean, countDown: Boolean): Boolean {
        if (!countDown || remaining == null) return false
        if (remaining > 0L) { completed.remove(key); return false }
        return !paused && completed.add(key)
    }
    fun remove(key: String) { completed.remove(key) }
}

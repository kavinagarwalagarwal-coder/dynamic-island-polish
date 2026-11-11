package com.dynamicdroid.app

/** Optional per-part timing of the island's drawing, switched on only by the performance test. */
object DrawProfile {
    @Volatile var enabled = false
    val totals = LinkedHashMap<String, Long>()

    inline fun <T> span(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try { return block() } finally {
            totals[name] = (totals[name] ?: 0L) + (System.nanoTime() - start)
        }
    }

    fun reset() = totals.clear()
    fun summary(frames: Int) = totals.entries.joinToString(" ") { "%s=%.2f".format(it.key, it.value / 1e6 / frames.coerceAtLeast(1)) }
}

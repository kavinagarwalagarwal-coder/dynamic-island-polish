package com.dynamicdroid.app

/** Stable activity selection: refreshes never steal focus or change creation order. */
class ActivityShelf<T>(private val keyOf: (T) -> String, private val priorityOf: (T) -> Int) {
    private val entries = LinkedHashMap<String, T>()
    private var selected: String? = null

    fun put(value: T): Boolean {
        val key = keyOf(value)
        val fresh = key !in entries
        val current = selected?.let(entries::get)
        entries[key] = value
        if (fresh && (current == null || priorityOf(value) >= priorityOf(current))) selected = key
        return fresh
    }
    fun all(): List<T> = entries.values.toList()
    fun select(key: String) { if (key in entries) selected = key }
    fun remove(key: String) { entries.remove(key); if (selected == key) selected = null }
    fun pair(visible: (T) -> Boolean = { true }): Pair<T?, T?> {
        val values = entries.values.filter(visible).reversed()
        val chosen = values.firstOrNull { keyOf(it) == selected }
        val urgent = values.maxByOrNull(priorityOf)
        val primary = chosen ?: urgent
        return primary to values.firstOrNull { keyOf(it) != primary?.let(keyOf) }
    }
}

/** Shared compact-plus-minimal geometry in local coordinates, independent of Android APIs. */
object IslandCluster {
    data class Layout(val width: Float, val center: Float, val bubbleCenter: Float)
    fun layout(screen: Float, requestedWidth: Float, camera: Float, hasBubble: Boolean,
               diameter: Float, gap: Float, margin: Float): Layout {
        val reserved = if (hasBubble) diameter + gap else 0f
        val width = requestedWidth.coerceIn(0f, (screen - margin * 2 - reserved).coerceAtLeast(0f))
        val low = margin + width / 2
        val high = (screen - margin - reserved - width / 2).coerceAtLeast(low)
        val center = camera.coerceIn(low, high)
        return Layout(width, center, center + width / 2 + gap + diameter / 2)
    }
}

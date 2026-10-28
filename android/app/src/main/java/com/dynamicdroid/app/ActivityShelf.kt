package com.dynamicdroid.app

/** Stable activity selection: refreshes never steal focus or change creation order. */
class ActivityShelf<T>(private val keyOf: (T) -> String, private val priorityOf: (T) -> Int,
                       private val prefersBubble: (T) -> Boolean = { false }) {
    private val entries = LinkedHashMap<String, T>()
    private var selected: String? = null
    private var explicitSelection = false

    fun put(value: T): Boolean {
        val key = keyOf(value)
        val fresh = key !in entries
        val current = selected?.let(entries::get)
        entries[key] = value
        if (fresh && (current == null || !prefersBubble(value) &&
                (prefersBubble(current) || priorityOf(value) >= priorityOf(current)))) {
            selected = key; explicitSelection = false
        }
        return fresh
    }
    fun all(): List<T> = entries.values.toList()
    fun select(key: String) { if (key in entries) { selected = key; explicitSelection = true } }
    fun restoreAutomaticSelection() { selected = null; explicitSelection = false }
    fun remove(key: String) { entries.remove(key); if (selected == key) selected = null }
    fun pair(visible: (T) -> Boolean = { true }): Pair<T?, T?> {
        val values = entries.values.filter(visible).reversed()
        val primaryCandidates = if (explicitSelection) values else values.filterNot(prefersBubble).ifEmpty { values }
        val explicitlyChosen = if (explicitSelection) primaryCandidates.firstOrNull { keyOf(it) == selected } else null
        val primary = explicitlyChosen ?: primaryCandidates.maxByOrNull(priorityOf)
        val remaining = values.filter { keyOf(it) != primary?.let(keyOf) }
        return primary to (remaining.firstOrNull(prefersBubble) ?: remaining.firstOrNull())
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

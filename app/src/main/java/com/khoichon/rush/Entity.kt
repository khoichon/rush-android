package com.khoichon.rush

/**
 * The traveling entities. All of them run the same encounter skeleton
 * (warning -> approach -> pass(es) -> clear / jumpscare); what differs is which assets they use,
 * how they warn, how fast they are and whether they rebound. [RushEngine] branches on this,
 * [SceneView] picks sprites from it.
 */
enum class Entity(
    /** Lower-case key used for prefs (weights, death counters). */
    val key: String,
    /** Name shown in the config UI. */
    val label: String,
    /** Which death screen the helper app should show for this entity. */
    val light: String,
    /** Default spawn weight (0-100). Rush is considerably more common, then Ambush, etc. */
    val defaultWeight: Int
) {
    RUSH("rush", "Rush", "guiding", 50),
    AMBUSH("ambush", "Ambush", "guiding", 20),
    BLITZ("blitz", "Blitz", "curious", 10),
    BASH("bash", "Bash", "curious", 8),
    SCRIBBLES("scribbles", "Scribbles", "curious", 5),
    SURGE("surge", "Surge", "curious", 5);

    companion object {
        val ALL = entries.toList()
    }
}

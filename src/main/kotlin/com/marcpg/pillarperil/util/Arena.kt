package com.marcpg.pillarperil.util

import com.marcpg.libpg.storing.Cord
import com.marcpg.libpg.util.toLocation
import org.bukkit.Bukkit
import org.bukkit.Location

/** The value that stands for "any arena" wherever an arena name is accepted. */
const val RANDOM_ARENA = "random"

/** The times of day `/time set` accepts by name, so arenas can be configured the same way. */
val NAMED_TIMES = mapOf(
    "day" to 1000L,
    "noon" to 6000L,
    "sunset" to 12000L,
    "night" to 13000L,
    "midnight" to 18000L,
    "sunrise" to 23000L,
)

/**
 * Reads a time of day, written either as a tick within the day or as one of the names
 * `/time set` knows.
 *
 * @return The time in ticks since dawn, or `null` if the value is neither.
 */
fun parseTimeOfDay(raw: String): Long? = raw.trim().lowercase().let { NAMED_TIMES[it] ?: it.toLongOrNull()?.mod(24000L) }

/**
 * A configured place for games to happen, so that starting one only takes a name instead of
 * a world and a set of coordinates.
 *
 * @param name The arena's id, as written in the configuration.
 * @param worldName The world the arena lives in. Resolved on use, so worlds may be loaded later.
 * @param cord The arena's center. Its Y is irrelevant, as games always build at `platform-height`.
 * @param time The time of day games run at, or `null` to leave the world's own time alone.
 * @param gameRules Game rules to apply for the duration of a game, by their configured name.
 *                  Anything not listed keeps the value the world already has.
 */
data class Arena(
    val name: String,
    val worldName: String,
    val cord: Cord,
    val time: Long? = null,
    val gameRules: Map<String, Any> = mapOf(),
) {
    /** The arena's center, or `null` while its world is not loaded. */
    fun center(): Location? = Bukkit.getWorld(worldName)?.let { cord.toLocation(it) }
}

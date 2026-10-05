package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.data.time.Time
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.game.GameCompanion
import com.marcpg.pillarperil.util.Arena
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.DisposableWorldMode
import com.marcpg.pillarperil.util.GameWorlds
import org.bukkit.entity.Player

/**
 * The single pipeline every game start goes through, no matter whether it came from `/game start` or the queue.
 *
 * The steps are always the same:
 * 1. the configured pre-commands, before any world is touched
 * 2. resolving the world and, if enabled, creating the temporary world to actually play in
 * 3. the configured post-commands, which can already use the location placeholders
 * 4. constructing and initializing the game itself
 */
object GameStarter {
    /**
     * Starts a game.
     *
     * The [callback] runs on the main thread and reports the started game or why it could not be started.
     * It may run later than this call when a temporary world has to be copied first.
     *
     * @param arena Where the game is played. Resolved after the pre-commands, so those can still create its world.
     */
    fun start(
        id: String,
        mode: GameCompanion<*>,
        players: List<Player>,
        arena: Arena,
        timeLimit: Time? = null,
        callback: (Result<Game>) -> Unit,
    ) {
        val placeholders = mutableMapOf<String, Any>(
            "id" to id,
            "mode" to mode.gameInfo.namespace,
            "players" to players.size,
        )

        Configuration.startPreCommands.forEach { PillarPeril.sendCommand(it(placeholders)) }

        val requested = arena.center()
        if (requested == null) {
            callback(Result.failure(IllegalStateException("The world '${arena.worldName}' of arena '${arena.name}' does not exist.")))
            return
        }

        GameWorlds.prepare(id, requested) { prepared ->
            val center = prepared.getOrElse { failure ->
                // In the copy mode the requested world is the template being cloned, so falling back to it
                // would play the game inside the very world that was supposed to stay untouched.
                if (Configuration.disposableWorldMode == DisposableWorldMode.COPY) {
                    callback(Result.failure(failure))
                    return@prepare
                }

                // Otherwise the requested world is only a reference for the coordinates, so degrading to it
                // (and therefore to block-by-block rollback) beats not starting the game at all.
                PillarPeril.LOG.error("Could not create a temporary world for game $id, falling back to '${requested.world.name}'.", failure)
                requested
            }

            placeholders += mapOf(
                "world" to center.world.name,
                "x" to center.x,
                "y" to center.y,
                "z" to center.z,
            )
            Configuration.startPostCommands.forEach { PillarPeril.sendCommand(it(placeholders)) }

            callback(runCatching {
                // TODO: Supply list of modifiers here:
                mode.constructGame(id, center, players, listOf()).apply {
                    this.arena = arena
                    timeLimitOverride = timeLimit

                    // A different world than the requested one can only be one made for this game.
                    if (center.world != requested.world)
                        disposableWorld = center.world

                    init()
                }
            }.onFailure {
                // The game never came up, so its temporary world would be orphaned otherwise.
                if (center.world != requested.world)
                    runCatching { GameWorlds.dispose(center.world) }
            })
        }
    }
}

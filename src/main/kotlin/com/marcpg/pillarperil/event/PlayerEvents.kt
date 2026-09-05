package com.marcpg.pillarperil.event

import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.pillarperil.game.util.GameManager
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.player.PendingRestores
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.QueueMethod
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent

object PlayerEvents : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = GameManager.player(event.player) ?: return

        // No longer count kills if the game has already started ending.
        if (player.game.ending) return

        if (event.player.killer != null)
            player.game.player(event.player.killer!!, false)?.kills++

        player.eliminate()
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (GameManager.games.isEmpty()) return

        val player = GameManager.player(event.player) ?: return

        // Freeze players in place while the game's starting grace period is still running,
        // so that nobody jumps off their pillar before they even had a chance to react.
        if (!player.game.started) {
            // Only horizontal movement is held: falling is left alone, so that a player who ends up
            // slightly above their pillar settles onto it instead of being pulled back up forever.
            if (event.from.x != event.to.x || event.from.z != event.to.z) {
                event.setTo(event.from.clone().apply { // Looking around and gravity stay allowed.
                    y = event.to.y
                    yaw = event.to.yaw
                    pitch = event.to.pitch
                })
            }
            return
        }

        if (event.to.y < Configuration.deathHeight)
            event.player.health = 0.0
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // Teleports right inside the join event are unreliable, hence the one tick of delay everywhere below.
        val game = GameManager.gameOf(player, onlyAlive = false)
        if (game != null) {
            bukkitRunLater(1L) {
                if (!player.isOnline) return@bukkitRunLater

                // The game may have ended within that tick, which leaves a restore behind instead.
                if (!game.reconnect(player))
                    PendingRestores.applyTo(player)
            }
            return
        }

        // Their game ended while they were away, so they still have to be put back to their pre-game state.
        if (PendingRestores.has(player.uniqueId)) {
            bukkitRunLater(1L) { if (player.isOnline) PendingRestores.applyTo(player) }
            return
        }

        if (Configuration.queueMethod == QueueMethod.AUTO)
            bukkitRunLater(20L) { if (player.isOnline) QueueManager.add(player) } // Wait 1 second before rejoining queue.
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        QueueManager.remove(event.player)

        val player = GameManager.player(event.player) ?: return
        if (Configuration.reconnectGrace > 0) {
            player.game.disconnect(player)
        } else {
            player.eliminate()
        }
    }
}

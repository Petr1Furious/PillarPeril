package com.marcpg.pillarperil.player

import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.QueueMethod
import com.marcpg.pillarperil.util.reviveIfDead
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*

/**
 * Restores which could not be applied yet, because the player was offline at the time — most commonly
 * because their game ended while they were disconnected. They are applied on the player's next join,
 * so that nobody ever comes back holding game items and standing on a pillar that no longer exists.
 */
object PendingRestores {
    private val snapshots = mutableMapOf<UUID, PlayerSnapshot>()

    /** Remembers a restore. An already-remembered one is kept, as it is the older and therefore more original state. */
    fun register(uuid: UUID, snapshot: PlayerSnapshot) {
        snapshots.putIfAbsent(uuid, snapshot)
    }

    fun has(uuid: UUID): Boolean = uuid in snapshots

    /** Applies and consumes this player's pending restore, if there is any. */
    fun applyTo(player: Player): Boolean {
        val snapshot = snapshots.remove(player.uniqueId) ?: return false
        player.restoreAfterGame(snapshot)
        return true
    }
}

/** Puts this player back into the state they were in before their game and moves them to the configured spawn. */
fun Player.restoreAfterGame(snapshot: PlayerSnapshot) {
    // Nothing below can be applied while they are still on the death screen.
    reviveIfDead()

    closeInventory()
    inventory.clear()
    clearActivePotionEffects()
    isInvulnerable = false // May still be set if the game ended during its starting grace period.
    scoreboard = Bukkit.getScoreboardManager().mainScoreboard

    snapshot.set(this, restoreLocation = false, restoreGameMode = false)
    teleport(Configuration.getSpawnLocation(world))

    // Applied after the teleport, because changing worlds is exactly when other plugins reassign the
    // game mode - Multiverse's `enforce-gamemode` being the usual one - and the last write wins.
    // Only the configuration can name a mode here; otherwise players come out of a game in the same
    // mode they went in, which is what the snapshot holds.
    gameMode = Configuration.spawnGameMode.value ?: snapshot.gameMode

    if (Configuration.queueMethod == QueueMethod.AUTO)
        bukkitRunLater(60L) { if (isOnline) QueueManager.add(this) } // Wait 3 seconds before rejoining queue.
}

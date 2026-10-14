package com.marcpg.pillarperil.player

import com.marcpg.libpg.display.PlayerMinecraftReceiver
import com.marcpg.libpg.display.SimpleActionBar
import com.marcpg.libpg.display.SimpleScoreboard
import com.marcpg.libpg.display.start
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.ItemGroupState
import com.marcpg.pillarperil.util.PoolItem
import com.marcpg.pillarperil.util.randomByWeight
import com.marcpg.pillarperil.util.playSoundSafe
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player

class PillarPlayer(
    player: Player,
    val game: Game,
    /**
     * The state to put the player back into once the game is over.
     * Carried over when reconnecting, so that a reconnect never snapshots the mid-game state.
     */
    val initialSnapshot: PlayerSnapshot = PlayerSnapshot(player),
) : PlayerMinecraftReceiver(player) {
    var simpleScoreboard: SimpleScoreboard? = null
    var simpleActionBar: SimpleActionBar? = null

    var kills: Int = 0
    var deathTime: Int? = null

    /** The spawn location on top of this player's own pillar. */
    var pillar: Location? = null

    /**
     * Where this player was last seen inside the game: where they died, or where they stood when
     * they disconnected. This is where `/pillar-peril respawn` puts them back.
     */
    var lastLocation: Location? = null

    /**
     * What this player has been given so far, which is what the item groups weigh the pool by.
     * Carried over when reconnecting, so a half-finished pair survives a disconnect.
     */
    var itemGroupState: ItemGroupState = ItemGroupState(game.itemGroups)

    /** The tick at which this player disconnected, or `null` while they are connected. */
    var disconnectedAt: Int? = null

    val disconnected: Boolean get() = disconnectedAt != null

    init {
        startDisplays()
    }

    fun startDisplays() {
        if (game.info.showScoreboard()) {
            try {
                simpleScoreboard = game.scoreboard?.invoke(this)
                simpleScoreboard!!.start()
            } catch (e: Exception) {
                game.error("Could not create and initialize scoreboard for $this.", e)
            }
        }

        if (game.info.showActionBar()) {
            try {
                simpleActionBar = game.actionBar?.invoke(this)
                simpleActionBar!!.start()
            } catch (e: Exception) {
                game.error("Could not create and initialize action bar for $this.", e)
            }
        }
    }

    /** Stops the displays. Wrapped defensively, as this also runs for players who already went offline. */
    fun stopDisplays() {
        runCatching { simpleScoreboard?.stop() }
        runCatching { simpleActionBar?.stop() }

        simpleScoreboard = null
        simpleActionBar = null
    }

    fun giveItems(available: Collection<PoolItem>, differentItems: Int = 1) {
        repeat(differentItems) {
            val chosen = available.randomByWeight { itemGroupState.weightOf(it) }
            itemGroupState.record(chosen)

            var item = chosen.createItemStack()
            for (modifier in game.modifiers) {
                item = modifier.onItemReceive(item)
            }

            player.inventory.addItem(item)
        }
        player.playSoundSafe(Sound.ENTITY_ITEM_PICKUP, 0.75f) { Configuration.soundEffectsItem }
    }

    fun clear(display: Boolean = false) {
        if (display)
            stopDisplays()

        // Nothing can be applied to an offline player, so it has to wait until they come back.
        if (!player.isOnline) {
            PendingRestores.register(uuid(), initialSnapshot)
            return
        }

        player.restoreAfterGame(initialSnapshot)
    }

    fun eliminate() = game.eliminate(this)
}

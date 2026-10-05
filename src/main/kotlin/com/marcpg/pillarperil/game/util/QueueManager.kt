package com.marcpg.pillarperil.game.util

import com.marcpg.libpg.lang.string
import com.marcpg.libpg.util.component
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.Game
import com.marcpg.pillarperil.util.Configuration
import com.marcpg.pillarperil.util.Ticking
import com.marcpg.pillarperil.util.trackToFastStats
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import kotlin.math.min

object QueueManager : Ticking {
    const val RED_COLORS = "#CC2222:#FF8888"
    const val GREEN_COLORS = "#22CC22:#88FF88"

    val queue = ArrayDeque<Player>()

    private var phase = 0.0

    fun add(player: Player) {
        if (!Configuration.queueEnabled || player in queue || GameManager.isInGame(player)) return

        queue.addLast(player)

        if (Configuration.queueCheckIntervalSecs == -1)
            check()
    }

    fun remove(player: Player) {
        if (!Configuration.queueEnabled) return

        queue.remove(player)

        if (Configuration.queueCheckIntervalSecs == -1)
            check()
    }

    override fun tick(tick: Ticking.Tick) {
        if (!Configuration.queueEnabled) return

        if (Configuration.queueCheckIntervalSecs >= 1) {
            if (tick.isInInterval(0, Configuration.queueCheckInterval))
                check()
        }

        // Animate the gradient by shifting its phase, which MiniMessage expects to be within -1.0 and 1.0.
        phase += 0.02
        if (phase > 1.0) phase -= 2.0

        queue.forEach { it.sendActionBar(MiniMessage.miniMessage().deserialize("<gradient:${if (queue.size >= Configuration.queueMinPlayers) GREEN_COLORS else RED_COLORS}:$phase>${it.locale().string("queue.actionbar", queue.size.toString(), Configuration.queueMinPlayers.toString())}</gradient>")) }
    }

    private fun check() {
        if (queue.size < Configuration.queueMinPlayers)
            return

        val count = min(Configuration.queueMaxPlayers, queue.size)
        startGame(MutableList(count) { queue.removeFirst() })
    }

    private fun startGame(players: List<Player>) {
        val id = Game.generateId()

        val arena = Configuration.queuedArena()
        if (arena == null) {
            PillarPeril.LOG.error("The queue is set to arena '${Configuration.queueArena}', which is not configured.")
            players.forEach {
                it.sendMessage(component("The game could not be started: no such arena is configured.", NamedTextColor.RED))
                it.sendMessage(component("Please notify an admin of the server.", NamedTextColor.RED))
            }
            return
        }

        GameStarter.start(id, Configuration.queueMode, players, arena) { result ->
            result.onFailure { error ->
                PillarPeril.LOG.error("Could not start queued game", error)
                error.trackToFastStats()

                players.forEach {
                    it.sendMessage(component("The game could not be started: ${error.message}", NamedTextColor.RED))
                    it.sendMessage(component("Please notify an admin of the server.", NamedTextColor.RED))
                }
            }
        }
    }
}

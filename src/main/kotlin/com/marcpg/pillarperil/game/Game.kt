package com.marcpg.pillarperil.game

import com.marcpg.libpg.data.time.Time
import com.marcpg.libpg.display.*
import com.marcpg.libpg.util.Randomizer
import com.marcpg.libpg.util.bukkitRunLater
import com.marcpg.libpg.util.component
import com.marcpg.libpg.util.miniMessage
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.game.util.GameInfo
import com.marcpg.pillarperil.game.util.GameManager
import com.marcpg.pillarperil.game.util.QueueManager
import com.marcpg.pillarperil.generation.Buildings
import com.marcpg.pillarperil.player.PillarPlayer
import com.marcpg.pillarperil.util.*
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemType
import org.bukkit.util.Vector
import java.time.Duration
import kotlin.math.atan2

abstract class Game(
    val id: String,
    center: Location,
    protected val bukkitPlayers: List<Player>,
    val modifiers: List<GameModifier>,
): Ticking {
    enum class EndingCause {
        FORCE,
        TIME_OVER,
        LAST_STANDING,
        DRAW,
        ERROR,
    }

    companion object {
        private val itemNowColor = listOf(TextColor.color(0x00AA22), TextColor.color(0x11FF77))
        private val itemTimeColor = listOf(TextColor.color(0x0022FF), TextColor.color(0x3399FF))

        fun getColor(left: Float): BossBar.Color = when {
            left < 0.2 -> BossBar.Color.BLUE
            left < 0.4 -> BossBar.Color.GREEN
            left < 0.6 -> BossBar.Color.YELLOW
            left < 0.8 -> BossBar.Color.RED
            else -> BossBar.Color.PINK
        }

        fun generateId() = Randomizer.generateRandomString(Constants.GAME_ID_LENGTH, Constants.GAME_ID_CHARSET)

        // Kept short, so that every countdown title fully replaces the previous one.
        private val countdownTimes: Title.Times = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))
    }

    // ================ CONSTRUCTION DATA ================

    abstract val info: GameInfo

    val center: Location = center.clone().apply { y = Configuration.platformHeight + 1.0 }
    val world: World = center.world
    val startingTick: Int = Bukkit.getCurrentTick()

    // Only modified when players join, leave, or reconnect, hence the hidden mutability.
    val initialPlayers: List<PillarPlayer> = mutableListOf()
    val players = mutableListOf<PillarPlayer>()

    // Initial target consisting of all `initialPlayers`, just used for caching.
    // Dropped whenever the player list changes, as reconnects replace players with a freshly bound instance.
    private var cachedInitialTarget: ForwardingMinecraftReceiver? = null
    private val initialTarget: ForwardingMinecraftReceiver
        get() = cachedInitialTarget ?: initialPlayers.toList().receiver().also { cachedInitialTarget = it }

    var radius: Double = 0.0

    lateinit var items: List<ItemType> protected set
    lateinit var buildings: Buildings private set

    // ==================== GAME STATE ====================

    /**
     * An optional time limit which overrides the game mode's configured one.
     * Has to be set before calling [init] to have any effect.
     */
    var timeLimitOverride: Time? = null

    /**
     * A world created purely for this game, which is deleted once the game is over.
     *
     * When this is set, the arena needs no rollback at all — the entire world goes away, so every block
     * and entity change goes with it, whatever caused it.
     */
    var disposableWorld: World? = null

    val timeLeft = Time()
    val itemCountdown = Time(0, allowNegatives = true)

    /**
     * The remaining seconds of the grace period at the very beginning of the game.
     * While this is above zero, players are frozen on their pillars and cannot be damaged.
     */
    val startCountdown = Time()

    /** Whether the initial grace period is over and the game is actually running. */
    var started: Boolean = false
        private set

    val itemCountdownPercentage: Float
        get() = (itemCountdown.get().toFloat() / (info.itemCountdown().toFloat() - 1)).coerceIn(0.0f, 1.0f)

    private val tickEvents = mutableMapOf<() -> Unit, Int>()
    private val itemEvents = mutableListOf<() -> Unit>()

    var ending = false

    /** The world's `doImmediateRespawn` from before the game changed it, restored once the game is over. */
    private var previousImmediateRespawn: Boolean? = null

    // ================= DISPLAY METHODS =================

    open val scoreboard: ((PillarPlayer) -> SimpleScoreboard)? = { p -> SimpleScoreboard(p, 5, MiniMessage.miniMessage().deserialize("<bold><gradient:#71CCF8:#FC91EC:#F87171>Pillar Peril"),
        StaticValueScoreboardEntry(p.locale().component("scoreboard.mode").style(info.keyStyle()), component(info.name(p.locale())).style(info.valueStyle)),
        StaticValueScoreboardEntry(p.locale().component("scoreboard.name").style(info.keyStyle()), component(p.name()).style(info.valueStyle)),
        ValueScoreboardEntry(p.locale().component("scoreboard.time").style(info.keyStyle())) { component(timeLeft.oneUnitFormatted).style(info.valueStyle) },
        ValueScoreboardEntry(p.locale().component("scoreboard.kills").style(info.keyStyle())) { component(p.kills.toString()).style(info.valueStyle)},
    ) }

    open val actionBar: ((PillarPlayer) -> SimpleActionBar)? = { p -> GradientActionBar(p, 5, 0.1) {
        if (itemCountdown.get() == 0L)
            p.locale().component("actionbar.now") to itemNowColor
        else
            p.locale().component("actionbar.time", itemCountdown.preciselyFormatted) to itemTimeColor
    } }

    open val bossBarCreator: () -> SimpleBossBar = { SimpleBossBar(target(false),
        20,
        { component("=== ${itemCountdown.oneUnitFormatted} ===").style(info.keyStyle()) },
        { itemCountdownPercentage },
        { getColor(itemCountdownPercentage) },
        { BossBar.Overlay.NOTCHED_10 }
    ) }

    var bossBar: SimpleBossBar? = null
        private set

    // =============== OVERRIDABLE METHODS ===============

    open fun init() {
        // Remembered so the world can be handed back the way it was found, as a game is rarely the only thing in it.
        previousImmediateRespawn = world.getGameRuleSafe("DO_IMMEDIATE_RESPAWN", "IMMEDIATE_RESPAWN")
        world.setGameRuleSafe("DO_IMMEDIATE_RESPAWN", "IMMEDIATE_RESPAWN", true)

        bukkitPlayers
            .map { PillarPlayer(it, this) }
            .onEach {
                QueueManager.remove(it.player)

                it.player.gameMode = GameMode.SURVIVAL
                it.player.clearActivePotionEffects()
                it.player.inventory.clear()
                it.player.foodLevel = 20
                it.player.saturation = 20.0f

                val maxHealth = it.player.getAttributeSafe("MAX_HEALTH")?.value
                if (maxHealth != null) {
                    it.player.health = maxHealth
                } else {
                    it.player.heal(999.0)
                }
            }
            .forEach {
                (initialPlayers as MutableList) += it
                players += it
            }

        @Suppress("DEPRECATION", "removal")
        val enabledCheck: (ItemType) -> Boolean = getIfClassExists(
            "io.papermc.paper.world.flag.FeatureDependant",
            {{ world.isEnabled(it) }},
            {{ it.isEnabledByFeature(world) }}
        )

        items = Registry.ITEM.filter { it !in Configuration.itemsBlacklist && enabledCheck(it) && info.additionalFilter(it) }.toList()

        radius = initialPlayers.size * Configuration.platformDistanceFactor / Math.TAU

        modifiers.forEach { it.init() }

        buildings = Buildings(this, info.horGen().constructGen(this), info.vertGen().constructGen(this))
        val centeredCenter = center.toCenterLocation()
        buildings.generate().forEachIndexed { i, l ->
            // Only X and Z get centered: toCenterLocation() would raise Y by another half block,
            // dropping the player onto their pillar from mid-air instead of standing them on it.
            val location = l.clone().toCenterLocation().apply { y = l.y + 1.0 }
            location.yaw = Math.toDegrees(atan2(-(centeredCenter.x - location.x), centeredCenter.z - location.z)).toFloat()

            players[i].pillar = location
            players[i].teleport(location)

            // Players who were running or jumping when the game started would otherwise keep their momentum.
            players[i].player.velocity = Vector()
            players[i].player.fallDistance = 0.0f
        }

        modifiers.forEach { it.customBuild() }

        if (info.showBossBar()) {
            bossBar = bossBarCreator()
            bossBar?.start()
        }

        timeLeft.set(timeLimitOverride ?: info.timeLimit())
        itemCountdown.set(info.itemCountdown())

        startCountdown.set(Configuration.startCountdown.coerceAtLeast(0).toLong())
        started = startCountdown.get() <= 0L
        if (!started)
            initialPlayers.forEach { it.player.isInvulnerable = true }

        GameManager.add(this)
        info("Initialized the game.")
    }

    open fun addItem(player: PillarPlayer) = player.giveItems(items)

    // ================= UTILITY METHODS =================

    fun info(msg: String) = PillarPeril.LOG.info("[${Constants.GAME_LOG_PREFIX}$id] $msg")
    fun warn(msg: String) = PillarPeril.LOG.warn("[${Constants.GAME_LOG_PREFIX}$id] $msg")
    fun error(msg: String, e: Throwable) = PillarPeril.LOG.error("[${Constants.GAME_LOG_PREFIX}$id] $msg", e)

    protected fun addTickEvent(interval: Time, event: () -> Unit) = addTickEvent(interval.get() * 20L, event)

    protected fun addTickEvent(intervalTicks: Long, event: () -> Unit) {
        tickEvents[event] = intervalTicks.toInt()
    }

    protected fun addItemEvent(event: () -> Unit) {
        itemEvents.add(event)
    }

    fun target(onlyAlive: Boolean = true): MinecraftReceiver = if (onlyAlive) players.receiver() else initialTarget

    /** Sends a translated message to everyone who is or was part of this game. */
    private fun announce(key: String, vararg variables: String?, color: TextColor = NamedTextColor.YELLOW) {
        for (p in initialPlayers) {
            p.sendMessage(p.locale().component(key, *variables, color = color))
        }
    }

    /**
     * Drops the cached receivers and rebuilds the boss bar.
     *
     * Both hold onto the players they were created with, so they have to be redone whenever the
     * player list changes — otherwise joined or reconnected players would never see the boss bar.
     */
    private fun refreshTargets() {
        cachedInitialTarget = null

        if (bossBar != null) {
            bossBar?.stop()
            bossBar = bossBarCreator()
            bossBar?.start()
        }
    }

    fun player(bukkitPlayer: Player, onlyAlive: Boolean = true): PillarPlayer? {
        for (player in (if (onlyAlive) players else initialPlayers)) {
            if (player.uuid() == bukkitPlayer.uniqueId)
                return player
        }
        return null
    }

    // ================ GAME-LOGIC METHODS ================

    fun eliminate(player: PillarPlayer) {
        if (ending || player !in players) return

        players -= player
        info("$player got eliminated.")

        player.deathTime = Bukkit.getCurrentTick()

        modifiers.forEach { it.onPlayerDeath(player) }

        val win = players.size <= 1
        val winners = players.toList()

        bukkitRunLater(19) { // 0.95s / 950ms
            if (!ending && win) {
                val lastDeath = initialPlayers.mapNotNull { it.deathTime }.max()
                val drawWinners = initialPlayers.filter { (it.deathTime ?: Int.MIN_VALUE) + 19 >= lastDeath }

                if (Configuration.enableDraws && players.isEmpty() && drawWinners.isNotEmpty()) {
                    end(EndingCause.DRAW, drawWinners)
                } else {
                    end(EndingCause.LAST_STANDING, winners)
                }
            }

            // Ending the game already sent everyone to the spawn and may have thrown the arena's world
            // away, so placing this player now would strand them in a world that no longer exists.
            if (!ending)
                placeEliminated(player)

            modifiers.forEach { it.onPostPlayerDeath(player) }
        }
    }

    /** Moves an eliminated player to wherever eliminated players belong. Does nothing while they are offline. */
    private fun placeEliminated(player: PillarPlayer) {
        if (!player.player.isOnline) return

        if (Configuration.respawnAtConfig) {
            player.player.gameMode = Configuration.spawnGameMode
            player.player.teleport(Configuration.getSpawnLocation(player.player.world))
        } else {
            player.player.gameMode = GameMode.SPECTATOR
            player.player.teleport(center)
        }
    }

    // ================ MEMBERSHIP METHODS ================

    /**
     * Marks a player as disconnected instead of eliminating them right away.
     * They keep their spot until [Configuration.reconnectGrace] runs out.
     */
    fun disconnect(player: PillarPlayer) {
        if (ending || player.disconnected || player !in players) return

        player.disconnectedAt = Bukkit.getCurrentTick()
        player.stopDisplays()

        val grace = Time(Configuration.reconnectGrace.toLong(), Time.Unit.SECONDS)
        announce("info.reconnect.disconnected", player.name(), grace.oneUnitFormatted)
        info("$player disconnected and has ${grace.oneUnitFormatted} to reconnect.")
    }

    /**
     * Binds this game back onto a player who just came back online.
     *
     * A reconnecting player is a completely new Bukkit player object, so their [PillarPlayer] is
     * replaced by one bound to the new object, carrying over everything the game tracked about them.
     *
     * @return `true` if the player was part of this game and got rebound.
     */
    fun reconnect(bukkitPlayer: Player): Boolean {
        if (ending) return false

        val old = player(bukkitPlayer, onlyAlive = false) ?: return false
        val stillAlive = players.indexOf(old)

        old.stopDisplays()

        val fresh = PillarPlayer(bukkitPlayer, this, old.initialSnapshot)
        fresh.kills = old.kills
        fresh.deathTime = old.deathTime
        fresh.pillar = old.pillar

        (initialPlayers as MutableList).replaceAll { if (it === old) fresh else it }
        if (stillAlive != -1)
            players[stillAlive] = fresh

        refreshTargets()

        if (stillAlive == -1) {
            // They ran out of time to reconnect while being away, so they are only a spectator now.
            placeEliminated(fresh)
            info("$fresh reconnected, but was already eliminated.")
            return true
        }

        if (!started)
            bukkitPlayer.isInvulnerable = true

        // Logging back in below the death height would kill them instantly, so put them back onto their pillar.
        val pillar = fresh.pillar
        if (pillar != null && bukkitPlayer.location.y < Configuration.deathHeight) {
            fresh.teleport(pillar)
            bukkitPlayer.velocity = Vector()
            bukkitPlayer.fallDistance = 0.0f
        }

        fresh.disconnectedAt = null

        announce("info.reconnect.reconnected", fresh.name())
        info("$fresh reconnected.")
        return true
    }

    /** Eliminates everyone whose reconnect grace period has run out. */
    private fun tickDisconnects() {
        val grace = Configuration.reconnectGrace
        if (grace <= 0) return

        val now = Bukkit.getCurrentTick()
        for (player in players.toList()) {
            val since = player.disconnectedAt ?: continue

            if ((now - since) / 20 >= grace) {
                announce("info.reconnect.timeout", player.name())
                info("$player did not reconnect in time.")
                eliminate(player)
            }
        }
    }

    override fun tick(tick: Ticking.Tick) {
        if (ending || players.isEmpty()) return

        if (tick.isSecond(startingTick)) {
            tickDisconnects()
            if (ending || players.isEmpty()) return
        }

        if (!started) {
            if (tick.isSecond(startingTick))
                tickStartCountdown()
            return
        }

        if (tick.isSecond(startingTick)) {
            if (itemCountdown.get() <= 0) {
                modifiers.forEach { it.onItemCycle() }
                itemEvents.forEach { it() }

                players.forEach { addItem(it) }
                itemCountdown.set(info.itemCountdown())
            } else {
                players.playSoundSafe(Sound.UI_BUTTON_CLICK, 0.2f, 2.0f) {
                    itemCountdown.get() <= Configuration.soundEffectsCooldown
                }
            }
            itemCountdown.dec()

            timeLeft.dec()
            if (timeLeft.get() <= 0)
                end(EndingCause.TIME_OVER)
        }

        tickEvents.filter { tick.isInInterval(startingTick, it.value) }.forEach { it.key() }

        modifiers.forEach { it.tick(tick) }
    }

    private fun tickStartCountdown() {
        val secondsLeft = startCountdown.get()

        // The last number needs its own second on screen, so releasing waits for the tick after it.
        if (secondsLeft <= 0L) {
            release()
            return
        }

        for (p in initialPlayers) {
            p.showTitle(Title.title(
                component(secondsLeft.toString(), if (secondsLeft <= 3) NamedTextColor.RED else NamedTextColor.YELLOW).decorate(TextDecoration.BOLD),
                p.locale().component("info.start.countdown.subtitle", color = NamedTextColor.GRAY),
                countdownTimes,
            ))
        }
        players.playSoundSafe(Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f)

        startCountdown.dec()
    }

    /** Ends the grace period, unfreezing all players and letting the actual game begin. */
    private fun release() {
        started = true

        for (p in initialPlayers) {
            p.player.isInvulnerable = false
            p.player.velocity = Vector()
            p.player.fallDistance = 0.0f

            p.showTitle(Title.title(
                p.locale().component("info.start.go.title", color = NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                p.locale().component("info.start.go.subtitle", color = NamedTextColor.GRAY),
                countdownTimes,
            ))
        }
        players.playSoundSafe(Sound.ENTITY_PLAYER_LEVELUP, 0.75f, 1.2f)

        info("The grace period is over, the game is now running.")
    }

    // ================ TIME-LIMIT METHODS ================

    /** Extends the remaining time of this game and notifies all players about it. */
    fun addTime(time: Time) {
        timeLeft.increment(time.get())
        announceTimeChange("info.time.extended", time)
    }

    /** Shortens the remaining time of this game and notifies all players about it. */
    fun removeTime(time: Time) {
        timeLeft.decrement(time.get()) // Clamped at zero, which ends the game on the next tick.
        announceTimeChange("info.time.reduced", time)
    }

    /** Sets the remaining time of this game and notifies all players about it. */
    fun setTime(time: Time) {
        timeLeft.set(time)
        announceTimeChange("info.time.set", time)
    }

    private fun announceTimeChange(key: String, time: Time) {
        announce(key, time.oneUnitFormatted, timeLeft.preciselyFormatted)
        players.playSoundSafe(Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f)

        info("Changed the time limit ($key by ${time.oneUnitFormatted}), leaving ${timeLeft.preciselyFormatted}.")
    }

    fun end(cause: EndingCause, winners: List<PillarPlayer> = listOf()) {
        if (ending) return
        ending = true

        for (p in initialPlayers) {
            when (cause) {
                EndingCause.FORCE -> p.showTitle(Title.title(
                    p.locale().component("info.end.force.title", color = NamedTextColor.YELLOW),
                    p.locale().component("info.end.force.subtitle", color = NamedTextColor.RED)
                ))
                EndingCause.TIME_OVER -> p.showTitle(Title.title(
                    p.locale().component("info.end.time-over.title", color = NamedTextColor.GREEN),
                    p.locale().component("info.end.time-over.subtitle", color = NamedTextColor.YELLOW)
                ))
                EndingCause.LAST_STANDING -> p.showTitle(Title.title(
                    p.locale().component("info.end.last-standing.title", winners.joinToString(" & "), color = NamedTextColor.GREEN),
                    p.locale().component("info.end.last-standing.subtitle", winners.sumOf { it.kills }.toString(), color = NamedTextColor.YELLOW)
                ))
                EndingCause.DRAW -> p.showTitle(Title.title(
                    p.locale().component("info.end.draw.title", color = NamedTextColor.GREEN),
                    p.locale().component("info.end.draw.subtitle", winners.joinToString(" & "), color = NamedTextColor.YELLOW)
                ))
                EndingCause.ERROR -> p.showTitle(Title.title(
                    component("Nobody wins!", color = NamedTextColor.RED),
                    component("An error occurred, resulting in no winner.", color = NamedTextColor.GRAY)
                ))
            }

            p.sendMessage(component("=== ").append(p.locale().component("info.end.time-over.stats")).append(component(" ===")).color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
            initialPlayers.sortedByDescending { it.kills }.forEachIndexed { i, sorted ->
                p.sendMessage(miniMessage("<dark_gray>${i + 1}. <gray>${sorted.player.name} <dark_gray>(<gold>${sorted.kills}<red>⚔<dark_gray>)"))
            }
        }

        when (cause) {
            EndingCause.FORCE -> warn("Stopped game forcefully.")
            EndingCause.TIME_OVER -> info("Stopped game because the time is up.")
            EndingCause.LAST_STANDING -> info("Stopped game because ${winners.joinToString()} won.")
            EndingCause.DRAW -> info("Stopped game because ${winners.joinToString(" & ")} died at the same time, resulting in a draw.")
            EndingCause.ERROR -> error("Stopped game due to an error. Error code: #001")
        }

        Configuration.endingCommands.forEach { PillarPeril.sendCommand(it(
            "id" to id,
            "mode" to info.mode.gameInfo.namespace,
            "players" to initialPlayers.size,
            "cause" to cause.name.lowercase(),
            "world" to center.world.name,
            "x" to center.x,
            "y" to center.y,
            "z" to center.z,
        )) }
        cleanup()
    }

    private fun cleanup() {
        GameManager.remove(this)
        initialPlayers.forEach { it.clear(true) }
        bossBar?.stop()

        // Has to happen while the world is still loaded, so before a temporary one gets thrown away.
        previousImmediateRespawn?.let { world.setGameRuleSafe("DO_IMMEDIATE_RESPAWN", "IMMEDIATE_RESPAWN", it) }

        val disposable = disposableWorld
        if (disposable != null) {
            GameWorlds.dispose(disposable)
            info("Deleted this game's temporary world '${disposable.name}'.")
            return
        }

        buildings.reset()
    }
}

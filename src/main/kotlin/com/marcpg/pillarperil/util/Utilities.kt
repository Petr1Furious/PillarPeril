package com.marcpg.pillarperil.util

import com.marcpg.libpg.display.MinecraftReceiver
import com.marcpg.libpg.display.receiver
import com.marcpg.pillarperil.PillarPeril
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player

fun Throwable.trackToFastStats() = Metrics.logError(this)

fun MinecraftReceiver.playSoundSafe(sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f, requirement: (() -> Boolean) = { true }) {
    if (Configuration.soundEffectsEnabled && requirement())
        this.playSound(Registry.SOUNDS.getKeyOrThrow(sound), volume, pitch)
}

fun List<MinecraftReceiver>.playSoundSafe(sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f, requirement: (() -> Boolean) = { true }) {
    if (Configuration.soundEffectsEnabled && requirement())
        this.receiver().playSound(Registry.SOUNDS.getKeyOrThrow(sound), volume, pitch)
}

fun Player.playSoundSafe(sound: Sound, volume: Float = 1.0f, pitch: Float = 1.0f, requirement: (() -> Boolean) = { true }) {
    if (Configuration.soundEffectsEnabled && requirement())
        this.playSound(this, sound, volume, pitch)
}

/** Whether this player is dead and still sitting on the death screen, where nothing can be done to them. */
val Player.awaitingRespawn: Boolean get() = isDead || health <= 0.0

/**
 * Respawns a player who is still sitting on the death screen.
 *
 * Bukkit quietly ignores [Player.teleport] for such a player, and raising their health only convinces
 * the server they are alive again while their client keeps showing the death screen until it
 * reconnects — the state players describe as "I had to relog". Anything that moves, heals or
 * snapshots players therefore has to get them out of it first.
 *
 * @return `true` if the player is alive afterwards.
 */
fun Player.reviveIfDead(): Boolean {
    if (!awaitingRespawn) return true

    @Suppress("DEPRECATION")
    runCatching { spigot().respawn() }
        .onFailure { PillarPeril.LOG.warn("Could not respawn $name: ${it.message}") }

    return !awaitingRespawn
}

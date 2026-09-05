package com.marcpg.pillarperil.util

import com.marcpg.libpg.config.*
import com.marcpg.libpg.storing.Cord
import com.marcpg.libpg.util.BasicOptional
import com.marcpg.libpg.util.toLocation
import com.marcpg.pillarperil.PillarPeril
import com.marcpg.pillarperil.Registry
import com.marcpg.pillarperil.game.mode.OriginalGame
import org.bukkit.*
import org.bukkit.block.BlockType
import org.bukkit.inventory.ItemType

object Configuration : Config(PaperConfigProvider()) {
    override val versionHistory: List<ConfigVersion> = listOf(
        ConfigVersion(id = 2),
        ConfigVersion(id = 3),
        ConfigVersion(id = 4),
        ConfigVersion(id = 5),
        ConfigVersion(id = 6),
        ConfigVersion(
            id = 7,
            modifications = listOf(
                // The queue is no longer the only thing that starts games, so its hooks became global ones.
                Modification("queue.pre-commands", PPEntryTypes.placeholder.list, listOf(), "start-pre-commands", PPEntryTypes.placeholder.list, listOf()),
                Modification("queue.post-commands", PPEntryTypes.placeholder.list, listOf(), "start-post-commands", PPEntryTypes.placeholder.list, listOf()),
            ),
        ),
        ConfigVersion(id = 8, extensiblePaths = listOf("arenas")),
        ConfigVersion(id = 9, extensiblePaths = listOf("arenas")),
    )

    override val version: Int = 9

    var startCountdown by int("start-countdown", 5)
    var reconnectGrace by int("reconnect-grace", 60)

    var platformHeight by double("platform-height", 200.0)
    var platformMaterial by custom("platform-material", PPEntryTypes.minecraftRegistry(org.bukkit.Registry.BLOCK), BlockType.BEDROCK)
    var maxFall by double("max-fall", 25.0)
    var platformDistanceFactor by double("platform-distance-factor", 10.0)
    var enableDraws by boolean("enable-draws")
    var startPreCommands by custom("start-pre-commands", PPEntryTypes.placeholder.list, listOf())
    var startPostCommands by custom("start-post-commands", PPEntryTypes.placeholder.list, listOf())
    var endingCommands by custom("ending-commands", PPEntryTypes.placeholder.list, listOf())
    var respawnAtConfig by boolean("respawn-at-config")

    var spawnGameMode by enum<GameMode>("player-spawn.game-mode", GameMode.ADVENTURE)
    var spawnWorld by custom("player-spawn.world", PaperEntryTypes.world, BasicOptional.ofNull())
    var spawnCord by custom("player-spawn.location", ExtendedEntryTypes.cordMap, Cord(0.0, -64.0, 0.0))

    var queueEnabled by boolean("queue.enabled")
    var queueMinPlayers by int("queue.min-players", 3)
    var queueMaxPlayers by int("queue.max-players", 16)
    var queueCheckIntervalSecs by int("queue.check-interval", 30)
    var queueMethod by enum<QueueMethod>("queue.method", QueueMethod.COMMAND)
    var queueMode by custom("queue.mode", PPEntryTypes.registry { Registry.modes }, OriginalGame)
    var queueArena by string("queue.arena", RANDOM_ARENA)

    var soundEffectsEnabled by boolean("sound-effects.enabled", true)
    var soundEffectsCooldown by int("sound-effects.cooldown", 3)
    var soundEffectsItem by boolean("sound-effects.item", true)

    var itemsBlacklist by custom("items.blacklist", PPEntryTypes.minecraftRegistry(org.bukkit.Registry.ITEM).list, listOf(ItemType.AIR, ItemType.BEDROCK, ItemType.ENDER_DRAGON_SPAWN_EGG))

    /**
     * Every configured arena, keyed by name.
     *
     * Read straight from the provider on each access, so arenas added through `/pp-config` or by
     * editing the file and reloading are picked up without a restart.
     */
    val arenas: Map<String, Arena>
        get() = provider.getSection("arenas").keys
            // A section can be reported deeply, so only the first path element is the arena's name.
            .map { it.substringBefore('.') }
            .distinct()
            .mapNotNull { name ->
                val world = provider.getString("arenas.$name.world", "")
                if (world.isEmpty()) return@mapNotNull null

                name to Arena(
                    name,
                    world,
                    Cord(
                        provider.getDouble("arenas.$name.location.x", 0.0),
                        provider.getDouble("arenas.$name.location.y", 0.0),
                        provider.getDouble("arenas.$name.location.z", 0.0),
                    ),
                )
            }
            .toMap()

    var disposableWorldsEnabled by boolean("disposable-worlds.enabled", true)
    var disposableWorldMode by enum<DisposableWorldMode>("disposable-worlds.mode", DisposableWorldMode.VOID)
    var disposableWorldName by custom("disposable-worlds.name", PPEntryTypes.placeholder, PlaceholderNameGetter("pp-{id}"))
    var disposableWorldMaxCopySize by int("disposable-worlds.max-copy-size", 512)

    var disableFastStats by boolean("disable-faststats", false)

    val deathHeight get() = platformHeight - maxFall

    fun getSpawnLocation(fallbackWorld: World): Location {
        val world = spawnWorld.value ?: fallbackWorld
        return if (spawnCord.y == -64.0) world.spawnLocation else spawnCord.toLocation(world)
    }

    val queueCheckInterval get() = queueCheckIntervalSecs * 20

    /** The arena queued games run in, picking a random one when configured as [RANDOM_ARENA]. */
    fun queuedArena(): Arena? = arenas.let { if (queueArena == RANDOM_ARENA) it.values.randomOrNull() else it[queueArena] }

    fun init() {
        val result = loadChecking()

        result.second.forEach { PillarPeril.LOG.error(it) }

        when (result.first) {
            ConfigLoadResult.LOADED -> PillarPeril.LOG.info("Configuration loaded.")
            ConfigLoadResult.CREATED -> PillarPeril.LOG.info("Configuration has been created.")
            ConfigLoadResult.UPDATED -> {
                PillarPeril.LOG.warn("============================= ! NOTE ! =========================")
                PillarPeril.LOG.warn("| The config has been updated and may need to be reconfigured. |")
                PillarPeril.LOG.warn("|    The old config has been backed up as 'config.yml.old'.    |")
                PillarPeril.LOG.warn("================================================================")
            }
            ConfigLoadResult.UPDATED_AND_MIGRATED -> {
                PillarPeril.LOG.info("============================= ! NOTE ! =======================")
                PillarPeril.LOG.info("| The config has been updated and was successfully migrated. |")
                PillarPeril.LOG.info("|   The old config has been backed up as 'config.yml.old'.   |")
                PillarPeril.LOG.info("==============================================================")

                dropMovedKeys()
                addMissingKeys()
            }
        }

        save()
    }

    /**
     * Writes the default for every registered key the file does not have.
     *
     * Migrating carries whole sections over from the old file, which overwrites the section the new
     * defaults just provided. Keys introduced by the new version would silently go missing that way -
     * they would still fall back to their default in code, but nobody could see or change them.
     */
    private fun addMissingKeys() {
        for ((path, entry) in getEntries()) {
            if (provider.getRaw(path) != null) continue

            provider.setRaw(path, entry.getBaseValue())
            PillarPeril.LOG.info("Added `$path`, which is new in this configuration version.")
        }
    }

    /**
     * Removes keys which moved elsewhere in a newer configuration version.
     *
     * The migration carries whole sections over, so a key that left one is copied back into its old spot
     * as well and would just sit there confusing people, since nothing reads it anymore.
     */
    private fun dropMovedKeys() {
        val moved = mapOf(
            "queue.pre-commands" to "start-pre-commands",
            "queue.post-commands" to "start-post-commands",
            "queue.world" to "queue.arena",
            "queue.location" to "queue.arena",
        )

        for ((path, replacement) in moved) {
            if (provider.getRaw(path) == null) continue

            provider.setRaw(path, null)
            PillarPeril.LOG.info("Removed `$path`, which moved to `$replacement`.")
        }
    }

    fun loadChecking(): Pair<ConfigLoadResult, List<String>> {
        val result = load() to mutableListOf<String>()

        if (queueCheckIntervalSecs < 1 && queueCheckIntervalSecs != -1)
            result.second += "Invalid value $queueCheckIntervalSecs for configuration key 'queue.check-interval'."

        return result
    }
}

object PPEntryTypes {
    val placeholder = CustomEntryType(
        BaseEntryTypes.string,
        { PlaceholderNameGetter(it) },
        { it.base }
    )

    fun <T> registry(entries: () -> Map<String, T>) = CustomEntryType(
        BaseEntryTypes.string,
        { entries()[it]!! },
        { entries().entries.first { e -> e.value == it }.key }
    )

    fun <T : Keyed> minecraftRegistry(registry: org.bukkit.Registry<T>) = CustomEntryType(
        BaseEntryTypes.string,
        { NamespacedKey.fromString(it)?.let { key -> registry.get(key) } },
        { registry.getKeyOrThrow(it).asMinimalString() }
    )
}

enum class QueueMethod { COMMAND, AUTO }

package com.marcpg.pillarperil.util

import com.marcpg.libpg.config.*
import com.marcpg.libpg.storing.Cord
import com.marcpg.libpg.util.BasicOptional
import com.marcpg.libpg.util.basicOptional
import com.marcpg.libpg.util.enumValueNoCase
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

    var spawnGameMode by custom("player-spawn.game-mode", PPEntryTypes.optionalGameMode, BasicOptional.ofNull())
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

    var itemsBlacklist by custom("items.blacklist", PPEntryTypes.minecraftRegistry(org.bukkit.Registry.ITEM).list, listOf(ItemType.AIR, ItemType.ENDER_DRAGON_SPAWN_EGG))
    var itemsBlacklistPatterns by custom("items.blacklist-patterns", BaseEntryTypes.string.list, DEFAULT_BLACKLIST_PATTERNS)
    var itemsBlacklistExceptions by custom("items.blacklist-exceptions", BaseEntryTypes.string.list, DEFAULT_BLACKLIST_EXCEPTIONS)

    /**
     * How often items are given relative to each other, keyed by pattern.
     *
     * A missing section means the defaults, while an empty one means somebody deliberately wants no
     * weighting at all — the two cannot be the same, or turning the defaults off would be impossible.
     */
    val itemWeights: Map<String, Int>
        get() = provider.getSectionOrNull("items.weights")
            ?.mapNotNull { (pattern, value) -> itemWeightOf(value)?.let { pattern to it } }
            ?.toMap()
            ?: DEFAULT_ITEM_WEIGHTS

    /**
     * The custom items, keyed by the name the rest of the configuration refers to them by.
     *
     * Only the definitions are kept here; reading one into an item is [parseCustomItem]'s job and
     * happens once per game, so a definition fixed in the file applies to the next game started.
     * Absent-versus-empty works as it does for [itemWeights].
     */
    val customItems: Map<String, String>
        get() = provider.getSectionOrNull("items.custom")
            ?.mapNotNull { (name, definition) ->
                definition?.toString()?.takeIf { it.isNotBlank() }?.let { name.trim().lowercase() to it }
            }
            ?.toMap()
            ?: DEFAULT_CUSTOM_ITEMS

    /**
     * The item groups, which make the items in them likely once a player is given one of them.
     *
     * Read on each access like [arenas], and absent-versus-empty is told apart the same way
     * [itemWeights] does it.
     */
    val itemGroups: List<ItemGroup>
        get() = itemGroupNames()?.mapNotNull { readItemGroup(it) } ?: DEFAULT_ITEM_GROUPS

    /** The names of all configured item groups, or `null` when the section is not there at all. */
    private fun itemGroupNames(): List<String>? = provider.getSectionOrNull("items.groups")
        // A section is reported deeply, so only the first path element is the group's name.
        ?.keys?.map { it.substringBefore('.') }?.distinct()

    /** Reads one item group, skipping it when it has no items to give. */
    private fun readItemGroup(name: String): ItemGroup? {
        val path = "items.groups.$name"

        val items = itemGroupPatterns("$path.items")
        if (items.isEmpty()) return null

        return itemGroup(
            name,
            itemWeightOf(provider.getRaw("$path.weight")) ?: DEFAULT_GROUP_WEIGHT,
            items,
            // Without triggers of its own, every item in the group sets off all the others.
            itemGroupPatterns("$path.triggers").ifEmpty { items },
            if (provider.getString("$path.mode", "").trim().lowercase() == GroupMode.REPEATING.name.lowercase())
                GroupMode.REPEATING else GroupMode.ONCE,
        )
    }

    private fun itemGroupPatterns(path: String): List<String> =
        provider.getStringList(path)?.filter { it.isNotBlank() } ?: listOf()

    /**
     * Every configured arena, keyed by name.
     *
     * Read straight from the provider on each access, so arenas added through `/pp-config` or by
     * editing the file and reloading are picked up without a restart.
     */
    val arenas: Map<String, Arena>
        get() = arenaNames()
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
                    // Anything unreadable is reported by `loadChecking` and then simply left to the world.
                    provider.getStringOrNull("arenas.$name.time")?.let { parseTimeOfDay(it) },
                    provider.getSection("arenas.$name.gamerules").mapNotNull { (rule, value) -> value?.let { rule to it } }.toMap(),
                )
            }
            .toMap()

    var disposableWorldsEnabled by boolean("disposable-worlds.enabled", true)
    var disposableWorldMode by enum<DisposableWorldMode>("disposable-worlds.mode", DisposableWorldMode.VOID)
    var disposableWorldName by custom("disposable-worlds.name", PPEntryTypes.placeholder, PlaceholderNameGetter("pp-{id}"))
    var disposableWorldMaxCopySize by int("disposable-worlds.max-copy-size", 128)

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

        result.second += checkArenas()
        result.second += checkCustomItems()
        result.second += checkItemWeights()
        result.second += checkItemGroups()

        // A typo here silently means "leave the game mode alone", which is easy to miss.
        val rawGameMode = provider.getStringOrNull("player-spawn.game-mode")
        if (rawGameMode != null && rawGameMode.trim().lowercase() !in PPEntryTypes.KEEP_GAME_MODE && spawnGameMode.isNull)
            result.second += "Invalid value '$rawGameMode' for configuration key 'player-spawn.game-mode'. Use a game mode or one of ${PPEntryTypes.KEEP_GAME_MODE.filter { it.isNotEmpty() }.joinToString()}."


        return result
    }

    /**
     * Reports item weights which cannot be used.
     *
     * A weight that names no existing item is almost always a typo, and since it changes nothing at
     * all it would otherwise be invisible — somebody would be left wondering why their item never
     * turned up any more often.
     */
    private fun checkItemWeights(): List<String> {
        val section = provider.getSectionOrNull("items.weights") ?: return listOf()
        val problems = mutableListOf<String>()

        for ((pattern, value) in section) {
            val weight = itemWeightOf(value)
            if (weight == null) {
                problems += "Invalid value '$value' for the item weight '$pattern' in 'items.weights'. Use a whole number of 0 or more."
                continue
            }

            if (!matchesAnyItem(pattern))
                problems += "The item weight '$pattern' in 'items.weights' matches no item and does nothing."
        }

        return problems
    }

    /**
     * Reports item groups which cannot be used.
     *
     * Same reasoning as [checkItemWeights]: a group that quietly does nothing is worse than one
     * that is complained about, since the only symptom is items not turning up together.
     */
    private fun checkItemGroups(): List<String> {
        val names = itemGroupNames() ?: return listOf()
        val problems = mutableListOf<String>()

        for (name in names) {
            val path = "items.groups.$name"

            val items = itemGroupPatterns("$path.items")
            if (items.isEmpty()) {
                problems += "The item group '$name' has no 'items' and does nothing. Remove it or list the items it should make likely."
                continue
            }

            val rawMode = provider.getStringOrNull("$path.mode")
            if (rawMode != null && rawMode.trim().lowercase() !in GROUP_MODES)
                problems += "Invalid value '$rawMode' for configuration key '$path.mode'. Use ${GROUP_MODES.joinToString(" or ")}."

            val rawWeight = provider.getRaw("$path.weight")
            if (rawWeight != null && itemWeightOf(rawWeight) == null)
                problems += "Invalid value '$rawWeight' for configuration key '$path.weight'. Use a whole number of 0 or more."

            for (pattern in items + itemGroupPatterns("$path.triggers")) {
                if (!matchesAnyItem(pattern))
                    problems += "The pattern '$pattern' in the item group '$name' matches no item and does nothing."
            }
        }

        return problems
    }

    /**
     * Reports custom items which cannot be used.
     *
     * A definition the parser cannot read is simply left out of the pool, so without this the only
     * symptom would be an item nobody is ever given and a group waiting for it forever.
     */
    private fun checkCustomItems(): List<String> {
        val section = provider.getSectionOrNull("items.custom") ?: return listOf()
        val problems = mutableListOf<String>()

        for ((name, definition) in section) {
            val id = name.trim().lowercase()

            if (definition == null || definition.toString().isBlank()) {
                problems += "The custom item '$name' has no definition. Write the item the way '/give' takes it."
                continue
            }

            if (org.bukkit.Registry.ITEM.any { it.itemId() == id })
                problems += "The custom item '$name' is already the name of a real item. Give the variant a name of its own."

            parseCustomItem(definition.toString()).onFailure {
                problems += "The custom item '$name' cannot be read: ${it.message}"
            }
        }

        return problems
    }

    /** Whether a configured pattern names anything at all, custom items included. */
    private fun matchesAnyItem(pattern: String): Boolean {
        val regex = wildcardRegex(pattern)
        return org.bukkit.Registry.ITEM.any { regex.matches(it.itemId()) } || customItems.keys.any { regex.matches(it) }
    }

    /** Reads a configured weight, which has to be a whole number that is not negative. */
    private fun itemWeightOf(value: Any?): Int? =
        ((value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull())?.takeIf { it >= 0 }

    /** The names of all configured arenas, whether they can be read or not. */
    private fun arenaNames(): List<String> = provider.getSection("arenas").keys
        // A section can be reported deeply, so only the first path element is the arena's name.
        .map { it.substringBefore('.') }
        .distinct()

    /**
     * Reports arena settings which cannot be read.
     *
     * Both the time of day and the game rules are skipped when they make no sense, since an arena
     * people can still play in beats refusing to start games over a typo. Silently ignoring a setting
     * is how somebody ends up debugging the wrong thing though, so it gets said out loud on every load.
     */
    private fun checkArenas(): List<String> = arenaNames().flatMap { name ->
        val problems = mutableListOf<String>()

        val rawTime = provider.getStringOrNull("arenas.$name.time")
        if (rawTime != null && parseTimeOfDay(rawTime) == null)
            problems += "Invalid value '$rawTime' for configuration key 'arenas.$name.time'. Use a tick of the day (0-24000) or one of ${NAMED_TIMES.keys.joinToString()}."

        for ((rule, value) in provider.getSection("arenas.$name.gamerules")) {
            val resolved = resolveGameRule(rule)

            if (resolved == null)
                problems += "Unknown game rule '$rule' in configuration key 'arenas.$name.gamerules'."
            else if (value == null || gameRuleValueOf(resolved, value) == null)
                problems += "Invalid value '$value' for the ${resolved.type.simpleName.lowercase()} game rule '$rule' in configuration key 'arenas.$name.gamerules'."
        }

        problems
    }
}

object PPEntryTypes {
    /** The values of `player-spawn.game-mode` which mean "leave the player's game mode alone". */
    val KEEP_GAME_MODE = setOf("previous", "keep", "restore", "none", "")

    /**
     * A game mode which may also be absent.
     *
     * Absent means the player keeps the game mode they had before their game, which is what most
     * servers want - forcing one is only useful when the spawn is a lobby everybody belongs in.
     */
    val optionalGameMode = CustomEntryType(
        BaseEntryTypes.string,
        {
            if (it.trim().lowercase() in KEEP_GAME_MODE) BasicOptional.ofNull()
            else runCatching { enumValueNoCase<GameMode>(it) }.getOrNull().basicOptional()
        },
        { it.value?.name?.lowercase() ?: KEEP_GAME_MODE.first() },
        "game-mode",
    )

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

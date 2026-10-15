package com.marcpg.pillarperil.util

import com.mojang.brigadier.StringReader
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import kotlin.random.Random

/**
 * One thing a player can be given.
 *
 * Most entries are a plain item out of the registry, but two kinds carry data as well. Some roll it
 * as they are handed out, because the item means nothing without it and listing every variant would
 * flood the pool — a `potion` with no potion type is just an empty bottle. The others are the custom
 * items from the configuration, which are a fixed variant of an item under a name of their own.
 */
class PoolItem(
    /** What the configuration calls this item: an item id, or the name of a custom item. */
    val id: String,
    /** How often this item comes up relative to the others. Zero means it is never given at all. */
    val weight: Int = 1,
    private val creator: () -> ItemStack,
) {
    fun createItemStack(): ItemStack = creator()

    override fun toString(): String = id
}

/**
 * Picks an item, with the heavier ones coming up proportionally more often.
 *
 * Weights are applied here rather than by repeating entries in the pool, so that a weight of 500
 * costs nothing but a larger number, and the pool keeps meaning "the items that can be given".
 *
 * @param weight What an item weighs for whoever is being given it, which is where the item groups
 *   raise the weight of the items they are waiting for.
 */
fun Collection<PoolItem>.randomByWeight(weight: (PoolItem) -> Int = PoolItem::weight): PoolItem {
    val total = sumOf { weight(it) }

    // Everything was weighted down to nothing, which is somebody's mistake rather than a wish to
    // hand out no items at all, so the weights are ignored instead.
    if (total <= 0) return random()

    var roll = Random.nextInt(total)
    for (item in this) {
        roll -= weight(item)
        if (roll < 0) return item
    }
    return last() // Unreachable, as the roll is always below the total.
}

/** How long an item group keeps raising the weight of an item it covers. */
enum class GroupMode {
    /** Until the player has been given that item once, which is all a matching pair needs. */
    ONCE,

    /** For the rest of the game, which is what ammunition for a weapon wants. */
    REPEATING,
}

/**
 * A set of items that only make sense together, like a happy ghast and a harness.
 *
 * Being given one of the [triggers] makes every item the group [boosts] far more likely for that
 * player, so the pieces of a pair tend to arrive within a few item cycles of each other instead of
 * a spawn egg sitting uselessly in somebody's inventory for a whole game.
 *
 * With no triggers of its own a group is symmetric — every item in it sets off all the others.
 */
class ItemGroup(
    val name: String,
    /** The weight the covered items are drawn with while this group is active. */
    val weight: Int,
    val mode: GroupMode,
    private val triggers: List<Regex>,
    private val items: List<Regex>,
) {
    fun triggeredBy(id: String): Boolean = triggers.any { it.matches(id) }

    fun boosts(id: String): Boolean = items.any { it.matches(id) }

    override fun toString(): String = name
}

/**
 * One player's progress through the item groups.
 *
 * Everything a player receives is remembered here, because a group only raises an item's weight
 * once the player actually holds one of its triggers, and a [GroupMode.ONCE] group stops doing so
 * for an item they have already been given.
 */
class ItemGroupState(private val groups: List<ItemGroup>) {
    private val triggered = mutableSetOf<ItemGroup>()
    private val received = mutableSetOf<String>()

    /** Remembers an item the player was just given and sets off every group it triggers. */
    fun record(item: PoolItem) {
        received += item.id
        triggered += groups.filter { it.triggeredBy(item.id) }
    }

    /**
     * The weight to draw an item with for this player.
     *
     * A group never makes an item rarer than it already is, so an item weighted up in the
     * configuration keeps that weight even when it belongs to a group with a smaller one.
     */
    fun weightOf(item: PoolItem): Int =
        maxOf(item.weight, activeFor(item.id).maxOfOrNull { it.weight } ?: 0)

    /** The groups currently raising this item for the player. */
    private fun activeFor(id: String): List<ItemGroup> = triggered
        .filter { it.boosts(id) && (it.mode == GroupMode.REPEATING || id !in received) }
}

/**
 * The items left out of every pool by default, as patterns for [wildcardRegex].
 *
 * Two kinds of item are in here. The first are the ones that exist in dozens of near-identical
 * copies without any of them doing something on a pillar — getting one of the 23 pottery sherds
 * is a wasted item cycle. The second are the operator's tools, like command blocks or a debug
 * stick, which are not a game mechanic anybody asked for.
 *
 * Useful items are deliberately kept however many of them there are: beds, wool, concrete, glass
 * and the other colour families are all perfectly good building material. So are the blocks which
 * cannot be crafted but still do something where they are placed — barriers, spawners, vaults
 * and budding amethyst are all fair game.
 */
val DEFAULT_BLACKLIST_PATTERNS = listOf(
    // Decorative sets, useless without a crafting table, a jukebox or a mount.
    "*_banner_pattern",
    "*_pottery_sherd",
    "*_smithing_template",
    "*_harness",
    "*_dye",
    "*_horse_armor",
    "*_nautilus_armor",
    "music_disc_*",
    "disc_fragment_*",

    // Operator tools, which do nothing for a player in survival.
    "chain_command_block",
    "command_block",
    "command_block_minecart",
    "debug_stick",
    "frogspawn",
    "jigsaw",
    "knowledge_book",
    "light",
    "repeating_command_block",
    "structure_block",
    "structure_void",
)

/**
 * The items kept even though a pattern matches them.
 *
 * One harness is a fine joke of an item to be handed; sixteen of them are a colour catalogue.
 */
val DEFAULT_BLACKLIST_EXCEPTIONS = listOf("white_harness")

/**
 * How often items come up relative to the others, as patterns for [wildcardRegex].
 *
 * Potions are weighted up because there are only four potion items against the registry's hundreds
 * of blocks, so at an even weight a player saw one about every sixth game — far too rare for
 * something that actually changes a fight.
 *
 * The rest are the items the groups in [DEFAULT_ITEM_GROUPS] are built around, weighted up for the
 * same reason: a group that never gets set off may as well not exist, and at a weight of 1 a happy
 * ghast turned up in about one game in twenty. Only the item which opens a group is listed, since
 * the group itself takes care of the other half once that one lands.
 */
val DEFAULT_ITEM_WEIGHTS = mapOf(
    "potion" to 25,
    "splash_potion" to 25,
    "lingering_potion" to 25,
    "tipped_arrow" to 25,
    "happy_ghast_spawn_egg" to 10,
    "bow" to 10,
    "crossbow" to 10,
    "tnt" to 15,
    "minecart" to 10,
    "saddle" to 10,
    "respawn_anchor" to 10,
)

/** The weight an item group gives its items when the configuration does not say. */
const val DEFAULT_GROUP_WEIGHT = 250

/** The accepted values of a group's `mode`, in the order [GroupMode] declares them. */
val GROUP_MODES = GroupMode.entries.map { it.name.lowercase() }

/**
 * The item groups every game plays with by default.
 *
 * Mostly matching pairs: items which do nothing on their own, where handing out one of them is
 * really a promise to hand out the other. The two repeating ones are ammunition, which stays
 * useful however much of it a player already has.
 */
val DEFAULT_ITEM_GROUPS = listOf(
    // A happy ghast cannot be ridden without a harness, and a harness is nothing without the ghast.
    itemGroup("happy-ghast", 600, listOf("happy_ghast_spawn_egg", "*_harness")),

    // Gliding off a pillar is only an escape with something to fly on with.
    itemGroup("elytra", 400, listOf("elytra", "firework_rocket")),

    // TNT nobody can light is a decoration, and a lighter with nothing to light is a torch.
    itemGroup("explosives", 400, listOf("tnt", "flint_and_steel")),

    // A minecart only goes somewhere once there is rail under it.
    itemGroup("minecart", 300, listOf("minecart", "rail")),

    // A saddle is only a saddle once there is something to put it on.
    itemGroup("saddle", 300, listOf("saddle", "horse_spawn_egg")),

    // Outside the nether a respawn anchor is a bomb, but only a charged one.
    itemGroup("respawn-anchor", 300, listOf("respawn_anchor", "glowstone")),

    // A bow keeps being worth something as long as the arrows keep coming.
    itemGroup("archery", 150, listOf("arrow", "spectral_arrow", "tipped_arrow"),
        triggers = listOf("bow", "crossbow"), mode = GroupMode.REPEATING),

    // Rockets are ammunition too, but only for a crossbow, and only the explosive ones are worth
    // shooting - the plain rocket from the elytra group above does no damage at all.
    itemGroup("rockets", 150, listOf(EXPLOSIVE_ROCKET),
        triggers = listOf("crossbow"), mode = GroupMode.REPEATING),
)

/** Builds a group from configured patterns, defaulting to a symmetric set of items. */
fun itemGroup(
    name: String,
    weight: Int = DEFAULT_GROUP_WEIGHT,
    items: List<String>,
    triggers: List<String> = items,
    mode: GroupMode = GroupMode.ONCE,
) = ItemGroup(name, weight, mode, triggers.map { wildcardRegex(it) }, items.map { wildcardRegex(it) })

/** The item ids of every form a potion comes in, all of which need a potion type to be worth anything. */
private val POTION_FORMS = setOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

/** The name of the explosive rocket in [DEFAULT_CUSTOM_ITEMS], which the rockets group hands out. */
const val EXPLOSIVE_ROCKET = "explosive_firework_rocket"

/**
 * The custom items every game plays with by default, as definitions for [parseCustomItem].
 *
 * A rocket out of the registry carries no explosion, and vanilla only damages anything with a rocket
 * that has one, so the plain item is a dud in a crossbow while staying exactly what a glider wants.
 * The explosive variant is therefore an item of its own, which the rockets group gives.
 */
val DEFAULT_CUSTOM_ITEMS = mapOf(
    EXPLOSIVE_ROCKET to """firework_rocket[fireworks={flight_duration:1,explosions:[{shape:"large_ball",colors:[I;16711680,16776960],has_trail:true}]},item_name={text:"Explosive Firework Rocket"}]""",
)

/**
 * Every potion type that actually does something.
 *
 * Water, mundane, thick and awkward potions have no effects at all, which makes them the same kind
 * of filler as the items dropped by the blacklist.
 */
private val effectivePotionTypes: List<PotionType> by lazy {
    PotionType.values().filter { runCatching { it.potionEffects.isNotEmpty() }.getOrDefault(false) }
}

/** The item's id without the `minecraft:` namespace, which is how the configuration names items. */
fun ItemType.itemId(): String = Registry.ITEM.getKeyOrThrow(this).asMinimalString()

/**
 * Compiles a configured pattern, in which `*` stands for any part of an item's id.
 *
 * A pattern without any `*` therefore simply names one item, so a single list can hold both
 * whole families like `*_banner_pattern` and one-off items like `command_block`.
 */
fun wildcardRegex(pattern: String): Regex = pattern.trim().lowercase()
    .split('*')
    .joinToString(".*") { Regex.escape(it) }
    .toRegex()

/**
 * Reads a custom item's definition, which is written exactly the way `/give` takes it.
 *
 * The command parser does the work on purpose: it is the one the game itself documents, it knows
 * every component the running version has, and it says precisely what is wrong with a definition.
 */
fun parseCustomItem(definition: String): Result<ItemStack> = runCatching {
    val reader = StringReader(definition.trim())
    val stack = ArgumentTypes.itemStack().parse(reader)

    require(!reader.canRead()) { "unexpected text after the item: '${reader.remaining}'" }

    stack
}

/**
 * Builds the pool of items a game hands out.
 *
 * @param world The game's world, which decides whether experimental items exist at all.
 */
fun buildItemPool(world: World): List<PoolItem> {
    val blacklist = Configuration.itemsBlacklist
    val patterns = Configuration.itemsBlacklistPatterns.map { wildcardRegex(it) }
    val exceptions = Configuration.itemsBlacklistExceptions.map { it.trim().lowercase() }.toSet()
    val weights = Configuration.itemWeights.map { (pattern, weight) -> wildcardRegex(pattern) to weight }

    @Suppress("DEPRECATION", "removal")
    val enabledCheck: (ItemType) -> Boolean = getIfClassExists(
        "io.papermc.paper.world.flag.FeatureDependant",
        {{ world.isEnabled(it) }},
        {{ it.isEnabledByFeature(world) }}
    )

    val registryItems = Registry.ITEM
        .filter { it !in blacklist && enabledCheck(it) }
        .filter { type ->
            val id = type.itemId()
            id in exceptions || patterns.none { it.matches(id) }
        }
        .map { poolItem(it, weightOf(it.itemId(), weights)) }

    // The blacklist is not applied to these: defining a variant is saying you want it, whatever the
    // patterns say about the item it is built from.
    val customItems = Configuration.customItems.mapNotNull { (name, definition) ->
        // A name that is already an item's would make two entries answer to it, so such a variant
        // is left out. Both this and an unreadable definition were reported by `loadChecking`.
        if (Registry.ITEM.any { it.itemId() == name }) return@mapNotNull null

        val template = parseCustomItem(definition).getOrNull() ?: return@mapNotNull null
        PoolItem(name, weightOf(name, weights)) { template.clone() }
    }

    // A weight of zero means the same as a blacklisting, so such an item leaves the pool entirely -
    // left in it, a group could still raise it back above zero.
    return (registryItems + customItems).filter { it.weight > 0 }
}

/**
 * The configured weight of an item, or 1 when nothing mentions it.
 *
 * When several patterns match, the longest one wins, so a `splash_potion: 1` keeps its weight next
 * to a `*potion: 25`. Deciding it by length rather than by the order of the lines means the file
 * can be rearranged without quietly changing what it does.
 */
private fun weightOf(id: String, weights: List<Pair<Regex, Int>>): Int =
    weights
        .filter { it.first.matches(id) }
        .maxByOrNull { it.first.pattern.length }
        ?.second
        ?: 1

/** Wraps an item type into a pool entry, giving the ones that need extra data a creator which rolls it. */
private fun poolItem(type: ItemType, weight: Int): PoolItem {
    val id = type.itemId()

    if (id !in POTION_FORMS || effectivePotionTypes.isEmpty())
        return PoolItem(id, weight) { type.createItemStack() }

    return PoolItem(id, weight) { type.createItemStack().apply { rollPotion() } }
}

/** Gives a potion, splash potion, lingering potion or tipped arrow an effect to carry. */
private fun ItemStack.rollPotion() =
    editMeta(PotionMeta::class.java) { meta -> meta.basePotionType = effectivePotionTypes.random() }

package com.marcpg.pillarperil.util

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
 * Most items are fully described by their type, but a few carry data without which the item is
 * meaningless — a `potion` with no potion type is just an empty bottle. Those roll their data at
 * the moment they are handed out, so a single pool entry covers every variant instead of flooding
 * the pool with one entry per variant, which would make potions a tenth of everything given.
 */
class PoolItem(
    val type: ItemType,
    /** How often this item comes up relative to the others. Zero means it is never given at all. */
    val weight: Int = 1,
    private val creator: (ItemType) -> ItemStack = { it.createItemStack() },
) {
    fun createItemStack(): ItemStack = creator(type)

    override fun toString(): String = type.itemId()
}

/**
 * Picks an item, with the heavier ones coming up proportionally more often.
 *
 * Weights are applied here rather than by repeating entries in the pool, so that a weight of 500
 * costs nothing but a larger number, and the pool keeps meaning "the items that can be given".
 */
fun Collection<PoolItem>.randomByWeight(): PoolItem {
    val total = sumOf { it.weight }

    // Everything was weighted down to nothing, which is somebody's mistake rather than a wish to
    // hand out no items at all, so the weights are ignored instead.
    if (total <= 0) return random()

    var roll = Random.nextInt(total)
    for (item in this) {
        roll -= item.weight
        if (roll < 0) return item
    }
    return last() // Unreachable, as the roll is always below the total.
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
 */
val DEFAULT_ITEM_WEIGHTS = mapOf(
    "potion" to 25,
    "splash_potion" to 25,
    "lingering_potion" to 25,
    "tipped_arrow" to 25,
)

/** The item ids of every form a potion comes in, all of which need a potion type to be worth anything. */
private val POTION_FORMS = setOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

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

    return Registry.ITEM
        .filter { it !in blacklist && enabledCheck(it) }
        .filter { type ->
            val id = type.itemId()
            id in exceptions || patterns.none { it.matches(id) }
        }
        .map { poolItem(it, weightOf(it, weights)) }
}

/**
 * The configured weight of an item, or 1 when nothing mentions it.
 *
 * When several patterns match, the longest one wins, so a `splash_potion: 1` keeps its weight next
 * to a `*potion: 25`. Deciding it by length rather than by the order of the lines means the file
 * can be rearranged without quietly changing what it does.
 */
private fun weightOf(type: ItemType, weights: List<Pair<Regex, Int>>): Int {
    val id = type.itemId()
    return weights
        .filter { it.first.matches(id) }
        .maxByOrNull { it.first.pattern.length }
        ?.second
        ?: 1
}

/** Wraps an item type into a pool entry, giving the ones that need extra data a creator which rolls it. */
private fun poolItem(type: ItemType, weight: Int): PoolItem {
    if (type.itemId() !in POTION_FORMS || effectivePotionTypes.isEmpty())
        return PoolItem(type, weight)

    return PoolItem(type, weight) {
        it.createItemStack().apply {
            editMeta(PotionMeta::class.java) { meta -> meta.basePotionType = effectivePotionTypes.random() }
        }
    }
}

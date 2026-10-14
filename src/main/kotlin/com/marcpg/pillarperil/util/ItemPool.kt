package com.marcpg.pillarperil.util

import com.marcpg.libpg.util.miniMessage
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.bukkit.inventory.meta.FireworkMeta
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
    /** The name to give an item instead of the one it comes with, keyed by pattern. */
    private val names: List<Pair<Regex, String>>,
) {
    fun triggeredBy(id: String): Boolean = triggers.any { it.matches(id) }

    fun boosts(id: String): Boolean = items.any { it.matches(id) }

    /**
     * The name this group gives an item, or `null` when it leaves the item named as it is.
     *
     * The longest matching pattern wins, as with the configured weights, so the order of the lines
     * does not decide anything.
     */
    fun nameFor(id: String): String? = names
        .filter { it.first.matches(id) }
        .maxByOrNull { it.first.pattern.length }
        ?.second

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
        val id = item.type.itemId()

        received += id
        triggered += groups.filter { it.triggeredBy(id) }
    }

    /**
     * The weight to draw an item with for this player.
     *
     * A group never makes an item rarer than it already is, so an item weighted up in the
     * configuration keeps that weight even when it belongs to a group with a smaller one.
     */
    fun weightOf(item: PoolItem): Int =
        maxOf(item.weight, activeFor(item.type.itemId()).maxOfOrNull { it.weight } ?: 0)

    /**
     * The name an active group wants this item handed out under, or `null` for its own name.
     *
     * Only a group which is raising the item right now gets to rename it, so a rocket drawn before
     * the player owns a crossbow is still a plain rocket. Has to be asked before [record], which is
     * what ends a [GroupMode.ONCE] group.
     */
    fun nameFor(item: PoolItem): String? {
        val id = item.type.itemId()

        return activeFor(id)
            .sortedByDescending { it.weight }
            .firstNotNullOfOrNull { it.nameFor(id) }
    }

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
 */
val DEFAULT_ITEM_WEIGHTS = mapOf(
    "potion" to 25,
    "splash_potion" to 25,
    "lingering_potion" to 25,
    "tipped_arrow" to 25,
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
    itemGroup("saddle", 300, listOf("saddle", "pig_spawn_egg")),

    // Outside the nether a respawn anchor is a bomb, but only a charged one.
    itemGroup("respawn-anchor", 300, listOf("respawn_anchor", "glowstone")),

    // A bow keeps being worth something as long as the arrows keep coming.
    itemGroup("archery", 150, listOf("arrow", "spectral_arrow", "tipped_arrow"),
        triggers = listOf("bow", "crossbow"), mode = GroupMode.REPEATING),

    // Rockets are ammunition too, but only for a crossbow, which the name says out loud.
    itemGroup("rockets", 150, listOf("firework_rocket"),
        triggers = listOf("crossbow"), mode = GroupMode.REPEATING,
        names = mapOf("firework_rocket" to "explosive_firework_rocket")),
)

/** Builds a group from configured patterns, defaulting to a symmetric set of items. */
fun itemGroup(
    name: String,
    weight: Int = DEFAULT_GROUP_WEIGHT,
    items: List<String>,
    triggers: List<String> = items,
    mode: GroupMode = GroupMode.ONCE,
    names: Map<String, String> = mapOf(),
) = ItemGroup(
    name,
    weight,
    mode,
    triggers.map { wildcardRegex(it) },
    items.map { wildcardRegex(it) },
    names.map { (pattern, name) -> wildcardRegex(pattern) to name },
)

/** The item ids of every form a potion comes in, all of which need a potion type to be worth anything. */
private val POTION_FORMS = setOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

/** The item id of the firework rocket, which is a dud without an explosion in it. */
private const val FIREWORK_ROCKET = "firework_rocket"

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
private fun poolItem(type: ItemType, weight: Int): PoolItem = when {
    type.itemId() in POTION_FORMS && effectivePotionTypes.isNotEmpty() ->
        PoolItem(type, weight) { it.createItemStack().apply { rollPotion() } }

    type.itemId() == FIREWORK_ROCKET ->
        PoolItem(type, weight) { it.createItemStack().apply { rollFirework() } }

    else -> PoolItem(type, weight)
}

/**
 * Hands an item out under a different name.
 *
 * This sets the item name rather than a custom name, so it reads as what the item simply is instead
 * of something a player renamed on an anvil. The configured name goes through MiniMessage, so it can
 * be coloured.
 */
fun ItemStack.overrideName(name: String) = editMeta { meta -> meta.itemName(miniMessage(name)) }

/** Gives a potion, splash potion, lingering potion or tipped arrow an effect to carry. */
private fun ItemStack.rollPotion() =
    editMeta(PotionMeta::class.java) { meta -> meta.basePotionType = effectivePotionTypes.random() }

/**
 * Gives a firework rocket something to go off with.
 *
 * A rocket straight out of the registry has no explosions at all, and vanilla only damages anything
 * with a rocket that has at least one — so as crossbow ammunition the plain item is a dud that
 * bursts silently. One explosion is enough to make it a weapon, and it costs a glider some health
 * when they boost with it, which is a fair price for the escape.
 */
private fun ItemStack.rollFirework() = editMeta(FireworkMeta::class.java) { meta ->
    meta.power = Random.nextInt(1, 4)
    meta.addEffect(
        FireworkEffect.builder()
            .with(FireworkEffect.Type.values().random())
            .withColor(randomColor(), randomColor())
            .withFade(randomColor())
            .apply { if (Random.nextBoolean()) flicker(true) }
            .apply { if (Random.nextBoolean()) trail(true) }
            .build()
    )
}

private fun randomColor(): Color = Color.fromRGB(Random.nextInt(0x1000000))

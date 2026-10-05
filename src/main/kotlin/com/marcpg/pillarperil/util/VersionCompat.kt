package com.marcpg.pillarperil.util

import org.bukkit.GameRule
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attributable
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.inventory.ItemStack

fun <T> getIfClassExists(requiredClass: String, hasClass: () -> T, alternative: () -> T): T {
    try {
        Class.forName(requiredClass)
    } catch (_: ClassNotFoundException) {
        return alternative()
    }
    // Return outside the try to not catch exceptions possibly created by hasClass itself.
    return hasClass()
}

fun gameRuleSafe(oldName: String, newName: String): GameRule<*> = try {
    // New game-rule system from 1.21.9, 1.21.10, or 1.21.11, not sure when exactly it got added:
    Class.forName("org.bukkit.GameRules").getField(newName)
} catch (_: Exception) {
    Class.forName("org.bukkit.GameRule").getField(oldName)
}.get(null) as GameRule<*>

private val resolvedGameRules = mutableMapOf<String, GameRule<*>?>()

/** Every game rule reachable as a constant, keyed by the normalized name of its field. */
private val gameRuleConstants: Map<String, GameRule<*>> by lazy {
    listOf("org.bukkit.GameRules", "org.bukkit.GameRule")
        .mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
        .flatMap { it.fields.asIterable() }
        .mapNotNull { field -> (runCatching { field.get(null) }.getOrNull() as? GameRule<*>)?.let { field.name.normalizedRuleName() to it } }
        .toMap()
}

private fun String.normalizedRuleName() = filter { it.isLetterOrDigit() }.lowercase()

/**
 * Looks a game rule up by name, accepting the spelling of any Minecraft version.
 *
 * Rules were renamed in 1.21.9 — `doImmediateRespawn` became `immediate_respawn` — while
 * configuration files outlive Minecraft versions, so both spellings have to keep working.
 *
 * @return The rule, or `null` if no version ever knew a rule by that name.
 */
fun resolveGameRule(name: String): GameRule<*>? = resolvedGameRules.getOrPut(name) {
    val normalized = name.normalizedRuleName()

    runCatching { GameRule.getByName(name) }.getOrNull()
        ?: GameRule.values().firstOrNull { it.name.normalizedRuleName() == normalized }
        ?: gameRuleConstants[normalized]
}

/** Reads a game rule's current value without having to know its type. */
fun World.gameRuleValue(rule: GameRule<*>): Any? {
    @Suppress("UNCHECKED_CAST")
    return getGameRuleValue(rule as GameRule<Any>)
}

/**
 * Sets a game rule from a configured value, converting it to whatever type the rule expects.
 *
 * @return `false` if the value does not fit the rule, in which case nothing was changed.
 */
fun World.setGameRuleValue(rule: GameRule<*>, value: Any): Boolean {
    val converted = gameRuleValueOf(rule, value) ?: return false

    @Suppress("UNCHECKED_CAST")
    return setGameRule(rule as GameRule<Any>, converted)
}

/**
 * Converts a value as written in the configuration into the type a game rule expects.
 *
 * YAML already tells booleans and numbers apart, but a quoted value or one coming from
 * `/pp-config` arrives as a string, so both forms are accepted.
 *
 * @return The converted value, or `null` if it does not fit the rule.
 */
fun gameRuleValueOf(rule: GameRule<*>, value: Any): Any? = when (rule.type) {
    Boolean::class.javaObjectType -> value as? Boolean ?: value.toString().lowercase().toBooleanStrictOrNull()
    Int::class.javaObjectType -> (value as? Number)?.toInt() ?: value.toString().toIntOrNull()
    else -> null
}

val cachedAttributes = mutableMapOf<String, Attribute>()
fun Attributable.getAttributeSafe(name: String): AttributeInstance? {
    if (name in cachedAttributes)
        return getAttribute(cachedAttributes[name]!!)

    val attribute = Attribute::class.java.fields.firstOrNull { it.name.endsWith(name) }?.get(null) ?: return null
    cachedAttributes[name] = attribute as Attribute
    return getAttribute(attribute)
}

private var cachedItemStackCreator: ((Material) -> ItemStack)? = null
fun Material.toItemStackSafe(): ItemStack {
    if (cachedItemStackCreator == null) {
        try {
            val of = ItemStack::class.java.getMethod("of", Material::class.java)
            cachedItemStackCreator = { of(null, it) as ItemStack }
        } catch (_: NoSuchMethodException) {
            cachedItemStackCreator = { ItemStack(it) }
        } catch (e: ReflectiveOperationException) {
            throw RuntimeException(e)
        }
    }

    return cachedItemStackCreator!!(this)
}

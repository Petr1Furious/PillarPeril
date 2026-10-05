package com.marcpg.pillarperil.util

import com.marcpg.libpg.lang.Translation
import com.marcpg.libpg.util.locale
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.*
import java.util.concurrent.CompletableFuture

/** Additional argument types, complementing LibPG's `ExtendedArgumentTypes`. */
object PPArgumentTypes {
    /**
     * A word argument with a fixed set of possible values, where every value gets a tooltip that is
     * translated into the suggesting sender's own locale.
     * @param values Provides the values and their tooltips for a given locale.
     */
    fun valuedWithTooltipFromLocale(values: (Locale) -> Map<String, String>): ArgumentType<String> = LocalizedValuedArgumentType(values)
}

/** @see PPArgumentTypes.valuedWithTooltipFromLocale */
class LocalizedValuedArgumentType(val values: (Locale) -> Map<String, String>) : ArgumentType<String> {
    override fun parse(reader: StringReader): String {
        val text = reader.readString()
        if (text in values(Translation.DEFAULT_LOCALE))
            return text

        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(reader, "Unknown value $text")
    }

    override fun getExamples(): Collection<String?> = values(Translation.DEFAULT_LOCALE).keys

    override fun <S> listSuggestions(context: CommandContext<S?>, builder: SuggestionsBuilder): CompletableFuture<Suggestions?> {
        val locale = (context.source as? CommandSourceStack)?.sender?.locale() ?: Translation.DEFAULT_LOCALE

        for ((value, tooltip) in values(locale)) {
            if (builder.remainingLowerCase in value)
                builder.suggest(value, LiteralMessage(tooltip))
        }
        return builder.buildFuture()
    }
}

package com.marcpg.pillarperil.util

data class PlaceholderNameGetter(val base: String) {
    operator fun invoke(values: Map<String, Any>): String =
        values.entries.fold(base) { result, (key, value) -> result.replace("{$key}", value.toString()) }

    operator fun invoke(vararg values: Pair<String, Any>): String = invoke(mapOf(*values))
}

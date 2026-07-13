package com.chikabell.app.domain.model

import java.util.Locale

object TagRules {
    const val MAX_TAGS_TOTAL = 50
    const val MAX_TAGS_PER_LOCATION = 5
    const val MAX_TAG_NAME_LENGTH = 30

    fun normalize(input: String): String {
        return cleanDisplayName(input)
            .lowercase(Locale.JAPAN)
    }

    fun cleanDisplayName(input: String): String {
        return input
            .trim()
            .removePrefix("#")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_TAG_NAME_LENGTH)
    }

    fun sanitizeNames(inputs: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val names = mutableListOf<String>()
        inputs.forEach { raw ->
            val name = cleanDisplayName(raw)
            if (name.isNotBlank() && seen.add(normalize(name))) {
                names += name
            }
        }
        return names.take(MAX_TAGS_PER_LOCATION)
    }
}

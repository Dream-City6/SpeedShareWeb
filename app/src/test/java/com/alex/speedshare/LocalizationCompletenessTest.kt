package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationCompletenessTest {
    @Test
    fun allLanguagesContainTheSameNonBlankKeys() {
        val english = Localization.table(ResolvedLanguage.ENGLISH)

        ResolvedLanguage.values().forEach { language ->
            val table = Localization.table(language)
            assertEquals("Missing or extra keys in ${language.name}", english.keys, table.keys)
            assertTrue(
                "Blank translation in ${language.name}",
                table.values.none(String::isBlank)
            )
        }
    }

    @Test
    fun formattingPlaceholdersMatchAcrossLanguages() {
        val english = Localization.table(ResolvedLanguage.ENGLISH)

        ResolvedLanguage.values().forEach { language ->
            Localization.table(language).forEach { (key, value) ->
                assertEquals(
                    "Formatting placeholders differ for '$key' in ${language.name}",
                    placeholders(english.getValue(key)),
                    placeholders(value)
                )
            }
        }
    }

    private fun placeholders(value: String): Set<String> =
        Regex("\\{\\d+}").findAll(value).map { it.value }.toSet()
}

package ru.circuitforge.project

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Утилиты для генерации и управления slug-идентификаторами компонентов.
 * Гарантирует уникальность в рамках проекта.
 */
object Slug {
    private val logger = LoggerFactory.getLogger(Slug::class.java)

    /** Генерирует slug для компонента на основе refPrefix. */
    fun generate(refPrefix: String): String {
        return when (refPrefix.uppercase()) {
            "R" -> "RES"
            "C" -> "CAP"
            "L" -> "IND"
            "D" -> "DIO"
            "Q" -> "TRN"
            "U", "IC" -> "IC"
            "J" -> "JMP"
            "SW" -> "SW"
            "F" -> "FUSE"
            "Y", "X" -> "OSC"
            else -> refPrefix.uppercase().take(3)
        }
    }

    /** Извлекает refPrefix из slug. */
    fun componentSlug(slug: String): String {
        return when (slug) {
            "RES" -> "R"
            "CAP" -> "C"
            "IND" -> "L"
            "DIO" -> "D"
            "TRN" -> "Q"
            "IC" -> "U"
            "JMP" -> "J"
            "SW" -> "SW"
            "FUSE" -> "F"
            "OSC" -> "Y"
            else -> slug
        }
    }

    /** Гарантирует уникальность slug в списке компонентов. */
    fun ensureUnique(slug: String, existing: List<Component>): String {
        if (slug !in existing.map { it.refPrefix }) return slug
        var counter = 1
        var candidate = "$slug$counter"
        while (candidate in existing.map { it.refPrefix }) {
            counter++
            candidate = "$slug$counter"
        }
        logger.warn("Slug collision for '$slug', using '$candidate'")
        return candidate
    }
}

package ru.circuitforge.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Репозиторий для управления документом схемы (schematic.json).
 */
object SchematicRepository {
    private val logger = LoggerFactory.getLogger(SchematicRepository::class.java)
    private const val SCHEMATIC_FILE_NAME = "schematic.json"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Загружает документ схемы. Возвращает пустой при отсутствии. */
    fun load(paths: ProjectPaths): SchematicDocument {
        val schematicPath = paths.schematic
        return try {
            val content = Files.readString(schematicPath)
            json.decodeFromString(SchematicDocument.serializer(), content)
        } catch (e: Exception) {
            logger.warn("No schematic file found at $schematicPath, using empty document", e)
            SchematicDocument()
        }
    }
    /** Сохраняет документ схемы. */
    fun save(paths: ProjectPaths, doc: SchematicDocument) {
        try {
            val schematicPath = paths.schematic
            Files.createDirectories(schematicPath.parent)
            val jsonStr = json.encodeToString(SchematicDocument.serializer(), doc)
            Files.writeString(schematicPath, jsonStr)
            logger.info("Schematic saved to $schematicPath")
        } catch (e: Exception) {
            logger.error("Failed to save schematic to ${paths.schematic}", e)
            throw e
        }
    }
}

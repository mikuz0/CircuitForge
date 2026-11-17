package ru.circuitforge.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Репозиторий для управления BOM документом (ПЭ3).
 */
object BomRepository {
    private val logger = LoggerFactory.getLogger(BomRepository::class.java)
    private const val BOM_FILE_NAME = "bom.json"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Загружает BOM документ из файла проекта. Возвращает пустой при отсутствии. */
    fun load(projectPath: Path): BomDocument {
        val bomPath = projectPath.resolve(BOM_FILE_NAME)
        return try {
            val content = Files.readString(bomPath)
            json.decodeFromString(BomDocument.serializer(), content)
        } catch (e: Exception) {
            logger.warn("No BOM file found at $bomPath, using empty document", e)
            BomDocument()
        }
    }

    /** Сохраняет BOM документ в файл проекта. */
    fun save(projectPath: Path, bom: BomDocument) {
        try {
            val bomPath = projectPath.resolve(BOM_FILE_NAME)
            Files.createDirectories(bomPath.parent)
            val jsonStr = json.encodeToString(BomDocument.serializer(), bom)
            Files.writeString(bomPath, jsonStr)
            logger.info("BOM saved to $bomPath")
        } catch (e: Exception) {
            logger.error("Failed to save BOM to ${projectPath.resolve(BOM_FILE_NAME)}", e)
            throw e
        }
    }

    /**
     * Загружает строки BOM с подтягиванием имён компонентов по UUID.
     */
    fun loadRows(projectPath: Path): List<BomRow> {
        val bomDoc = load(projectPath)
        val rows = mutableListOf<BomRow>()

        bomDoc.items.forEach { item ->
            var compName = "<not found>"
            var prefix = "?"
            try {
                val comp = ComponentRepository.loadComponentById(projectPath, item.componentId)
                if (comp != null) {
                    compName = comp.name
                    prefix = comp.refPrefix
                }
            } catch (e: Exception) {
                logger.warn("Failed to load component by id: ${item.componentId}", e)
            }

            rows.add(BomRow(
                bomnetId = item.bomnetId,
                number = item.number,
                refDes = item.refDes,
                componentId = item.componentId,
                componentName = compName,
                comment = item.comment,
                refPrefix = prefix
            ))
        }
        return rows
    }
}

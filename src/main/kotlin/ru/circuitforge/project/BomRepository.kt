package ru.circuitforge.project

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Репозиторий для управления BOM документом.
 * Отвечает за загрузку, сохранение и формирование строк BOM.
 */
object BomRepository {
    private val logger = LoggerFactory.getLogger(BomRepository::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private const val BOM_FILE = "bom.json"

    /**
     * Загружает BOM документ из файла проекта.
     * Возвращает пустой документ при отсутствии файла.
     */
    fun load(projectDir: Path): BomDocument {
        val bomPath = projectDir.resolve(BOM_FILE)
        return try {
            val content = Files.readString(bomPath)
            json.decodeFromString(BomDocument.serializer(), content)
        } catch (e: Exception) {
            logger.warn("No BOM file found at $bomPath, using empty document", e)
            BomDocument()
        }
    }

    /**
     * Сохраняет BOM документ в файл проекта.
     */
    fun save(projectDir: Path, bom: BomDocument) {
        try {
            val bomPath = projectDir.resolve(BOM_FILE)
            Files.createDirectories(bomPath.parent)
            val jsonStr = json.encodeToString(BomDocument.serializer(), bom)
            Files.writeString(bomPath, jsonStr)
            logger.info("BOM saved to $bomPath")
        } catch (e: Exception) {
            logger.error("Failed to save BOM to ${projectDir.resolve(BOM_FILE)}", e)
            throw e
        }
    }

    /**
     * Формирует строки BOM из списка компонентов проекта.
     * Группирует компоненты по refPrefix.
     */
    fun loadRows(components: List<Component>): List<BomRow> {
        return components.map { comp ->
            BomRow(
                reference = comp.refPrefix + Slug.extractNumber(comp.name).takeIf { it > 0 } ?: "N/A",
                value = comp.value,
                packageType = comp.packageType,
                manufacturer = comp.manufacturer,
                quantity = 1,
                remarks = ""
            )
        }
    }

    /**
     * Формирует элементы BOM, сгруппированные по refPrefix.
     */
    fun loadItems(components: List<Component>): List<BomItem> {
        val grouped = components.groupBy { it.refPrefix }
        return grouped.map { (prefix, comps) ->
            BomItem(
                refPrefix = prefix ?: "UNSPEC",
                name = "Group_${prefix ?: "UNSPEC"}",
                value = comps.firstOrNull()?.value ?: "",
                packageType = comps.firstOrNull()?.packageType ?: "",
                quantity = comps.size,
                remarks = "",
                rows = comps.map { comp ->
                    BomRow(
                        reference = comp.name,
                        value = comp.value,
                        packageType = comp.packageType,
                        manufacturer = comp.manufacturer,
                        quantity = 1,
                        remarks = ""
                    )
                }
            )
        }
    }
}

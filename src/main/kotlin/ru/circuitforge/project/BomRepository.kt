package ru.circuitforge.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Репозиторий для управления BOM документом (ПЭ3).
 * Отвечает за загрузку, сохранение и формирование строк BOM.
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
     * Загружает строки BOM с подтягиванием имён компонентов.
     * componentName подтягивается через ComponentRepository.loadComponent(componentSlug).name.
     */
    fun loadRows(projectPath: Path): List<BomRow> {
        val bomDoc = load(projectPath)
        val rows = mutableListOf<BomRow>()

        bomDoc.items.forEach { item ->
            val componentName = try {
                val component = ComponentRepository.loadComponent(projectPath, item.componentSlug)
                component.name
            } catch (e: Exception) {
                logger.warn("Failed to load component for slug: ${item.componentSlug}", e)
                "<not found>"
            }

            rows.add(BomRow(
                number = item.number,
                refDes = item.refDes,
                componentSlug = item.componentSlug,
                componentName = componentName,
                comment = item.comment
            ))
        }

        return rows
    }

    /** Формирует начальный BOM из компонентов проекта — по одному элементу на компонент-описание. */
    fun initFromComponents(projectPath: Path): BomDocument {
        val componentsDir = projectPath.resolve("components")
        if (!Files.exists(componentsDir)) return BomDocument()

        val items = mutableListOf<BomItem>()
        var number = 1
        try {
            Files.list(componentsDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".cfcomp") }
                    .forEach { compPath ->
                        try {
                            val comp = ComponentRepository.load(compPath)
                            // Используем slug из компонента, а не имя файла
                            val slug = comp.slug.takeIf { it.isNotBlank() }
                                ?: compPath.fileName.toString().removeSuffix(".cfcomp")
                            items.add(BomItem(
                                number = number++,
                                refDes = comp.refPrefix + number,
                                componentSlug = slug,
                                comment = comp.description
                            ))
                        } catch (e: Exception) {
                            logger.warn("Failed to load component from $compPath", e)
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to init BOM from components in $projectPath", e)
        }
        return BomDocument(schemaVersion = 1, items = items)
    }
}

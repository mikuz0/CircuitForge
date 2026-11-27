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
    fun load(paths: ProjectPaths): BomDocument {
        val bomPath = paths.bom
        return try {
            val content = Files.readString(bomPath)
            json.decodeFromString(BomDocument.serializer(), content)
        } catch (e: Exception) {
            logger.warn("No BOM file found at $bomPath, using empty document", e)
            BomDocument()
        }
    }

    /** Сохраняет BOM документ в файл проекта. */
    fun save(paths: ProjectPaths, bom: BomDocument) {
        try {
            val bomPath = paths.bom
            Files.createDirectories(bomPath.parent)
            val jsonStr = json.encodeToString(BomDocument.serializer(), bom)
            Files.writeString(bomPath, jsonStr)
            logger.info("BOM saved to $bomPath")
        } catch (e: Exception) {
            logger.error("Failed to save BOM to ${paths.bom}", e)
            throw e
        }
    }

    /**
     * Создаёт новый BOM-документ из всех компонентов проекта.
     * Каждый компонент становится одной строкой BOM с авто-сгенерированным RefDes.
     */
    fun initFromComponents(paths: ProjectPaths): BomDocument {
        val componentsDir = paths.componentsDir
        if (!Files.exists(componentsDir)) {
            logger.warn("No components directory found at $componentsDir")
            return BomDocument()
        }

        val items = mutableListOf<BomItem>()
        try {
            Files.list(componentsDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".cfcomp") }
                    .sorted()
                    .forEach { path ->
                        try {
                            val comp = ComponentRepository.load(path)
                            val slug = path.fileName.toString().removeSuffix(".cfcomp")
                            val prefix = comp.refPrefix.uppercase()
                            val refDes = if (prefix.isNotBlank()) "$prefix${items.size + 1}" else "COMP${items.size + 1}"
                            items.add(
                                BomItem(
                                    number = items.size + 1,
                                    refDes = refDes,
                                    componentId = comp.id,
                                    componentSlug = slug,
                                    comment = comp.description
                                )
                            )
                        } catch (e: Exception) {
                            logger.warn("Failed to load component from $path, skipping", e)
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to scan components directory: $componentsDir", e)
        }

        return BomDocument(schemaVersion = 1, items = items)
    }

    /**
     * Загружает строки BOM с подтягиванием имён компонентов по UUID.
     * Использует кэш компонентов для O(N) вместо O(N×M).
     */
    fun loadRows(paths: ProjectPaths): List<BomRow> {
        val bomDoc = load(paths)

        // Кэш: componentId -> Component (загружаем все компоненты один раз)
        val componentCache = mutableMapOf<String, Component>()
        val componentsDir = paths.componentsDir
        if (Files.exists(componentsDir)) {
            try {
                Files.list(componentsDir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".cfcomp") }
                        .forEach { path ->
                            runCatching { ComponentRepository.load(path) }.getOrNull()
                                ?.let { componentCache[it.id] = it }
                        }
                }
            } catch (e: Exception) {
                logger.warn("Failed to scan components dir for cache: $componentsDir", e)
            }
        }

        val rows = mutableListOf<BomRow>()
        bomDoc.items.forEach { item ->
            val comp = componentCache[item.componentId]
            val compName = comp?.name ?: "<not found>"
            val prefix = comp?.refPrefix ?: "?"
            val slug = item.componentSlug.ifBlank {
                // Если slug пустой, пытаемся найти по componentId в кэше
                comp?.let { component ->
                    componentsDir.resolve("${ComponentRepository.generateSlug(component.name)}.cfcomp").let { 
                        if (Files.exists(it)) ComponentRepository.generateSlug(component.name) else ""
                    }
                } ?: ""
            }

            rows.add(BomRow(
                bomnetId = item.bomnetId,
                number = item.number,
                refDes = item.refDes,
                componentId = item.componentId,
                componentSlug = slug,
                componentName = compName,
                comment = item.comment,
                refPrefix = prefix
            ))
        }
        return rows
    }
}

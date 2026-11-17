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
     * Создаёт новый BOM-документ из всех компонентов проекта.
     * Каждый компонент становится одной строкой BOM с авто-сгенерированным RefDes.
     */
    fun initFromComponents(projectPath: Path): BomDocument {
        val componentsDir = projectPath.resolve("components")
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
     */
    fun loadRows(projectPath: Path): List<BomRow> {
        val bomDoc = load(projectPath)
        val rows = mutableListOf<BomRow>()

        bomDoc.items.forEach { item ->
            var compName = "<not found>"
            var prefix = "?"
            var slug = item.componentSlug
            try {
                val comp = ComponentRepository.loadComponentById(projectPath, item.componentId)
                if (comp != null) {
                    compName = comp.name
                    prefix = comp.refPrefix
                    // Resolve slug from component filename if not already set
                    if (slug.isBlank()) {
                        val componentsDir = projectPath.resolve("components")
                        if (Files.exists(componentsDir)) {
                            Files.list(componentsDir).use { stream ->
                                stream.filter { it.fileName.toString().endsWith(".cfcomp") }
                                    .forEach { path ->
                                        try {
                                            val loadedComp = ComponentRepository.load(path)
                                            if (loadedComp.id == item.componentId) {
                                                slug = path.fileName.toString().removeSuffix(".cfcomp")
                                            }
                                        } catch (e: Exception) { /* skip */ }
                                    }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to load component by id: ${item.componentId}", e)
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

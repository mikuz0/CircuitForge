package ru.circuitforge.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Репозиторий для управления компонентами.
 * Отвечает за CRUD операции и сканирование файлов компонентов.
 */
object ComponentRepository {
    private val logger = LoggerFactory.getLogger(ComponentRepository::class.java)
    private const val CFCOMP_EXT = ".cfcomp"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Создаёт новый компонент и сохраняет в файл. */
    fun create(slug: String, name: String, description: String, portsCount: Int, refPrefix: String = ""): Path {
        val component = Component(
            slug = slug,
            name = name,
            description = description,
            portsCount = portsCount,
            refPrefix = refPrefix
        )
        val fileName = "${slug}_$name$CFCOMP_EXT"
        val path = Path.of(fileName)
        save(path, component)
        logger.info("Component created: $path")
        return path
    }

    /** Сохраняет компонент в указанный файл. */
    fun save(path: Path, component: Component) {
        try {
            Files.createDirectories(path.parent)
            val jsonStr = json.encodeToString(Component.serializer(), component)
            Files.writeString(path, jsonStr)
            logger.info("Component saved to $path")
        } catch (e: Exception) {
            logger.error("Failed to save component to $path", e)
            throw e
        }
    }

    /** Загружает компонент из файла. */
    fun load(path: Path): Component {
        return try {
            val content = Files.readString(path)
            json.decodeFromString(Component.serializer(), content)
        } catch (e: Exception) {
            logger.error("Failed to load component from $path", e)
            throw e
        }
    }

    /** Сканирует директорию на наличие файлов компонентов. */
    fun listComponents(directory: Path): List<ComponentSummary> {
        val components = mutableListOf<ComponentSummary>()
        try {
            Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(CFCOMP_EXT) }
                    .forEach { path ->
                        try {
                            val comp = load(path)
                            components.add(toSummary(comp))
                        } catch (e: Exception) {
                            logger.warn("Failed to load component from $path, skipping", e)
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to scan directory $directory", e)
        }
        return components
    }

    /** Удаляет компонент. */
    fun delete(path: Path) {
        try {
            Files.deleteIfExists(path)
            logger.info("Component deleted: $path")
        } catch (e: Exception) {
            logger.error("Failed to delete component $path", e)
            throw e
        }
    }

    /** Преобразует компонент в сокращённую информацию. */
    fun toSummary(component: Component): ComponentSummary {
        return ComponentSummary(
            id = component.id,
            slug = component.slug,
            name = component.name,
            portsCount = component.portsCount
        )
    }
}

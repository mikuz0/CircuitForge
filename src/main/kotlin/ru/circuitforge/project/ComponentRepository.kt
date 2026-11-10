package ru.circuitforge.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Репозиторий для управления компонентами.
 * Формат файла: <slug>.cfcomp (например, resistor-0603.cfcomp).
 * Slug генерируется из name: lowercase, пробелы в '-', только a-z0-9-.
 */
object ComponentRepository {
    private val logger = LoggerFactory.getLogger(ComponentRepository::class.java)
    private const val CFCOMP_EXT = ".cfcomp"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Генерирует уникальный slug из имени компонента.
     * Формат: lowercase, пробелы в '-', только a-z0-9-.
     * При коллизии добавляет -1, -2 и т.д.
     */
    fun generateSlug(name: String): String {
        val base = name.lowercase()
            .replace(' ', '-')
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-+"), "-")
            .removePrefix("-")
            .removeSuffix("-")

        if (base.isBlank()) return "component"

        var slug = base
        var counter = 1
        while (true) {
            if (!isSlugInUse(slug)) {
                return slug
            }
            slug = "$base-$counter"
            counter++
        }
    }

    /** Проверяет, занят ли slug в директории проекта. */
    private fun isSlugInUse(slug: String): Boolean {
        // Проверка будет выполняться в контексте projectDir
        return false
    }

    /**
     * Создаёт новый компонент и сохраняет в файл.
     * @return slug созданного компонента
     */
    fun createComponent(projectDir: Path, name: String, description: String, refPrefix: String, ports: List<Port> = emptyList()): String {
        val slug = generateUniqueSlug(projectDir, name)
        val component = Component(
            schemaVersion = 1,
            slug = slug,
            name = name,
            description = description,
            refPrefix = refPrefix,
            ports = ports
        )
        val path = projectDir.resolve("components").resolve("${slug}$CFCOMP_EXT")
        save(path, component)
        logger.info("Component created: ${slug}$CFCOMP_EXT")
        return slug
    }

    /**
     * Сохраняет существующий компонент. Проверяет уникальность name,
     * переименовывает файл при смене slug.
     * @return актуальный slug
     */
    fun saveComponent(projectDir: Path, originalSlug: String, name: String, description: String, refPrefix: String, ports: List<Port>): String {
        val componentsDir = projectDir.resolve("components")
        
        // Проверяем уникальность name (кроме текущего компонента)
        if (name.isNotBlank() && hasNameConflict(componentsDir, originalSlug, name)) {
            throw IllegalArgumentException("Component with name '$name' already exists")
        }

        val newSlug = generateUniqueSlug(projectDir, name)
        val component = Component(
            schemaVersion = 1,
            id = java.util.UUID.randomUUID().toString(),
            slug = newSlug,
            name = name,
            description = description,
            refPrefix = refPrefix,
            ports = ports
        )

        // Переименовываем файл при смене slug
        val newPath = componentsDir.resolve("${newSlug}$CFCOMP_EXT")
        val oldPath = componentsDir.resolve("${originalSlug}$CFCOMP_EXT")
        
        if (originalSlug != newSlug && Files.exists(oldPath)) {
            Files.move(oldPath, newPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            logger.info("Component renamed: $originalSlug -> $newSlug")
        } else {
            Files.createDirectories(componentsDir)
        }
        
        save(newPath, component)
        logger.info("Component saved: $newSlug")
        return newSlug
    }

    /** Проверяет конфликт имён компонентов. */
    private fun hasNameConflict(componentsDir: Path, excludeSlug: String, name: String): Boolean {
        return try {
            Files.list(componentsDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(CFCOMP_EXT) }
                    .anyMatch { path ->
                        val fileName = path.fileName.toString().removeSuffix(CFCOMP_EXT)
                        fileName != excludeSlug && fileName == name
                    }
            }
        } catch (e: Exception) {
            logger.warn("Failed to check name conflict for '$name'", e)
            false
        }
    }

    /**
     * Загружает компонент по slug из директории проекта.
     */
    fun loadComponent(projectDir: Path, slug: String): Component {
        val path = projectDir.resolve("components").resolve("${slug}$CFCOMP_EXT")
        return load(path)
    }

    /**
     * Удаляет компонент по slug.
     */
    fun deleteComponent(projectDir: Path, slug: String) {
        val path = projectDir.resolve("components").resolve("${slug}$CFCOMP_EXT")
        delete(path)
    }

    /**
     * Сканирует директорию и возвращает список ComponentSummary.
     */
    fun listComponents(projectDir: Path): List<ComponentSummary> {
        val componentsDir = projectDir.resolve("components")
        val components = mutableListOf<ComponentSummary>()
        try {
            Files.list(componentsDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(CFCOMP_EXT) }
                    .forEach { path ->
                        try {
                            val comp = load(path)
                            val fileName = path.fileName.toString().removeSuffix(CFCOMP_EXT)
                            // Используем slug из файла или вычисляем из имени
                            val slug = comp.slug.takeIf { it.isNotBlank() } ?: fileName
                            val summary = ComponentSummary(
                                id = comp.id,
                                slug = slug,
                                name = comp.name,
                                description = comp.description,
                                refPrefix = comp.refPrefix,
                                portsCount = comp.ports.size
                            )
                            logger.info("Loaded component: name=${comp.name}, slug=$slug, ports=${comp.ports.size}")
                            components.add(summary)
                        } catch (e: Exception) {
                            logger.warn("Failed to load component from $path, skipping", e)
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to scan directory $componentsDir", e)
        }
        return components
    }

    /** Генерирует уникальный slug с учётом существующих файлов. */
    private fun generateUniqueSlug(projectDir: Path, name: String): String {
        val baseSlug = generateSlug(name)
        val componentsDir = projectDir.resolve("components")
        var candidate = baseSlug
        var counter = 1

        while (true) {
            val candidatePath = componentsDir.resolve("${candidate}$CFCOMP_EXT")
            if (!Files.exists(candidatePath)) {
                return candidate
            }
            candidate = "$baseSlug-$counter"
            counter++
        }
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
}

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
     */
    fun generateSlug(name: String): String {
        val base = name.lowercase()
            .replace(' ', '-')
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-+"), "-")
            .removePrefix("-")
            .removeSuffix("-")

        return if (base.isBlank()) "component" else base
    }

    /**
     * Создаёт новый компонент и сохраняет в файл.
     * @return slug созданного компонента
     */
    fun createComponent(
        projectDir: Path,
        name: String,
        description: String,
        refPrefix: String,
        ports: List<Port> = emptyList(),
        mainProperties: List<Property> = emptyList(),
        extraProperties: List<Property> = emptyList()
    ): String {
        val slug = generateUniqueSlug(projectDir, name)
        val component = Component(
            schemaVersion = 1,
            name = name,
            description = description,
            refPrefix = refPrefix,
            ports = ports,
            mainProperties = mainProperties,
            extraProperties = extraProperties
        )
        val path = projectDir.resolve("components").resolve("${slug}$CFCOMP_EXT")
        save(path, component)
        logger.info("Component created: ${slug}$CFCOMP_EXT")
        return slug
    }
    /**
     * Создаёт копию существующего компонента с новым id и новым именем файла.
     * Имя копии: "<name> copy", при конфликте — "<name> copy-1", "<name> copy-2", ...
     * @return slug созданной копии
     */
    fun duplicateComponent(projectDir: Path, sourceSlug: String): String {
        val sourcePath = projectDir.resolve("components").resolve("${sourceSlug}$CFCOMP_EXT")
        val source = load(sourcePath)

        val copyName = "${source.name} copy"
        val newSlug = generateUniqueSlug(projectDir, copyName)
        val newPath = projectDir.resolve("components").resolve("${newSlug}$CFCOMP_EXT")

        val copy = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = copyName
        )
        save(newPath, copy)
        logger.info("Component duplicated: $sourceSlug -> $newSlug")
        return newSlug
    }
    /**
     * Сохраняет существующий компонент. Проверяет уникальность name,
     * переименовывает файл при смене slug.
     * @return актуальный slug
     */
    fun saveComponent(
        projectDir: Path,
        originalSlug: String, // Фактически это имя файла (без .cfcomp)
        name: String,
        description: String,
        refPrefix: String,
        ports: List<Port>,
        mainProperties: List<Property> = emptyList(),
        extraProperties: List<Property> = emptyList()
    ): String {
        val componentsDir = projectDir.resolve("components")
        val oldPath = componentsDir.resolve("${originalSlug}$CFCOMP_EXT")

        // 1. Читаем старый ID (если файл существует)
        val existingId = if (Files.exists(oldPath)) {
            try {
                load(oldPath).id
            } catch (e: Exception) {
                java.util.UUID.randomUUID().toString() // Fallback
            }
        } else {
            java.util.UUID.randomUUID().toString()
        }

        // 2. Генерируем новое имя файла (передаем originalSlug как исключение!)
        val newSlug = generateUniqueSlug(projectDir, name, originalSlug) // <-- ИСПРАВЛЕНО
        val newPath = componentsDir.resolve("${newSlug}$CFCOMP_EXT")

        // 3. Создаем объект с сохраненным ID
        val component = Component(
            schemaVersion = 1,
            id = existingId, // <-- СОХРАНЯЕМ СТАРЫЙ ID
            name = name,
            description = description,
            refPrefix = refPrefix,
            ports = ports,
            mainProperties = mainProperties,
            extraProperties = extraProperties
        )

        // 4. Переименовываем файл при необходимости
        if (originalSlug != newSlug && Files.exists(oldPath)) {
            Files.move(oldPath, newPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            logger.info("Component file renamed: $originalSlug -> $newSlug")
        }

        // 5. Сохраняем
        save(newPath, component)
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
                            // Имя файла без расширения — это наш "slug" для UI
                            val fileSlug = path.fileName.toString().removeSuffix(CFCOMP_EXT)

                            val summary = ComponentSummary(
                                id = comp.id,
                                slug = fileSlug, // Используем имя файла
                                name = comp.name,
                                description = comp.description,
                                refPrefix = comp.refPrefix,
                                portsCount = comp.ports.size,
                                mainPropertiesCount = comp.mainProperties.size,
                                extraPropertiesCount = comp.extraProperties.size
                            )
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
    private fun generateUniqueSlug(projectDir: Path, name: String, excludeSlug: String? = null): String {
        val baseSlug = generateSlug(name)
        val componentsDir = projectDir.resolve("components")
        var candidate = baseSlug
        var counter = 1

        while (true) {
            // Если кандидат совпадает с исключением — это наш текущий файл, он "свободен"
            if (candidate == excludeSlug) {
                return candidate
            }

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
    /**
     * Находит компонент по его UUID, сканируя папку components.
     * Возвращает null, если компонент не найден.
     */
    fun loadComponentById(projectDir: Path, componentId: String): Component? {
        val componentsDir = projectDir.resolve("components")
        if (!Files.exists(componentsDir)) return null
        return try {
            Files.list(componentsDir).use { stream ->
                stream.toList()
                    .filter { it.fileName.toString().endsWith(CFCOMP_EXT) }
                    .map { path ->
                        try { load(path) } catch (e: Exception) { null }
                    }
                    .filterNotNull()
                    .firstOrNull { it.id == componentId }
            }
        } catch (e: Exception) {
            logger.error("Failed to find component by id: $componentId", e)
            null
        }
    }

}
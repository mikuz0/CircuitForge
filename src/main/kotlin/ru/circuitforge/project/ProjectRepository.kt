package ru.circuitforge.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Репозиторий для управления проектами.
 * Отвечает за создание, сохранение и сканирование проектов.
 */
object ProjectRepository {
    private val logger = LoggerFactory.getLogger(ProjectRepository::class.java)
    private const val PROJECT_FILE_NAME = "project.cfproj"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Создаёт новый проект в рабочей области.
     * @param name имя проекта (безопасное, без / и \)
     * @param workspace рабочая область (папка, где создаётся проект)
     * @return путь к файлу project.cfproj
     * @throws IllegalArgumentException если имя недопустимо или проект уже существует
     */
    @Throws(IllegalArgumentException::class)
    fun create(name: String, workspace: Path): Path {
        if (name.isBlank()) {
            throw IllegalArgumentException("Project name cannot be blank")
        }
        if ('/' in name || '\\' in name) {
            throw IllegalArgumentException("Project name cannot contain '/' or '\\'")
        }

        val machineName = generateMachineName(name)
        val projectDir = workspace.resolve(machineName)
        if (Files.exists(projectDir)) {
            throw IllegalArgumentException("Project '$name' already exists at $projectDir")
        }

        Files.createDirectories(projectDir)
        Files.createDirectories(projectDir.resolve("components"))
        Files.createDirectories(projectDir.resolve("nets"))
        Files.createDirectories(projectDir.resolve("exports"))

        val projectFile = projectDir.resolve(PROJECT_FILE_NAME)
        val project = Project(name = name, machineName = machineName)
        save(projectFile, project)

        logger.info("Project created: $projectFile")
        return projectFile
    }

    /** Сохраняет проект в указанный файл. */
    fun save(path: Path, project: Project) {
        try {
            Files.createDirectories(path.parent)
            val jsonStr = json.encodeToString(Project.serializer(), project)
            Files.writeString(path, jsonStr)
            logger.info("Project saved to $path")
        } catch (e: Exception) {
            logger.error("Failed to save project to $path", e)
            throw e
        }
    }

    /** Загружает проект из файла. */
    fun load(path: Path): Project {
        return try {
            val content = Files.readString(path)
            json.decodeFromString(Project.serializer(), content)
        } catch (e: Exception) {
            logger.error("Failed to load project from $path", e)
            throw e
        }
    }
    /**
     * Генерирует машинное имя проекта: lowercase, пробелы -> '-',
     * только a-z0-9-, схлопывание '-', trim '-'.
     */
    @Throws(IllegalArgumentException::class)
    fun generateMachineName(name: String): String {
        val base = name.lowercase()
            .replace(' ', '-')
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-+"), "-")
            .removePrefix("-")
            .removeSuffix("-")
        if (base.isBlank()) {
            throw IllegalArgumentException("Project name must contain at least one letter or digit")
        }
        return base
    }
    /**
     * Сканирует директорию на наличие файлов проектов.
     * Возвращает список ProjectInfo, отсортированный по имени.
     */
    fun scan(directory: Path): List<ProjectInfo> {
        val projects = mutableListOf<ProjectInfo>()
        try {
            Files.list(directory).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .forEach { dir ->
                        val projectFile = dir.resolve(PROJECT_FILE_NAME)
                        if (Files.exists(projectFile)) {
                            try {
                                val project = load(projectFile)
                                projects.add(ProjectInfo(project.name, project.machineName, dir))
                            } catch (e: Exception) {
                                logger.warn("Failed to load project from $dir, skipping", e)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to scan directory $directory", e)
        }
        return projects.sorted()
    }
}

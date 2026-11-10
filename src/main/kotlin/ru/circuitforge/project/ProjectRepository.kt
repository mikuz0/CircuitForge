package ru.circuitforge.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.attribute.BasicFileAttributes

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

    /** Создаёт новый проект с указанным именем и путём. */
    fun create(name: String, path: Path): Path {
        val project = Project(name = name)
        val dir = path.resolve(name)
        Files.createDirectories(dir)
        val file = dir.resolve(PROJECT_FILE_NAME)
        save(file, project)
        logger.info("Project created: $file")
        return file
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

    /** Сканирует директорию на наличие файлов проектов. */
    fun scan(directory: Path): List<ProjectInfo> {
        val projects = mutableListOf<ProjectInfo>()
        try {
            Files.list(directory).use { stream ->
                stream.filter { it.isDirectory() }
                    .forEach { dir ->
                        val projectFile = dir.resolve(PROJECT_FILE_NAME)
                        if (Files.exists(projectFile)) {
                            try {
                                val attr = Files.readAttributes(dir, BasicFileAttributes::class.java)
                                projects.add(ProjectInfo(dir.fileName.toString(), dir))
                            } catch (e: Exception) {
                                logger.warn("Failed to load project from $dir", e)
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

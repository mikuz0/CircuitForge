package ru.circuitforge.ui.projects

import javafx.concurrent.Task
import ru.circuitforge.project.ProjectInfo
import ru.circuitforge.project.ProjectRepository
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Path
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory

/**
 * Контроллер для управления проектами.
 * Выполняет I/O операции в фоновых потоках через Task.
 */
class ProjectsController(private val view: ProjectsView) {
    private val logger = LoggerFactory.getLogger(ProjectsController::class.java)
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "project-io").apply { isDaemon = true }
    }

    /** Загружает список проектов из директории. */
    fun loadProjects(dir: Path) {
        val task = object : Task<List<ProjectInfo>>() {
            override fun call(): List<ProjectInfo> {
                return ProjectRepository.scan(dir)
            }
        }
        task.setOnSucceeded { view.setProjects(task.value) }
        task.setOnFailed {
            logger.error("Failed to load projects", task.exception)
            UiUtils.errorAlert("Failed to load projects", task.exception)
        }
        executor.submit(task)
    }

    /** Создаёт новый проект. */
    fun createProject(name: String, dir: Path) {
        val task = object : Task<Path>() {
            override fun call(): Path {
                return ProjectRepository.create(name, dir.toString())
            }
        }
        task.setOnSucceeded { loadProjects(dir) }
        task.setOnFailed {
            UiUtils.errorAlert("Failed to create project", task.exception)
        }
        executor.submit(task)
    }

    /** Удаляет выбранный проект. */
    fun deleteProject(project: ProjectInfo) {
        val task = object : Task<Boolean>() {
            override fun call(): Boolean {
                project.pathObj.toFile().delete()
                return true
            }
        }
        task.setOnSucceeded { loadProjects(dir) }
        task.setOnFailed { UiUtils.errorAlert("Failed to delete project", task.exception) }
        executor.submit(task)
    }
}

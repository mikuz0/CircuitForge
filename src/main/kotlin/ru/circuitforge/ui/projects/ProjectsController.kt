package ru.circuitforge.ui.projects

import javafx.concurrent.Task
import org.slf4j.LoggerFactory
import ru.circuitforge.app.SettingsStore
import ru.circuitforge.project.ProjectInfo
import ru.circuitforge.project.ProjectRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Контроллер Менеджера проектов.
 * Выполняет I/O операции в фоновых потоках через Task, управляет Workspace и проектами.
 */
class ProjectsController(private val view: ProjectsView) {
    private val logger = LoggerFactory.getLogger(ProjectsController::class.java)
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "project-io").apply { isDaemon = true }
    }

    /** Устанавливает рабочую область и загружает проекты. */
    fun setWorkspace(path: Path) {
        // Сохраняем в настройки
        val settings = SettingsStore.load()
        SettingsStore.save(settings.copy(workspace = path.toString()))

        view.setWorkspacePath(path.toString())
        loadProjects(path)
    }

    /** Загружает список проектов из указанной директории. */
    fun loadProjects(dir: Path) {
        val task = object : Task<List<ProjectInfo>>() {
            override fun call(): List<ProjectInfo> {
                return ProjectRepository.scan(dir)
            }
        }
        task.setOnSucceeded {
            view.setProjects(task.value)
            view.setStatus("Loaded ${task.value.size} project(s)")
            updateModuleButtonsState()
        }
        task.setOnFailed {
            logger.error("Failed to load projects from $dir", task.exception)
            view.setStatus("Failed to load projects: ${task.exception?.message}", true)
        }
        executor.submit(task)
    }

    /** Создаёт новый проект с именем из поля ввода. */
    fun createNewProject() {
        val name = view.newProjectName().trim()
        val workspaceStr = view.workspacePath()

        if (name.isBlank()) {
            view.setStatus("Project name cannot be blank", true)
            return
        }

        val workspace = Path.of(workspaceStr)
        if (!Files.exists(workspace)) {
            view.setStatus("Workspace does not exist: $workspace", true)
            return
        }

        val task = object : Task<Unit>() {
            override fun call() {
                ProjectRepository.create(name, workspace)
            }
        }

        task.setOnSucceeded {
            view.clearNewProjectName()
            view.setStatus("Project '$name' created successfully")
            loadProjects(workspace)
            // Автоматически выделяем созданный проект
            view.selectProjectByName(name)
            updateModuleButtonsState()
        }

        task.setOnFailed {
            val errorMsg = task.exception?.message ?: "Unknown error"
            view.setStatus("Failed to create project: $errorMsg", true)
            logger.error("Failed to create project '$name'", task.exception)
        }

        executor.submit(task)
    }

    /** Обновляет состояние кнопок модулей. Вызывается при изменении выделения в ListView. */
    fun updateModuleButtonsState() {
        val hasSelection = view.selectedProject() != null
        view.setModuleButtonsEnabled(hasSelection)
    }
}

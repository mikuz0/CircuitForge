package ru.circuitforge.app

import javafx.application.Application
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.project.ProjectInfo
import ru.circuitforge.project.ProjectRepository
import ru.circuitforge.ui.components.ComponentsWindow
import ru.circuitforge.ui.editor.BomWindow
import ru.circuitforge.ui.nets.NetWindow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Главное приложение CircuitForge — Менеджер проектов.
 * Загружает Workspace из настроек при старте, управляет проектами и запускает модули.
 */
class MainApp : Application() {
    private val logger = LoggerFactory.getLogger(MainApp::class.java)
    private lateinit var view: ProjectsView
    private lateinit var controller: ProjectsController

    // Храним ссылки на окна модулей, чтобы GC не собрал их
    private val moduleWindows = mutableListOf<Stage>()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "project-io").apply { isDaemon = true }
    }

    override fun start(primaryStage: Stage) {
        logger.info("CircuitForge starting")

        AppPaths.initConfigDir()

        // Создаём view и контроллер
        view = ProjectsView()
        controller = ProjectsController(view)

        // Подписываемся на события кнопок
        view.onChooseWorkspace = { chooseWorkspace(primaryStage) }
        view.onCreateProject = { controller.createNewProject() }
        view.onBomClick = { openModule("bom") }
        view.onNetsClick = { openModule("nets") }
        view.onComponentsClick = { openModule("components") }

        view.setController(controller)

        // Загружаем Workspace из настроек
        val settings = SettingsStore.load()
        if (settings.workspace != null) {
            val workspacePath = Path.of(settings.workspace!!)
            if (Files.exists(workspacePath)) {
                controller.setWorkspace(workspacePath)
                logger.info("Workspace loaded from settings: $workspacePath")
            } else {
                logger.warn("Workspace path from settings does not exist: ${settings.workspace}")
            }
        } else {
            logger.info("No workspace set in settings, user needs to choose one")
        }

        val scene = view.buildScene()

        primaryStage.title = "CircuitForge — Project Manager"
        primaryStage.scene = scene
        primaryStage.minWidth = 700.0
        primaryStage.minHeight = 500.0
        primaryStage.show()

        logger.info("CircuitForge started successfully")
    }

    /** Открывает диалог выбора рабочей области через DirectoryChooser. */
    private fun chooseWorkspace(stage: Stage) {
        val chooser = DirectoryChooser()
        chooser.title = "Choose Workspace"
        val selectedDir = chooser.showDialog(stage)
        if (selectedDir != null) {
            val workspace = selectedDir.toPath()
            controller.setWorkspace(workspace)
        }
    }

    /** Открывает модуль для выбранного проекта как новое окно. */
    private fun openModule(moduleName: String) {
        val project = view.selectedProject()
        if (project == null) {
            view.setStatus("Select a project first", true)
            return
        }

        logger.info("Launching module '$moduleName' for project '${project.name}' at ${project.path}")

        // Создаём новое окно модуля в том же JavaFX приложении
        var moduleStage: Stage = Stage()
        when (moduleName) {
            "bom" -> {
                val bomWindow = BomWindow()
                bomWindow.openProject(project.path)
                moduleStage = bomWindow.getStage()
            }
            "nets" -> {
                val netWindow = NetWindow()
                netWindow.openProject(project.path)
                moduleStage = netWindow.getStage()
            }
            "components" -> {
                val compWindow = ComponentsWindow()
                compWindow.openProject(project.path)
                moduleStage = compWindow.getStage()
            }
        }
        moduleStage.title = "${project.name} — $moduleName"
        
        // Сохраняем ссылку на окно, чтобы GC не собрал его
        moduleWindows.add(moduleStage)
        
        moduleStage.show()
    }

    override fun stop() {
        logger.info("CircuitForge shutting down")
    }
}

/**
 * Представление Менеджера проектов.
 * Содержит выбор рабочей области, список проектов, поле создания нового проекта и кнопки модулей.
 */
class ProjectsView {
    // Workspace секция
    private val workspacePathField = TextField().apply { isEditable = false }
    private val chooseWorkspaceBtn = Button("Choose workspace...")

    // New project секция
    private val newProjectNameField = TextField()
    private val createProjectBtn = Button("Create")

    // Статус/ошибки
    private val statusLabel = Label()

    // Список проектов
    private val listView = ListView<ProjectInfo>().apply {
        prefHeight = 200.0
    }

    // Кнопки модулей
    private val bomBtn = Button("ПЭ3")
    private val netsBtn = Button("Связи")
    private val componentsBtn = Button("Компоненты")

    // Callback-свойства
    var onChooseWorkspace: (() -> Unit)? = null
    var onCreateProject: (() -> Unit)? = null
    var onBomClick: (() -> Unit)? = null
    var onNetsClick: (() -> Unit)? = null
    var onComponentsClick: (() -> Unit)? = null

    private val root = VBox(10.0).apply {
        padding = Insets(15.0)
        alignment = Pos.TOP_LEFT
        style = "-fx-background-color: #fafafa;"

        // Workspace секция
        val workspaceSection = VBox(5.0,
            Label("Workspace:"),
            HBox(10.0, workspacePathField, chooseWorkspaceBtn).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(workspacePathField, Priority.ALWAYS)
            }
        ).apply {
            padding = Insets(5.0)
            style = "-fx-background-color: #e8e8e8; -fx-padding: 5; -fx-border-radius: 3;"
        }

        // New project секция
        val newProjectSection = VBox(5.0,
            Label("New project:"),
            HBox(10.0, newProjectNameField, createProjectBtn).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(newProjectNameField, Priority.ALWAYS)
            }
        ).apply {
            padding = Insets(5.0)
            style = "-fx-background-color: #e8e8e8; -fx-padding: 5; -fx-border-radius: 3;"
        }

        // Статус
        statusLabel.apply {
            style = "-fx-text-fill: red; -fx-font-size: 12;"
        }

        // Список проектов
        val listSection = VBox(5.0, Label("Projects:"), listView).apply {
            VBox.setVgrow(listView, Priority.ALWAYS)
            padding = Insets(5.0)
            style = "-fx-background-color: white; -fx-border-color: #ccc; -fx-border-width: 1; -fx-border-radius: 3;"
        }

        // Кнопки модулей
        val moduleButtons = HBox(10.0, bomBtn, netsBtn, componentsBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(5.0)
            style = "-fx-background-color: #f0f0f0; -fx-padding: 5;"
        }
        setModuleButtonsEnabled(false)

        children.addAll(workspaceSection, newProjectSection, statusLabel, listSection, moduleButtons)
    }

    /** Строит сцену. */
    fun buildScene(): Scene {
        return Scene(root, 700.0, 500.0)
    }

    /** Устанавливает контроллер и подписывает кнопки. */
    fun setController(controller: ProjectsController) {
        chooseWorkspaceBtn.setOnAction { onChooseWorkspace?.invoke() }
        createProjectBtn.setOnAction { onCreateProject?.invoke() }
        bomBtn.setOnAction { onBomClick?.invoke() }
        netsBtn.setOnAction { onNetsClick?.invoke() }
        componentsBtn.setOnAction { onComponentsClick?.invoke() }

        // Слушатель изменения выделения — обновляет состояние кнопок модулей
        listView.selectionModel.selectedItemProperty().addListener { _, _, _ ->
            controller.updateModuleButtonsState()
        }
    }

    /** Устанавливает список проектов. */
    fun setProjects(projects: List<ProjectInfo>) {
        listView.items.setAll(projects)
    }

    /** Возвращает выбранный проект. */
    fun selectedProject(): ProjectInfo? = listView.selectionModel.selectedItem

    /** Выделяет проект по имени в списке. */
    fun selectProjectByName(name: String) {
        listView.items.find { it.name == name }
            ?.let { listView.selectionModel.select(it) }
    }

    /** Возвращает путь рабочей области. */
    fun workspacePath(): String = workspacePathField.text

    /** Устанавливает путь рабочей области. */
    fun setWorkspacePath(path: String) {
        workspacePathField.text = path
    }

    /** Устанавливает имя нового проекта. */
    fun newProjectName(): String = newProjectNameField.text

    /** Очищает поле нового проекта. */
    fun clearNewProjectName() {
        newProjectNameField.clear()
    }

    /** Устанавливает текст статуса (ошибки). */
    fun setStatus(text: String, isError: Boolean = false) {
        statusLabel.text = text
        statusLabel.style = if (isError) {
            "-fx-text-fill: red; -fx-font-size: 12;"
        } else {
            "-fx-text-fill: green; -fx-font-size: 12;"
        }
    }

    /** Включает/выключает кнопки модулей. */
    fun setModuleButtonsEnabled(enabled: Boolean) {
        bomBtn.isDisable = !enabled
        netsBtn.isDisable = !enabled
        componentsBtn.isDisable = !enabled
    }
}

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

/**
 * Top-level main() for the CircuitForge application.
 */
fun main() {
    Application.launch(MainApp::class.java)
}

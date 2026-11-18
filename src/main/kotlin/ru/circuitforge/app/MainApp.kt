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
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.ProjectInfo
import ru.circuitforge.project.ProjectRepository
import ru.circuitforge.ui.projects.ProjectsController
import ru.circuitforge.ui.projects.ProjectsView
import ru.circuitforge.ui.components.ComponentsWindow
import ru.circuitforge.ui.editor.BomWindow
import ru.circuitforge.ui.nets.NetWindow
import ru.circuitforge.ui.schematic.SchematicWindow
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

        // Загружаем язык из настроек
        val settings = SettingsStore.load()
        I18n.load(settings.getLocale())
        logger.info("Locale loaded: ${I18n.getCurrentLocale().displayName}")

        // Создаём view и контроллер
        view = ProjectsView()
        controller = ProjectsController(view)

        // Подписываемся на события кнопок
        view.onChooseWorkspace = { chooseWorkspace(primaryStage) }
        view.onCreateProject = { controller.createNewProject() }
        view.onBomClick = { openModule("bom") }
        view.onNetsClick = { openModule("nets") }
        view.onComponentsClick = { openModule("components") }
        view.onSchematicClick = { openModule("schematic") }
        view.onLocaleChange = { locale ->
            val newSettings = SettingsStore.load().withLocale(locale)
            I18n.load(locale)
            SettingsStore.save(newSettings)
            logger.info("Locale changed to: ${locale.displayName}")
            // Перезагружаем view для обновления текстов
            view.rebuildScene()
        }

        view.setController(controller)

        // Загружаем Workspace из настроек
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

        primaryStage.title = I18n.tr("app.title")
        primaryStage.scene = scene
        primaryStage.minWidth = 700.0
        primaryStage.minHeight = 500.0
        primaryStage.show()

        logger.info("CircuitForge started successfully")
    }

    /** Открывает диалог выбора рабочей области через DirectoryChooser. */
    private fun chooseWorkspace(stage: Stage) {
        val chooser = DirectoryChooser()
        chooser.title = "Choose workspace..."
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
            view.setStatus(I18n.tr("projects.select.first"), true)
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
            "schematic" -> {
                val schematicWindow = SchematicWindow()
                schematicWindow.openProject(project.path)
                moduleStage = schematicWindow.getStage()
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
 * Top-level main() for the CircuitForge application.
 */
fun main() {
    Application.launch(MainApp::class.java)
}

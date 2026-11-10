package ru.circuitforge.ui.editor

import javafx.application.Application
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.project.Project
import ru.circuitforge.project.ProjectRepository
import ru.circuitforge.ui.common.ModuleLauncher
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Path

/**
 * Standalone launch для редактора BOM.
 * Поддерживает аргумент командной строки с путём к проекту (.cfproj).
 */
class BomApp : ModuleLauncher() {
    private val logger = LoggerFactory.getLogger(BomApp::class.java)

    override fun createWindow(): Stage {
        val window = Stage()
        val bomWindow = BomWindow()

        // Проверяем, передан ли путь к проекту
        val projectPath = parameters.firstOrNull()
        if (projectPath != null) {
            try {
                val project = ProjectRepository.load(Path.of(projectPath))
                bomWindow.openProject(project)
                logger.info("BOM Editor opened with project: $projectPath")
            } catch (e: Exception) {
                logger.error("Failed to load project $projectPath", e)
                UiUtils.errorAlert("Failed to load project: $projectPath", e)
            }
        } else {
            logger.info("BOM Editor opened without project")
        }

        window.title = "BOM Editor"
        window.scene = javafx.scene.Scene(bomWindow.root)
        window.width = 1000.0
        window.height = 700.0
        return window
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Application.launch(BomApp::class.java, *args)
        }
    }
}

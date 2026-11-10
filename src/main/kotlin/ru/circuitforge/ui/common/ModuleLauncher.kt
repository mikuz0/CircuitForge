package ru.circuitforge.ui.common

import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared launcher for ProjectAware modules.
 * Usage: <binary> [project_path]
 * If project_path is provided, opens the project; otherwise shows an empty window.
 */
object ModuleLauncher {

    /**
     * Launches a ProjectAware module with optional project path argument.
     * Called from module's main() function.
     */
    fun <T : ProjectAware> run(awareFactory: () -> T, args: Array<String>) {
        val projectPath = if (args.isNotEmpty()) Path.of(args[0]) else null
        val launcher = object : Application() {
            override fun start(primaryStage: Stage) {
                val aware = awareFactory()
                primaryStage.title = "CircuitForge"
                primaryStage.scene = Scene(VBox(10.0), 400.0, 300.0)

                if (projectPath != null && Files.exists(projectPath)) {
                    aware.openProject(projectPath)
                } else {
                    aware.showWindow()
                }

                primaryStage.show()
            }
        }
        Application.launch(launcher::class.java, *args)
    }
}

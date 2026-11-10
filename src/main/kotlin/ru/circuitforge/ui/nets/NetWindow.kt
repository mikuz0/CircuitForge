package ru.circuitforge.ui.nets

import javafx.stage.Stage
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.project.Project

/**
 * Заглушка окна редактора сетей.
 */
class NetWindow : ProjectAware {
    private val stage = Stage()

    init {
        stage.title = "Net Editor (stub)"
        stage.scene = javafx.scene.Scene(javafx.scene.layout.VBox(10.0, javafx.scene.control.Label("Net Editor")))
    }

    override fun showWindow() = stage.show()
    override fun openProject(project: Project) {}
    override fun getStage(): Stage = stage
}

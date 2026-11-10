package ru.circuitforge.ui.nets

import javafx.stage.Stage
import ru.circuitforge.ui.common.ProjectAware
import java.nio.file.Path

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
    override fun openProject(projectPath: Path) {}
    override fun getStage(): Stage = stage
}

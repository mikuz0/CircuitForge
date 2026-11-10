package ru.circuitforge.app

import javafx.application.Application
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.stage.Stage
import ru.circuitforge.ui.editor.BomApp
import ru.circuitforge.ui.nets.NetApp
import ru.circuitforge.ui.components.ComponentsApp

/**
 * Главное приложение CircuitForge.
 * Запускает основной UI с навигацией по модулям.
 */
class MainApp : Application() {
    override fun start(primaryStage: Stage) {
        val bomButton = Button("Open BOM Editor")
        val netButton = Button("Open Net Editor")
        val componentsButton = Button("Open Components Editor")
        val label = Label("CircuitForge")

        bomButton.setOnAction { BomApp.run(BomApp::class.java) }
        netButton.setOnAction { NetApp.run(NetApp::class.java) }
        componentsButton.setOnAction { ComponentsApp.run(ComponentsApp::class.java) }

        val root = VBox(20.0, label, bomButton, netButton, componentsButton).apply {
            padding = Insets(40.0)
            style = "-fx-alignment: center;"
        }

        primaryStage.scene = Scene(root, 400, 300)
        primaryStage.title = "CircuitForge"
        primaryStage.show()
    }
}

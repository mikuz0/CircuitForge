package ru.circuitforge.ui.common

import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import java.nio.file.Path

/**
 * Utility functions shared across UI components.
 */
object UiUtils {
    const val ACCENT_COLOR = "#2196F3"
    const val PATH_COLOR = "#555555"

    /** Creates a StringProperty bound to a Path, returning the path string. */
    fun pathProperty(path: Path?): StringProperty {
        return SimpleStringProperty(path?.toString() ?: "<not opened>")
    }

    /** Creates a label displaying a path in monospace style. */
    fun pathLabel(pathProp: StringProperty): Label {
        val label = Label(pathProp.value)
        label.styleClass.add("path-label")
        // Стиль фиксированный, не нужно биндить
        label.style = "-fx-font-family: monospace; -fx-text-fill: $PATH_COLOR;"
        return label
    }

    /** Shows an error alert dialog. */
    fun errorAlert(
        title: String,
        message: String,
    ) {
        val alert = Alert(AlertType.ERROR)
        alert.title = title
        alert.headerText = null
        alert.contentText = message
        alert.showAndWait()
    }

    /** Shows a confirmation dialog and returns true if user confirmed. */
    fun confirmAlert(
        title: String,
        header: String?,
        content: String,
    ): Boolean {
        val alert = Alert(AlertType.CONFIRMATION)
        alert.title = title
        alert.headerText = header
        alert.contentText = content
        return alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == javafx.scene.control.ButtonType.OK
    }
}

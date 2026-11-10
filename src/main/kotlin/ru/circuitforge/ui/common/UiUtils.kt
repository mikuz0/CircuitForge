package ru.circuitforge.ui.common

import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonType
import org.slf4j.LoggerFactory

/**
 * Утилиты для UI: диалоговые окна подтверждения и ошибок.
 */
object UiUtils {
    private val logger = LoggerFactory.getLogger(UiUtils::class.java)

    /** Показывает диалог ошибки с сообщением и опциональной причиной. */
    fun errorAlert(message: String, throwable: Throwable? = null) {
        val alert = Alert(AlertType.ERROR)
        alert.title = "Error"
        alert.headerText = message
        throwable?.let { alert.contentText = it.message }
        alert.showAndWait()
        logger.error(message, throwable)
    }

    /** Показывает диалог подтверждения и возвращает результат. */
    fun confirmAlert(message: String): Boolean {
        val alert = Alert(AlertType.CONFIRMATION)
        alert.title = "Confirm"
        alert.headerText = message
        return alert.showAndWait().filter { it == ButtonType.OK }.isNotEmpty()
    }
}

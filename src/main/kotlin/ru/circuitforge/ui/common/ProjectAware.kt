package ru.circuitforge.ui.common

import javafx.stage.Stage
import java.nio.file.Path

/**
 * Интерфейс для модулей, работающих с проектами.
 * Позволяет модулям получать доступ к текущему проекту и окну.
 */
interface ProjectAware {
    /** Показывает окно модуля. */
    fun showWindow()

    /** Открывает проект по пути. */
    fun openProject(projectPath: Path)

    /** Возвращает stage (окно) для модальных диалогов. */
    fun getStage(): Stage
}

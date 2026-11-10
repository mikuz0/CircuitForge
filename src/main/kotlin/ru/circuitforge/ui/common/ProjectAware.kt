package ru.circuitforge.ui.common

import javafx.stage.Stage
import ru.circuitforge.project.Project

/**
 * Интерфейс для модулей, работающих с проектами.
 * Позволяет модулям получать доступ к текущему проекту и окну.
 */
interface ProjectAware {
    /** Показывает окно модуля. */
    fun showWindow()

    /** Устанавливает текущий проект для модуля. */
    fun openProject(project: Project)

    /** Возвращает stage (окно) для модальных диалогов. */
    fun getStage(): Stage
}

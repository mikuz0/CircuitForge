package ru.circuitforge.ui.projects

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ListView
import javafx.scene.layout.VBox
import ru.circuitforge.project.ProjectInfo

/**
 * Представление списка проектов с кнопками действий.
 * Отображает проекты в ListView с кнопками "Open", "New", "Delete".
 */
class ProjectsView {
    private val listView = ListView<ProjectInfo>()
    private val openButton = Button("Open")
    private val newButton = Button("New")
    private val deleteButton = Button("Delete")

    val root: VBox = VBox(10.0, listView, openButton, newButton, deleteButton).apply {
        padding = Insets(10.0)
        alignment = Pos.TOP_LEFT
    }

    /** Устанавливает список проектов для отображения. */
    fun setProjects(projects: List<ProjectInfo>) {
        listView.items.setAll(projects)
    }

    /** Возвращает выбранный проект. */
    fun selectedProject(): ProjectInfo? = listView.selectionModel.selectedItem

    /** Подписывается на событие открытия проекта. */
    fun onOpen(action: () -> Unit) {
        openButton.setOnAction { action() }
    }

    /** Подписывается на событие создания проекта. */
    fun onNew(action: () -> Unit) {
        newButton.setOnAction { action() }
    }

    /** Подписывается на событие удаления проекта. */
    fun onDelete(action: () -> Unit) {
        deleteButton.setOnAction { action() }
    }
}

package ru.circuitforge.ui.projects

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.ProjectInfo

/**
 * Представление Менеджера проектов.
 * Содержит выбор рабочей области, список проектов, поле создания нового проекта и кнопки модулей.
 */
class ProjectsView {
    // Workspace секция
    private val workspacePathField = TextField().apply { isEditable = false }
    private val chooseWorkspaceBtn = Button()

    // New project секция
    private val newProjectNameField = TextField()
    private val createProjectBtn = Button()

    // Статус/ошибки
    private val statusLabel = Label()

    // Список проектов
    private val listView = ListView<ProjectInfo>().apply {
        prefHeight = 200.0
    }

    // Кнопки модулей
    private val bomBtn = Button()
    private val netsBtn = Button()
    private val componentsBtn = Button()
    private val schematicBtn = Button()

    // Выбор языка
    private val localeComboBox = ComboBox<I18n.LocaleType>().apply {
        items.setAll(I18n.LocaleType.values().toList())
        valueProperty().value = I18n.getCurrentLocale()
        minWidth = 120.0
    }

    // Callback-свойства
    var onChooseWorkspace: (() -> Unit)? = null
    var onCreateProject: (() -> Unit)? = null
    var onBomClick: (() -> Unit)? = null
    var onNetsClick: (() -> Unit)? = null
    var onComponentsClick: (() -> Unit)? = null
    var onSchematicClick: (() -> Unit)? = null
    var onLocaleChange: ((I18n.LocaleType) -> Unit)? = null

    private val root = VBox(10.0).apply {
        padding = Insets(15.0)
        alignment = Pos.TOP_LEFT
        style = "-fx-background-color: #fafafa;"

        // Workspace секция
        val workspaceSection = VBox(5.0,
            Label(),
            HBox(10.0, workspacePathField, chooseWorkspaceBtn).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(workspacePathField, Priority.ALWAYS)
            }
        ).apply {
            padding = Insets(5.0)
            style = "-fx-background-color: #e8e8e8; -fx-padding: 5; -fx-border-radius: 3;"
        }

        // New project секция
        val newProjectSection = VBox(5.0,
            Label(),
            HBox(10.0, newProjectNameField, createProjectBtn).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(newProjectNameField, Priority.ALWAYS)
            }
        ).apply {
            padding = Insets(5.0)
            style = "-fx-background-color: #e8e8e8; -fx-padding: 5; -fx-border-radius: 3;"
        }

        // Статус
        statusLabel.apply {
            style = "-fx-text-fill: red; -fx-font-size: 12;"
        }

        // Список проектов
        val listSection = VBox(5.0, Label(), listView).apply {
            VBox.setVgrow(listView, Priority.ALWAYS)
            padding = Insets(5.0)
            style = "-fx-background-color: white; -fx-border-color: #ccc; -fx-border-width: 1; -fx-border-radius: 3;"
        }

        // Выбор языка
        val localeSection = HBox(10.0, Label(I18n.tr("projects.label.language")), localeComboBox).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(5.0)
            style = "-fx-background-color: #f0f0f0; -fx-padding: 5; -fx-border-radius: 3;"
        }

        // Кнопки модулей
        val moduleButtons = HBox(10.0, bomBtn, netsBtn, componentsBtn, schematicBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(5.0)
            style = "-fx-background-color: #f0f0f0; -fx-padding: 5;"
        }
        setModuleButtonsEnabled(false)

        children.addAll(workspaceSection, newProjectSection, statusLabel, listSection, localeSection, moduleButtons)
    }

    /** Строит сцену. */
    fun buildScene(): Scene {
        updateLabels()
        return Scene(root, 700.0, 500.0)
    }

    /** Перестраивает сцену с новыми переводами. */
    fun rebuildScene() {
        updateLabels()
        // Обновляем текст в localeComboBox
        localeComboBox.value = I18n.getCurrentLocale()
    }

    /** Обновляет все тексты на текущий язык. */
    private fun updateLabels() {
        chooseWorkspaceBtn.text = I18n.tr("projects.btn.choose.workspace")
        createProjectBtn.text = I18n.tr("projects.btn.create")
        bomBtn.text = I18n.tr("projects.btn.bom")
        netsBtn.text = I18n.tr("projects.btn.nets")
        componentsBtn.text = I18n.tr("projects.btn.components")
        schematicBtn.text = I18n.tr("projects.btn.schematic")

        // Обновляем Label в секциях
        val workspaceSection = root.children[0] as VBox
        val newProjectSection = root.children[1] as VBox
        val listSection = root.children[3] as VBox

        (workspaceSection.children[0] as Label).text = I18n.tr("projects.label.workspace")
        (newProjectSection.children[0] as Label).text = I18n.tr("projects.label.new.project")
        (listSection.children[0] as Label).text = I18n.tr("projects.label.projects")
    }

    /** Устанавливает контроллер и подписывает кнопки. */
    fun setController(controller: ProjectsController) {
        chooseWorkspaceBtn.setOnAction { onChooseWorkspace?.invoke() }
        createProjectBtn.setOnAction { onCreateProject?.invoke() }
        bomBtn.setOnAction { onBomClick?.invoke() }
        netsBtn.setOnAction { onNetsClick?.invoke() }
        componentsBtn.setOnAction { onComponentsClick?.invoke() }
        schematicBtn.setOnAction { onSchematicClick?.invoke() }

        // Слушатель выбора языка
        localeComboBox.valueProperty().addListener { _, _, newLocale ->
            onLocaleChange?.invoke(newLocale)
        }

        // Слушатель изменения выделения — обновляет состояние кнопок модулей
        listView.selectionModel.selectedItemProperty().addListener { _, _, _ ->
            controller.updateModuleButtonsState()
        }
    }

    /** Устанавливает список проектов. */
    fun setProjects(projects: List<ProjectInfo>) {
        listView.items.setAll(projects)
    }

    /** Возвращает выбранный проект. */
    fun selectedProject(): ProjectInfo? = listView.selectionModel.selectedItem

    /** Выделяет проект по имени в списке. */
    fun selectProjectByName(name: String) {
        listView.items.find { it.name == name }
            ?.let { listView.selectionModel.select(it) }
    }

    /** Возвращает путь рабочей области. */
    fun workspacePath(): String = workspacePathField.text

    /** Устанавливает путь рабочей области. */
    fun setWorkspacePath(path: String) {
        workspacePathField.text = path
    }

    /** Устанавливает имя нового проекта. */
    fun newProjectName(): String = newProjectNameField.text

    /** Очищает поле нового проекта. */
    fun clearNewProjectName() {
        newProjectNameField.clear()
    }

    /** Устанавливает текст статуса (ошибки). */
    fun setStatus(text: String, isError: Boolean = false) {
        statusLabel.text = text
        statusLabel.style = if (isError) {
            "-fx-text-fill: red; -fx-font-size: 12;"
        } else {
            "-fx-text-fill: green; -fx-font-size: 12;"
        }
    }

    /** Включает/выключает кнопки модулей. */
    fun setModuleButtonsEnabled(enabled: Boolean) {
        bomBtn.isDisable = !enabled
        netsBtn.isDisable = !enabled
        componentsBtn.isDisable = !enabled
        schematicBtn.isDisable = !enabled
    }
}

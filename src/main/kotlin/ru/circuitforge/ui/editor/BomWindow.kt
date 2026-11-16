package ru.circuitforge.ui.editor

import javafx.collections.FXCollections
import javafx.collections.transformation.SortedList
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.*
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

private const val CFCOMP_EXT = ".cfcomp"

/**
 * Полноформатное окно редактора ПЭ3 (BOM).
 * Содержит таблицу с колонками #, RefDes, Component, Comment.
 * Поддерживает Add/Duplicate/Delete/Save/Revert, dirty tracking, горячие клавиши.
 */
class BomWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(BomWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "bom-io").apply { isDaemon = true }
    }

    private var projectPath: Path? = null

    // Данные
    private val bomItems = FXCollections.observableArrayList<BomItem>()
    private var savedSnapshot: String = "" // Для dirty tracking

    // UI элементы
    private val tableView = TableView<BomItem>()

    // Toolbar кнопки
    private val addButton = Button(I18n.tr("bom.btn.add"))
    private val duplicateButton = Button(I18n.tr("bom.btn.duplicate"))
    private val deleteButton = Button(I18n.tr("bom.btn.delete"))
    private val saveButton = Button(I18n.tr("bom.btn.save"))
    private val revertButton = Button(I18n.tr("bom.btn.revert"))
    private val closeButton = Button(I18n.tr("bom.btn.close"))

    // Форма для редактирования выбранной строки
    private val refDesField = TextField()
    private val commentField = TextField()

    init {
        stage.title = I18n.tr("bom.window.title")
        setupTableView()
        setupToolbar()
        setupHotKeys()
        stage.scene = Scene(createRoot(), 900.0, 600.0)
    }

    /** Создаёт корневой контейнер. */
    private fun createRoot(): BorderPane {
        val root = BorderPane()
        root.top = createToolbar()
        root.center = createTablePanel()
        root.bottom = createFormPanel()
        return root
    }

    /** Создаёт панель инструментов. */
    private fun createToolbar(): HBox {
        val toolbar = HBox(10.0, addButton, duplicateButton, deleteButton, saveButton, revertButton, closeButton).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style = "-fx-background-color: #f5f5f5; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;"
        }
        return toolbar
    }

    /** Создаёт панель таблицы. */
    private fun createTablePanel(): VBox {
        return VBox(0.0, tableView).apply {
            style = "-fx-background-color: white;"
            VBox.setVgrow(tableView, Priority.ALWAYS)
        }
    }

    /** Создаёт панель формы редактирования. */
    private fun createFormPanel(): VBox {
        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 8.0
        grid.padding = Insets(10.0)
        grid.style = "-fx-background-color: #fafafa; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;"

        val refDesLabel = Label(I18n.tr("bom.label.ref.des"))
        val commentLabel = Label(I18n.tr("bom.label.comment"))
        GridPane.setConstraints(refDesLabel, 0, 0)
        GridPane.setConstraints(refDesField, 1, 0)
        GridPane.setConstraints(commentLabel, 0, 1)
        GridPane.setConstraints(commentField, 1, 1)

        grid.children.addAll(refDesLabel, refDesField, commentLabel, commentField)

        return VBox(10.0, Label(I18n.tr("bom.label.edit.selected")), grid).apply {
            padding = Insets(10.0)
        }
    }

    /** Настраивает TableView с колонками. */
    private fun setupTableView() {
        // Колонка # — порядковый номер (readonly)
        val numberCol = TableColumn<BomItem, String>(I18n.tr("bom.col.number"))
        numberCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.number.toString()) }
        numberCol.isEditable = false
        numberCol.prefWidth = 50.0

        // Колонка RefDes — позиционное обозначение (readonly, редактируем через форму)
        val refDesCol = TableColumn<BomItem, String>(I18n.tr("bom.col.ref.des"))
        refDesCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.refDes) }
        refDesCol.prefWidth = 200.0

        // Колонка Component — наименование из библиотеки (readonly)
        val componentCol = TableColumn<BomItem, String>(I18n.tr("bom.col.component"))
        componentCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(resolveComponentName(it.value.componentSlug)) }
        componentCol.isEditable = false
        componentCol.prefWidth = 250.0

        // Колонка Comment — комментарий (readonly, редактируем через форму)
        val commentCol = TableColumn<BomItem, String>(I18n.tr("bom.col.comment"))
        commentCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.comment) }
        commentCol.prefWidth = 300.0

        tableView.columns.setAll(numberCol, refDesCol, componentCol, commentCol)
        tableView.items = SortedList(bomItems)

        // Выделение строки → заполнение формы
        tableView.selectionModel.selectedItemProperty().addListener { _, _, new ->
            if (new != null) {
                refDesField.text = new.refDes
                commentField.text = new.comment
            }
        }
    }

    /** Разрешает имя компонента по slug. */
    private fun resolveComponentName(slug: String): String {
        if (slug.isBlank()) return I18n.tr("bom.placeholder.not.found")
        projectPath?.let { dir ->
            val compPath = dir.resolve("components").resolve("${slug}$CFCOMP_EXT")
            if (Files.exists(compPath)) {
                try {
                    val comp = ComponentRepository.load(compPath)
                    return comp.name
                } catch (e: Exception) {
                    logger.warn("Failed to load component $slug", e)
                }
            }
        }
        return I18n.tr("bom.placeholder.not.found.detail", slug)
    }

    /** Создаёт и подписывает toolbar. */
    private fun setupToolbar() {
        addButton.setOnAction { showAddItemForm() }
        duplicateButton.setOnAction { duplicateRow() }
        deleteButton.setOnAction { deleteRow() }
        saveButton.setOnAction { saveBom() }
        revertButton.setOnAction { revertChanges() }
        closeButton.setOnAction { handleWindowClosing() }
    }

    /** Настраивает горячие клавиши. */
    private fun setupHotKeys() {
        stage.sceneProperty().addListener { _, _, newScene ->
            newScene?.onKeyPressed = EventHandler { event: KeyEvent -> handleHotKey(event) }
        }
    }

    /** Обработчик горячих клавиш. */
    private fun handleHotKey(event: KeyEvent) {
        when {
            event.code == KeyCode.S && event.isControlDown -> { saveBom(); event.consume() }
            event.code == KeyCode.D && event.isControlDown -> { duplicateRow(); event.consume() }
            event.code == KeyCode.DELETE -> { deleteRow(); event.consume() }
        }
    }

    /** Проверяет, есть ли несохранённые изменения. */
    private fun isDirty(): Boolean {
        return serializeCurrentState() != savedSnapshot
    }

    /** Сериализует текущее состояние BOM в строку. */
    private fun serializeCurrentState(): String {
        return bomItems.map { "${it.number}|${it.refDes}|${it.componentSlug}|${it.comment}" }.joinToString(";")
    }

    /** Помечает состояние как изменённое. */
    private fun markDirty() {
        // Можно добавить визуальный индикатор изменений
    }

    /** Показывает ChoiceDialog со списком компонентов для добавления элемента. */
    private fun showAddItemForm() {
        val components = projectPath?.let { dir ->
            ComponentRepository.listComponents(dir)
        } ?: emptyList()

        if (components.isEmpty()) {
            UiUtils.errorAlert(I18n.tr("bom.alert.no.components"), I18n.tr("bom.alert.no.components.text"))
            return
        }

        val dialog = ChoiceDialog(components.first(), components.map { "${it.name} (${it.refPrefix})" }.toTypedArray())
        dialog.title = I18n.tr("bom.dialog.add")
        dialog.headerText = I18n.tr("bom.dialog.select.component")
        dialog.contentText = I18n.tr("bom.dialog.choose.component")

        val result = dialog.showAndWait()
        if (result.isPresent) {
            val selectedIndex = components.map { "${it.name} (${it.refPrefix})" }
                .indexOf(result.get())
            if (selectedIndex >= 0) {
                addItemFromComponent(components[selectedIndex])
            }
        }
    }

    /** Добавляет элемент BOM на основе выбранного компонента. */
    private fun addItemFromComponent(component: ComponentSummary) {
        // Генерируем RefDes: берём refPrefix и ищем максимальный номер
        val prefix = component.refPrefix.uppercase()
        val existingRefs = bomItems.mapNotNull { item ->
            val refDes = item.refDes
            val numStr = refDes.dropWhile { !it.isDigit() }
            val num = numStr.toIntOrNull() ?: return@mapNotNull null
            prefix to num
        }
        val maxNum = existingRefs.maxOfOrNull { it.second } ?: 0
        val newRefDes = "$prefix${maxNum + 1}"

        val newItem = BomItem(
            number = 0, // Будет пересчитан при сохранении
            refDes = newRefDes,
            componentSlug = component.slug,
            comment = ""
        )

        bomItems.add(newItem)
        markDirty()
    }

    /** Дублирует выбранную строку. */
    private fun duplicateRow() {
        val selected = tableView.selectionModel.selectedItem ?: return
        val index = bomItems.indexOf(selected)

        // Генерируем следующий RefDes
        val prefix = selected.refDes.takeWhile { c -> c.isLetter() }.ifEmpty { "X" }
        val existingRefs = bomItems.mapNotNull { item ->
            val refDes = item.refDes
            val numStr = refDes.dropWhile { !it.isDigit() }
            val num = numStr.toIntOrNull() ?: return@mapNotNull null
            prefix to num
        }
        val maxNum = existingRefs.maxOfOrNull { it.second } ?: 0
        val newRefDes = "$prefix${maxNum + 1}"

        val duplicate = selected.copy(
            refDes = newRefDes,
            number = 0
        )

        bomItems.add(index + 1, duplicate)
        tableView.selectionModel.select(index + 1)
        markDirty()
    }

    /** Удаляет выбранную строку. */
    private fun deleteRow() {
        val selected = tableView.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert(I18n.tr("common.alert.confirm"), I18n.tr("bom.alert.delete.row"), I18n.tr("bom.alert.delete.row.text", selected.refDes))) return
        bomItems.remove(selected)
        markDirty()
    }

    /** Сохраняет BOM документ. */
    private fun saveBom() {
        // Валидация: все RefDes должны быть заполнены и уникальны
        val refDesList = bomItems.map { it.refDes }
        if (refDesList.any { it.isBlank() }) {
            UiUtils.errorAlert(I18n.tr("bom.alert.validation.error"), I18n.tr("bom.alert.ref.des.required"))
            return
        }
        if (refDesList.size != refDesList.toSet().size) {
            UiUtils.errorAlert(I18n.tr("bom.alert.validation.error"), I18n.tr("bom.alert.ref.des.unique"))
            return
        }

        // Перенумерация #
        val numberedItems = bomItems.mapIndexed { index, item ->
            item.copy(number = index + 1)
        }

        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() {
                projectPath?.let { dir ->
                    val bomDoc = BomDocument(schemaVersion = 1, items = numberedItems)
                    BomRepository.save(dir, bomDoc)
                    savedSnapshot = serializeCurrentState()
                    logger.info("BOM saved successfully")
                }
            }
        }

        task.setOnSucceeded {
            val alert = Alert(Alert.AlertType.INFORMATION)
            alert.title = I18n.tr("bom.alert.success")
            alert.headerText = null
            alert.contentText = I18n.tr("bom.alert.saved")
            alert.showAndWait()
        }
        task.setOnFailed {
            UiUtils.errorAlert(I18n.tr("bom.alert.save.failed"), task.exception?.message ?: I18n.tr("common.alert.unknown.error"))
            logger.error("Failed to save BOM", task.exception)
        }
        executor.submit(task)
    }

    /** Отменяет все несохранённые изменения. */
    private fun revertChanges() {
        projectPath?.let { dir ->
            val bomDoc = BomRepository.load(dir)
            bomItems.setAll(bomDoc.items)
            savedSnapshot = serializeCurrentState()
            logger.info("BOM reverted to last saved state")
        }
    }

    /** Обрабатывает закрытие окна с защитой от потери данных. */
    private fun handleWindowClosing() {
        if (isDirty()) {
            val alert = Alert(Alert.AlertType.CONFIRMATION)
            alert.title = I18n.tr("bom.alert.unsaved")
            alert.headerText = I18n.tr("bom.alert.unsaved.header")
            alert.contentText = I18n.tr("bom.alert.unsaved.text")

            val result = alert.showAndWait()
            when {
                result.orElse(ButtonType.CANCEL) == ButtonType.YES -> {
                    saveBom()
                    if (!isDirty()) stage.close()
                }
                result.orElse(ButtonType.CANCEL) == ButtonType.NO -> stage.close()
                // CANCEL — ничего не делаем
            }
        } else {
            stage.close()
        }
    }

    override fun showWindow() = stage.show()

    override fun openProject(projectPath: Path) {
        this.projectPath = projectPath

        // Загружаем BOM или инициализируем из компонентов
        val bomDoc = BomRepository.load(projectPath)
        if (bomDoc.items.isEmpty()) {
            logger.info("No BOM found, initializing from components")
            bomItems.setAll(BomRepository.initFromComponents(projectPath).items)
        } else {
            bomItems.setAll(bomDoc.items)
        }

        savedSnapshot = serializeCurrentState()
        logger.info("BOM opened for project: $projectPath")
    }

    override fun getStage(): Stage = stage
}

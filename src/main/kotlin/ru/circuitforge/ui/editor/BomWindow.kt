package ru.circuitforge.ui.editor

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.*
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Полноценное окно редактора ПЭ3 (BOM).
 */
class BomWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(BomWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "bom-io").apply { isDaemon = true }
    }

    private var projectPath: Path? = null

    private val bomRows = FXCollections.observableArrayList<BomRow>()
    private var savedSnapshot: String = ""

    private val tableView = TableView<BomRow>()
    private val addButton = Button(I18n.tr("bom.btn.add") ?: "Добавить элемент")
    private val duplicateButton = Button(I18n.tr("bom.btn.duplicate") ?: "Дублировать")
    private val deleteButton = Button(I18n.tr("bom.btn.delete") ?: "Удалить")
    private val saveButton = Button(I18n.tr("bom.btn.save") ?: "Сохранить")
    private val revertButton = Button(I18n.tr("bom.btn.revert") ?: "Отменить")
    private val closeButton = Button(I18n.tr("bom.btn.close") ?: "Закрыть")
    private val statusLabel = Label("")

    init {
        stage.title = I18n.tr("bom.window.title") ?: "BOM Editor (ПЭ3)"
        setupTableView()
        setupToolbar()
        stage.scene = Scene(createRoot(), 1000.0, 650.0)
    }

    private fun createRoot(): BorderPane {
        val root = BorderPane()
        root.top = createToolbar()
        root.center = tableView
        root.bottom = HBox(10.0, saveButton, revertButton, statusLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;"
        }
        return root
    }

    private fun createToolbar(): HBox {
        return HBox(10.0, addButton, duplicateButton, deleteButton, closeButton).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style = "-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;"
        }
    }

    private fun setupToolbar() {
        addButton.setOnAction { showAddItemDialog() }
        duplicateButton.setOnAction { duplicateRow() }
        deleteButton.setOnAction { deleteRow() }
        saveButton.setOnAction { saveBom() }
        revertButton.setOnAction { revertChanges() }
        closeButton.setOnAction { stage.close() }
    }

    private fun setupTableView() {
        tableView.isEditable = true

        // Колонка # (readonly)
        val numberCol = TableColumn<BomRow, String>("#")
        numberCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.number.toString()) }
        numberCol.isEditable = false
        numberCol.prefWidth = 50.0

        // Колонка RefDes (редактируемая)
        val refDesCol = TableColumn<BomRow, String>("Поз. обозначение")
        refDesCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.refDes) }
        refDesCol.prefWidth = 150.0
        refDesCol.cellFactory = javafx.util.Callback { _ -> TextFieldTableCell<BomRow, String>() }
        refDesCol.setOnEditCommit { event ->
            val row = event.rowValue
            val newRefDes = event.newValue?.trim() ?: ""
            if (newRefDes.isNotBlank()) {
                val index = bomRows.indexOf(row)
                if (index >= 0) {
                    bomRows[index] = row.copy(refDes = newRefDes)
                    markDirty()
                }
            } else {
                tableView.refresh()
            }
        }

        // Колонка Component (readonly)
        val componentCol = TableColumn<BomRow, String>("Компонент")
        componentCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.componentName) }
        componentCol.isEditable = false
        componentCol.prefWidth = 250.0

        // Колонка Comment (редактируемая)
        val commentCol = TableColumn<BomRow, String>("Комментарий")
        commentCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.comment) }
        commentCol.prefWidth = 300.0
        commentCol.cellFactory = javafx.util.Callback { _ ->
            object : TableCell<BomRow, String>() {
                private val textField = TextField()
                init {
                    textField.setOnAction {
                        commitEdit(textField.text)
                    }
                    textField.focusedProperty().addListener { _, _, isFocused ->
                        if (!isFocused) commitEdit(textField.text)
                    }
                }
                override fun startEdit() {
                    super.startEdit()
                    textField.text = item ?: ""
                    graphic = textField
                    text = null
                    textField.requestFocus()
                    textField.selectAll()
                }
                override fun cancelEdit() {
                    super.cancelEdit()
                    text = item
                    graphic = null
                }
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                    } else if (isEditing) {
                        textField.text = item
                        graphic = textField
                        text = null
                    } else {
                        text = item
                        graphic = null
                    }
                }
            }
        }
        commentCol.setOnEditCommit { event ->
            val row = event.rowValue
            val newComment = event.newValue?.trim() ?: ""
            val index = bomRows.indexOf(row)
            if (index >= 0) {
                bomRows[index] = row.copy(comment = newComment)
                markDirty()
            }
        }

        tableView.columns.setAll(numberCol, refDesCol, componentCol, commentCol)
        tableView.items = bomRows
    }

    private fun showAddItemDialog() {
        val projectDir = projectPath ?: return
        val components = ComponentRepository.listComponents(projectDir)
        if (components.isEmpty()) {
            UiUtils.errorAlert("Нет компонентов", "В проекте нет компонентов. Создайте их в редакторе компонентов.")
            return
        }

        val dialog = ChoiceDialog(components.first().name, components.map { it.name })
        dialog.title = "Добавить элемент"
        dialog.headerText = "Выберите компонент"
        dialog.contentText = "Компонент:"

        val result = dialog.showAndWait()
        if (result.isPresent) {
            val selectedName = result.get()
            val comp = components.find { it.name == selectedName } ?: return

            // Генерируем RefDes: prefix + maxNum + 1
            val prefix = comp.refPrefix.uppercase()
            val existingNums = bomRows.mapNotNull { row ->
                if (row.refDes.startsWith(prefix)) {
                    row.refDes.removePrefix(prefix).toIntOrNull()
                } else null
            }
            val maxNum = existingNums.maxOrNull() ?: 0
            val newRefDes = "$prefix${maxNum + 1}"

            val newRow = BomRow(
                bomnetId = UUID.randomUUID().toString(),
                number = 0, // Пересчитается при сохранении
                refDes = newRefDes,
                componentId = comp.id,
                componentSlug = comp.slug,
                componentName = comp.name,
                comment = "",
                refPrefix = prefix
            )
            bomRows.add(newRow)
            markDirty()
            logger.info("Added BOM item: $newRefDes (${comp.name})")
        }
    }

    private fun duplicateRow() {
        val selected = tableView.selectionModel.selectedItem ?: return
        val prefix = selected.refPrefix
        val existingNums = bomRows.mapNotNull { row ->
            if (row.refDes.startsWith(prefix)) {
                row.refDes.removePrefix(prefix).toIntOrNull()
            } else null
        }
        val maxNum = existingNums.maxOrNull() ?: 0
        val newRefDes = "$prefix${maxNum + 1}"

        val dup = selected.copy(
            bomnetId = UUID.randomUUID().toString(),
            refDes = newRefDes,
            number = 0
        )
        bomRows.add(dup)
        markDirty()
    }

    private fun deleteRow() {
        val selected = tableView.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert("Удалить?", null, "Удалить строку '${selected.refDes}'?")) return
        bomRows.remove(selected)
        markDirty()
    }

    private fun saveBom() {
        val refDesList = bomRows.map { it.refDes }
        if (refDesList.any { it.isBlank() }) {
            UiUtils.errorAlert("Ошибка", "Все RefDes должны быть заполнены.")
            return
        }
        if (refDesList.size != refDesList.toSet().size) {
            UiUtils.errorAlert("Ошибка", "Все RefDes должны быть уникальны.")
            return
        }

        // Перенумерация #
        val numberedRows = bomRows.mapIndexed { index, row -> row.copy(number = index + 1) }

        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() {
                projectPath?.let { dir ->
                    val items = numberedRows.map { row ->
                        BomItem(
                            bomnetId = row.bomnetId,
                            number = row.number,
                            refDes = row.refDes,
                            componentId = row.componentId,
                            componentSlug = row.componentSlug,
                            comment = row.comment
                        )
                    }
                    BomRepository.save(dir, BomDocument(schemaVersion = 1, items = items))
                    savedSnapshot = serializeCurrentState()
                }
            }
        }
        task.setOnSucceeded {
            statusLabel.text = "Сохранено"
            logger.info("BOM saved")
        }
        task.setOnFailed {
            UiUtils.errorAlert("Ошибка", task.exception?.message ?: "Unknown error")
            logger.error("Failed to save BOM", task.exception)
        }
        executor.submit(task)
    }

    private fun revertChanges() {
        projectPath?.let { dir ->
            val rows = BomRepository.loadRows(dir)
            bomRows.setAll(rows)
            savedSnapshot = serializeCurrentState()
            statusLabel.text = "Отменено"
        }
    }

    private fun serializeCurrentState(): String {
        return bomRows.map { "${it.bomnetId}|${it.refDes}|${it.componentId}|${it.comment}" }.joinToString(";")
    }

    private fun markDirty() {
        statusLabel.text = "Не сохранено"
        statusLabel.style = "-fx-text-fill: orange;"
    }

    override fun showWindow() = stage.show()

    override fun openProject(projectPath: Path) {
        this.projectPath = projectPath
        stage.title = "CircuitForge — BOM — $projectPath"

        val rows = BomRepository.loadRows(projectPath)
        bomRows.setAll(rows)
        savedSnapshot = serializeCurrentState()
        statusLabel.text = "Загружено: ${rows.size} элементов"
        logger.info("BOM opened: $projectPath, ${rows.size} items")
    }

    override fun getStage(): Stage = stage
}

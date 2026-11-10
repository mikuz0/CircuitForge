package ru.circuitforge.ui.editor

import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Полноценное окно редактора BOM.
 * Содержит BorderPane с Toolbar, TableView<BomRow> и кнопками Add/Duplicate/Delete.
 */
class BomWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(BomWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "bom-io").apply { isDaemon = true }
    }

    private val tableView = TableView<BomRow>()
    private val bomItemsView = ListView<BomItem>()

    // Поля для добавления/редактирования
    private val refField = TextField()
    private val valueField = TextField()
    private val packageField = TextField()
    private val manufacturerField = TextField()
    private val remarksField = TextField()
    private val quantityField = TextField("1")

    private val addButton = Button("Add")
    private val duplicateButton = Button("Duplicate")
    private val deleteButton = Button("Delete")
    private val saveButton = Button("Save BOM")
    private val closeButton = Button("Close")

    private var editingIndex: Int? = null
    private var project: Project? = null
    private var projectDir: Path? = null

    private val bomRows: ObservableList<BomRow> = FXCollections.observableArrayList()
    private val bomItems: ObservableList<BomItem> = FXCollections.observableArrayList()

    val root: BorderPane = BorderPane().apply {
        // Top — Toolbar
        top = createToolbar()

        // Left — список групп BOM
        val leftPanel = VBox(10.0, Label("BOM Groups"), bomItemsView).apply {
            padding = Insets(10.0)
            minWidth = 250.0
            VBox.setVgrow(bomItemsView, Priority.ALWAYS)
        }

        // Center — таблица строк BOM
        val centerPanel = VBox(10.0, Label("BOM Rows"), tableView).apply {
            padding = Insets(10.0)
            VBox.setVgrow(tableView, Priority.ALWAYS)
        }

        // Bottom — форма и кнопки
        bottom = createFormPanel()

        center = BorderPane(centerPanel, leftPanel, null, bottom, null)
    }

    init {
        stage.title = "BOM Editor"
        stage.scene = javafx.scene.Scene(root)
        stage.width = 1000.0
        stage.height = 700.0
        setupTableView()
        setupEventHandlers()
    }

    /** Создаёт панель инструментов с кнопками. */
    private fun createToolbar(): HBox {
        val toolbar = HBox(10.0, saveButton, closeButton)
        toolbar.alignment = Pos.CENTER_RIGHT
        toolbar.padding = Insets(5.0)
        toolbar.style = "-fx-background-color: #f5f5f5; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;"
        return toolbar
    }

    /** Создаёт панель формы для добавления/редактирования строки BOM. */
    private fun createFormPanel(): GridPane {
        val grid = GridPane()
        grid.alignment = Pos.TOP_LEFT
        grid.hgap = 10
        grid.vgap = 8
        grid.padding = Insets(10.0)
        grid.style = "-fx-background-color: #fafafa; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;"

        grid.add(Label("Reference:"), 0, 0)
        grid.add(refField, 1, 0)
        grid.add(Label("Value:"), 0, 1)
        grid.add(valueField, 1, 1)
        grid.add(Label("Package:"), 0, 2)
        grid.add(packageField, 1, 2)
        grid.add(Label("Manufacturer:"), 0, 3)
        grid.add(manufacturerField, 1, 3)
        grid.add(Label("Quantity:"), 0, 4)
        grid.add(quantityField, 1, 4)
        grid.add(Label("Remarks:"), 0, 5)
        grid.add(remarksField, 1, 5)

        val buttonBar = HBox(10.0, addButton, duplicateButton, deleteButton)
        buttonBar.alignment = Pos.CENTER_LEFT
        grid.add(buttonBar, 0, 6, 2, 1)

        return grid
    }

    /** Настраивает TableView с колонками BOM. */
    private fun setupTableView() {
        val refCol = TableColumn<BomRow, String>("Reference")
        refCol.setCellValueFactory { it.value.reference }
        refCol.prefWidth = 120

        val valCol = TableColumn<BomRow, String>("Value")
        valCol.setCellValueFactory { it.value.value }
        valCol.prefWidth = 150

        val pkgCol = TableColumn<BomRow, String>("Package")
        pkgCol.setCellValueFactory { it.value.packageType }
        pkgCol.prefWidth = 100

        val manCol = TableColumn<BomRow, String>("Manufacturer")
        manCol.setCellValueFactory { it.value.manufacturer }
        manCol.prefWidth = 150

        val qtyCol = TableColumn<BomRow, String>("Quantity")
        qtyCol.setCellValueFactory { it.value.quantity.toString() }
        qtyCol.prefWidth = 80

        val remarksCol = TableColumn<BomRow, String>("Remarks")
        remarksCol.setCellValueFactory { it.value.remarks }
        remarksCol.prefWidth = 200

        tableView.columns.setAll(refCol, valCol, pkgCol, manCol, qtyCol, remarksCol)
        tableView.items = bomRows
        tableView.selectionModel.selectedItemProperty().addListener { _, _, new ->
            if (new != null) loadRowIntoForm(new)
        }
    }

    /** Настраивает обработчики событий кнопок. */
    private fun setupEventHandlers() {
        addButton.setOnAction { saveRow() }
        duplicateButton.setOnAction { duplicateRow() }
        deleteButton.setOnAction { deleteRow() }
        saveButton.setOnAction { saveBom() }
        closeButton.setOnAction { stage.close() }
    }

    /** Сохраняет новую строку BOM. */
    private fun saveRow() {
        val ref = refField.text
        val value = valueField.text
        val pkg = packageField.text
        val man = manufacturerField.text
        val qty = quantityField.text.toIntOrNull() ?: 1
        val remarks = remarksField.text

        if (ref.isBlank()) {
            UiUtils.errorAlert("Reference cannot be blank")
            return
        }

        val row = BomRow(
            reference = ref,
            value = value,
            packageType = pkg,
            manufacturer = man,
            quantity = qty,
            remarks = remarks
        )

        bomRows.add(row)
        updateBomItems()
        clearForm()
    }

    /** Дублирует выбранную строку BOM. */
    private fun duplicateRow() {
        val selected = tableView.selectionModel.selectedItem ?: return
        val duplicate = selected.copy(
            reference = selected.reference + "_copy",
            quantity = selected.quantity + 1
        )
        val index = bomRows.indexOf(selected)
        bomRows.add(index + 1, duplicate)
        updateBomItems()
    }

    /** Удаляет выбранную строку BOM. */
    private fun deleteRow() {
        val selected = tableView.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert("Delete row '${selected.reference}'?")) return
        bomRows.remove(selected)
        updateBomItems()
        clearForm()
    }

    /** Загружает данные строки в форму. */
    private fun loadRowIntoForm(row: BomRow) {
        editingIndex = bomRows.indexOf(row)
        refField.text = row.reference
        valueField.text = row.value
        packageField.text = row.packageType
        manufacturerField.text = row.manufacturer
        quantityField.text = row.quantity.toString()
        remarksField.text = row.remarks
    }

    /** Очищает форму и сбрасывает состояние редактирования. */
    private fun clearForm() {
        refField.clear()
        valueField.clear()
        packageField.clear()
        manufacturerField.clear()
        quantityField.text = "1"
        remarksField.clear()
        editingIndex = null
    }

    /** Обновляет группы BOM из строк. */
    private fun updateBomItems() {
        val grouped = bomRows.groupBy { it.reference.takeWhile { c -> c.isLetter() } }
        bomItems.setAll(grouped.map { (prefix, rows) ->
            BomItem(
                refPrefix = prefix ?: "N/A",
                name = "Group_${prefix ?: "N/A"}",
                value = rows.firstOrNull()?.value ?: "",
                packageType = rows.firstOrNull()?.packageType ?: "",
                quantity = rows.sumOf { it.quantity },
                remarks = "",
                rows = rows
            )
        })
    }

    /** Сохраняет BOM документ. */
    private fun saveBom() {
        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() {
                project?.let { proj ->
                    val bomDoc = BomDocument(items = BomRepository.loadItems(bomRows))
                    val updated = proj.copy(bom = bomDoc)
                    projectDir?.let { dir ->
                        ProjectRepository.save(dir.resolve("${proj.info.name}.cfproj"), updated)
                        BomRepository.save(dir, bomDoc)
                        logger.info("BOM saved to project ${proj.info.name}")
                    }
                }
            }
        }

        task.setOnSucceeded {
            logger.info("BOM saved successfully")
            UiUtils.confirmAlert("BOM saved successfully")
        }
        task.setOnFailed {
            UiUtils.errorAlert("Failed to save BOM", task.exception)
        }
        executor.submit(task)
    }

    override fun showWindow() = stage.show()

    override fun openProject(proj: Project) {
        project = proj
        projectDir = proj.info.pathObj

        // Загружаем существующие строки BOM
        bomRows.setAll(BomRepository.loadRows(proj.components))
        updateBomItems()

        // Если BOM пустой, генерируем из компонентов
        if (bomRows.isEmpty()) {
            bomRows.setAll(BomRepository.loadRows(proj.components))
            updateBomItems()
        }
    }

    override fun getStage(): Stage = stage
}

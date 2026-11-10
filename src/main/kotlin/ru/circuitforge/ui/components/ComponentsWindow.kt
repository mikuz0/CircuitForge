package ru.circuitforge.ui.components

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
import java.util.regex.Pattern

/**
 * Полноценное окно редактора компонентов.
 * Содержит ListView для списка, GridPane для формы, TableView для BOM строк.
 */
class ComponentsWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(ComponentsWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "components-io").apply { isDaemon = true }
    }

    // UI элементы
    private val listView = ListView<ComponentSummary>()
    private val tableView = TableView<BomRow>()

    // Поля формы
    private val nameField = TextField()
    private val refPrefixField = TextField()
    private val valueField = TextField()
    private val packageField = TextField()
    private val manufacturerField = TextField()
    private val addButton = Button("Add")
    private val updateButton = Button("Update")
    private val deleteButton = Button("Delete")
    private val clearButton = Button("Clear")

    private var editingId: String? = null
    private var projectDir: Path? = null
    private var project: Project? = null

    // Список компонентов
    private val componentList: ObservableList<ComponentSummary> = FXCollections.observableArrayList()
    private val bomRows: ObservableList<BomRow> = FXCollections.observableArrayList()

    val root: BorderPane = BorderPane().apply {
        // Left panel — список компонентов
        val leftPanel = VBox(10.0, Label("Components"), listView).apply {
            maxWidth = Double.MAX_VALUE
            VBox.setVgrow(listView, Priority.ALWAYS)
            padding = Insets(10.0)
            minWidth = 250.0
        }

        // Center panel — форма компонента
        val centerPanel = createFormPanel()

        // Bottom panel — BOM таблица
        val bottomPanel = VBox(10.0, Label("BOM Preview"), tableView).apply {
            VBox.setVgrow(tableView, Priority.ALWAYS)
            padding = Insets(10.0)
            minWidth = 400.0
        }

        center = BorderPane(centerPanel, null, leftPanel, bottomPanel, null)
    }

    init {
        stage.title = "Components Editor"
        stage.scene = javafx.scene.Scene(root)
        stage.width = 900.0
        stage.height = 600.0
        setupTableView()
        setupEventHandlers()
    }

    /** Создаёт панель формы компонента с GridPane. */
    private fun createFormPanel(): GridPane {
        val grid = GridPane()
        grid.alignment = Pos.TOP_LEFT
        grid.hgap = 10
        grid.vgap = 10
        grid.padding = Insets(10.0)

        grid.add(Label("Name:"), 0, 0)
        grid.add(nameField, 1, 0)
        grid.add(Label("Ref Prefix:"), 0, 1)
        grid.add(refPrefixField, 1, 1)
        grid.add(Label("Value:"), 0, 2)
        grid.add(valueField, 1, 2)
        grid.add(Label("Package:"), 0, 3)
        grid.add(packageField, 1, 3)
        grid.add(Label("Manufacturer:"), 0, 4)
        grid.add(manufacturerField, 1, 4)

        val buttonBar = HBox(10.0, addButton, updateButton, deleteButton, clearButton)
        buttonBar.alignment = Pos.CENTER_LEFT
        grid.add(buttonBar, 0, 5, 2, 1)

        return grid
    }

    /** Настраивает TableView для BOM строк. */
    private fun setupTableView() {
        val refCol = TableColumn<BomRow, String>("Reference")
        refCol.setCellValueFactory { it.value.reference }

        val valCol = TableColumn<BomRow, String>("Value")
        valCol.setCellValueFactory { it.value.value }

        val pkgCol = TableColumn<BomRow, String>("Package")
        pkgCol.setCellValueFactory { it.value.packageType }

        val manCol = TableColumn<BomRow, String>("Manufacturer")
        manCol.setCellValueFactory { it.value.manufacturer }

        tableView.columns.setAll(refCol, valCol, pkgCol, manCol)
        tableView.items = bomRows
    }

    /** Настраивает обработчики событий кнопок. */
    private fun setupEventHandlers() {
        addButton.setOnAction { saveComponent() }
        updateButton.setOnAction { updateComponent() }
        deleteButton.setOnAction { deleteComponent() }
        clearButton.setOnAction { clearForm() }

        listView.selectionModel.selectedItemProperty().addListener { _, _, new ->
            if (new != null) loadComponentIntoForm(new)
        }
    }

    /** Валидирует refPrefix: должен содержать только A-Z0-9_, верхний регистр. */
    private fun validateRefPrefix(prefix: String): Boolean {
        if (prefix.isBlank()) {
            UiUtils.errorAlert("Ref Prefix cannot be blank")
            return false
        }
        val pattern = Pattern.compile("^[A-Z0-9_]+$")
        if (!pattern.matcher(prefix).matches()) {
            UiUtils.errorAlert("Ref Prefix must contain only uppercase letters, digits, and underscores (A-Z0-9_)")
            return false
        }
        return true
    }

    /** Сохраняет новый компонент. */
    private fun saveComponent() {
        val name = nameField.text
        val refPrefix = refPrefixField.text.uppercase()
        val value = valueField.text
        val pkg = packageField.text
        val man = manufacturerField.text

        if (name.isBlank()) {
            UiUtils.errorAlert("Component name cannot be blank")
            return
        }

        if (!validateRefPrefix(refPrefix)) return

        val task = object : javafx.concurrent.Task<Component>() {
            override fun call(): Component {
                val component = Component(
                    name = name,
                    refPrefix = refPrefix,
                    value = value,
                    packageType = pkg,
                    manufacturer = man
                )
                project?.let { proj ->
                    val updated = proj.copy(components = proj.components + component)
                    projectDir?.let { dir ->
                        ProjectRepository.save(dir.resolve("${refPrefix}_$name.cfproj"), component)
                        logger.info("Component saved: ${refPrefix}_$name.cfproj")
                    }
                }
                return component
            }
        }

        task.setOnSucceeded {
            componentList.add(ComponentRepository.toSummary(task.value))
            updateBomPreview()
            clearForm()
        }
        task.setOnFailed {
            UiUtils.errorAlert("Failed to save component", task.exception)
        }
        executor.submit(task)
    }

    /** Обновляет существующий компонент. */
    private fun updateComponent() {
        val id = editingId ?: return
        val name = nameField.text
        val refPrefix = refPrefixField.text.uppercase()

        if (name.isBlank() || refPrefix.isBlank()) {
            UiUtils.errorAlert("Name and Ref Prefix cannot be blank")
            return
        }

        if (!validateRefPrefix(refPrefix)) return

        val task = object : javafx.concurrent.Task<Component>() {
            override fun call(): Component {
                val component = Component(
                    id = id,
                    name = name,
                    refPrefix = refPrefix,
                    value = valueField.text,
                    packageType = packageField.text,
                    manufacturer = manufacturerField.text
                )
                project?.let { proj ->
                    val updated = proj.copy(
                        components = proj.components.map { if (it.id == id) component else it }
                    )
                    projectDir?.let { dir ->
                        ProjectRepository.save(dir.resolve("${refPrefix}_$name.cfproj"), component)
                        logger.info("Component updated: ${refPrefix}_$name.cfproj")
                    }
                }
                return component
            }
        }

        task.setOnSucceeded {
            val summary = ComponentRepository.toSummary(task.value)
            val index = componentList.indexOfFirst { it.id == id }
            if (index >= 0) componentList[index] = summary
            updateBomPreview()
            clearForm()
        }
        task.setOnFailed {
            UiUtils.errorAlert("Failed to update component", task.exception)
        }
        executor.submit(task)
    }

    /** Удаляет выбранный компонент. */
    private fun deleteComponent() {
        val selected = listView.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert("Delete component '${selected.name}'?")) return

        val task = object : javafx.concurrent.Task<Boolean>() {
            override fun call(): Boolean {
                project?.let { proj ->
                    val updated = proj.copy(components = proj.components.filter { it.id != selected.id })
                    projectDir?.toFile()?.parentFile?.let { dir ->
                        Path.of(dir.toString, "${selected.refPrefix}_${selected.name}.cfproj").toFile().delete()
                    }
                }
                return true
            }
        }

        task.setOnSucceeded {
            componentList.remove(selected)
            updateBomPreview()
            clearForm()
        }
        task.setOnFailed {
            UiUtils.errorAlert("Failed to delete component", task.exception)
        }
        executor.submit(task)
    }

    /** Загружает данные компонента в форму. */
    private fun loadComponentIntoForm(summary: ComponentSummary) {
        editingId = summary.id
        nameField.text = summary.name
        refPrefixField.text = summary.refPrefix
        valueField.text = summary.value
        packageField.text = summary.packageType
        // Manufacturer не хранится в summary, оставляем пустым
    }

    /** Очищает форму и сбрасывает состояние редактирования. */
    fun clearForm() {
        nameField.clear()
        refPrefixField.clear()
        valueField.clear()
        packageField.clear()
        manufacturerField.clear()
        editingId = null
    }

    /** Обновляет предпросмотр BOM из текущего списка компонентов. */
    private fun updateBomPreview() {
        project?.let { proj ->
            bomRows.setAll(BomRepository.loadRows(proj.components))
        }
    }

    /** Устанавливает проект для редактора. */
    fun setProject(proj: Project, dir: Path) {
        project = proj
        projectDir = dir
        componentList.setAll(proj.components.map { ComponentRepository.toSummary(it) })
        listView.items = componentList
        updateBomPreview()
    }

    override fun showWindow() = stage.show()
    override fun openProject(proj: Project) {
        project = proj
        projectDir = proj.info.pathObj
        componentList.setAll(proj.components.map { ComponentRepository.toSummary(it) })
        listView.items = componentList
        updateBomPreview()
    }
    override fun getStage(): Stage = stage
}

package ru.circuitforge.ui.components

import javafx.collections.FXCollections
import javafx.collections.transformation.SortedList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

private const val CFCOMP_EXT = ".cfcomp"

/**
 * Полноформатное окно редактора компонентов.
 * Содержит таблицу компонентов и таблицу портов внутри компонента.
 * Поддерживает CRUD для компонентов и портов, dirty tracking.
 */
class ComponentsWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(ComponentsWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "components-io").apply { isDaemon = true }
    }

    private var projectPath: Path? = null

    // Данные
    private val componentList = FXCollections.observableArrayList<ComponentSummary>()
    private val portList = FXCollections.observableArrayList<Port>()
    private var originalPortList = FXCollections.observableArrayList<Port>()

    // UI элементы — таблица компонентов
    private val componentTable = TableView<ComponentSummary>()

    // UI элементы — таблица портов
    private val portTable = TableView<Port>()

    // Поля формы компонента
    private val componentNameField = TextField()
    private val componentDescField = TextField()
    private val refPrefixField = TextField()
    private val model3dField = TextField()

    // Кнопки компонентов
    private val componentSaveButton = Button("Save")
    private val componentDeleteButton = Button("Delete")
    private val componentRevertButton = Button("Revert")

    // Поля формы порта (для добавления нового порта)
    private val portPinNumberField = TextField()
    private val portNameField = TextField()
    private val portDescField = TextField()

    // Кнопки портов
    private val portAddButton = Button("Add Port")
    private val portDeleteButton = Button("Delete Port")

    private var editingComponentSlug: String? = null

    // Dirty tracking
    private var isDirty = false

    init {
        stage.title = "Components Editor"
        setupComponentTable()
        setupPortTable()
        setupEventHandlers()
        stage.scene = Scene(createRoot(), 1000.0, 700.0)
    }

    /** Создаёт корневой контейнер. */
    private fun createRoot(): BorderPane {
        val root = BorderPane()

        // Левая панель — таблица компонентов
        val leftPanel = VBox(10.0,
            Label("Components:"),
            componentTable
        ).apply {
            VBox.setVgrow(componentTable, Priority.ALWAYS)
            padding = Insets(10.0)
            minWidth = 400.0
            style = "-fx-background-color: white; -fx-border-color: #ddd; -fx-border-width: 0 1 0 0;"
        }

        // Правая верхняя панель — форма компонента
        val formPanel = createComponentFormPanel()

        // Правая нижняя панель — таблица портов
        val portsPanel = VBox(10.0,
            Label("Ports:"),
            portTable,
            createPortFormPanel()
        ).apply {
            VBox.setVgrow(portTable, Priority.ALWAYS)
            padding = Insets(10.0)
        }

        root.center = HBox(0.0, leftPanel, formPanel, portsPanel).apply {
            HBox.setHgrow(componentTable, Priority.ALWAYS)
            HBox.setHgrow(portTable, Priority.ALWAYS)
        }

        return root
    }

    /** Создаёт панель формы компонента. */
    private fun createComponentFormPanel(): VBox {
        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 8.0
        grid.padding = Insets(10.0)

        GridPane.setConstraints(Label("Name:"), 0, 0)
        GridPane.setConstraints(componentNameField, 1, 0)
        GridPane.setConstraints(Label("Description:"), 0, 1)
        GridPane.setConstraints(componentDescField, 1, 1)
        GridPane.setConstraints(Label("Ref Prefix:"), 0, 2)
        GridPane.setConstraints(refPrefixField, 1, 2)
        GridPane.setConstraints(Label("Model 3D:"), 0, 3)
        GridPane.setConstraints(model3dField, 1, 3)

        grid.children.addAll(
            Label("Name:"), componentNameField,
            Label("Description:"), componentDescField,
            Label("Ref Prefix:"), refPrefixField,
            Label("Model 3D:"), model3dField
        )

        val buttonBar = HBox(10.0, componentSaveButton, componentDeleteButton, componentRevertButton)
        buttonBar.alignment = Pos.CENTER_LEFT

        return VBox(10.0, Label("Component Properties:"), grid, buttonBar).apply {
            padding = Insets(10.0)
            minWidth = 300.0
        }
    }

    /** Создаёт панель формы порта. */
    private fun createPortFormPanel(): VBox {
        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 5.0
        grid.padding = Insets(5.0)

        GridPane.setConstraints(Label("Pin#:"), 0, 0)
        GridPane.setConstraints(portPinNumberField, 1, 0)
        GridPane.setConstraints(Label("Name:"), 0, 1)
        GridPane.setConstraints(portNameField, 1, 1)
        GridPane.setConstraints(Label("Description:"), 0, 2)
        GridPane.setConstraints(portDescField, 1, 2)

        grid.children.addAll(
            Label("Pin#:"), portPinNumberField,
            Label("Name:"), portNameField,
            Label("Description:"), portDescField
        )

        val buttonBar = HBox(10.0, portAddButton, portDeleteButton)
        buttonBar.alignment = Pos.CENTER_LEFT

        return VBox(10.0, Label("Port Properties:"), grid, buttonBar).apply {
            padding = Insets(5.0)
        }
    }

    /** Настраивает таблицу компонентов. */
    private fun setupComponentTable() {
        val nameCol = TableColumn<ComponentSummary, String>("Name")
        nameCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.name) }
        nameCol.prefWidth = 150.0

        val descCol = TableColumn<ComponentSummary, String>("Description")
        descCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.description) }
        descCol.prefWidth = 200.0

        val refPrefixCol = TableColumn<ComponentSummary, String>("RefPrefix")
        refPrefixCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.refPrefix) }
        refPrefixCol.prefWidth = 80.0

        val portsCol = TableColumn<ComponentSummary, String>("Ports")
        portsCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.portsCount.toString()) }
        portsCol.prefWidth = 60.0

        componentTable.columns.setAll(nameCol, descCol, refPrefixCol, portsCol)
        componentTable.items = SortedList(componentList)

        componentTable.selectionModel.selectedItemProperty().addListener { _, _, new ->
            if (new != null) loadComponentIntoForm(new)
        }
    }

    /** Настраивает таблицу портов. */
    private fun setupPortTable() {
        val numberCol = TableColumn<Port, String>("#")
        numberCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.number.toString()) }
        numberCol.prefWidth = 50.0

        val pinCol = TableColumn<Port, String>("Pin#")
        pinCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.pinNumber) }
        pinCol.prefWidth = 100.0

        val nameCol = TableColumn<Port, String>("Name")
        nameCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.name) }
        nameCol.prefWidth = 150.0

        val descCol = TableColumn<Port, String>("Description")
        descCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.description) }
        descCol.prefWidth = 200.0

        portTable.columns.setAll(numberCol, pinCol, nameCol, descCol)
        portTable.items = portList
    }

    /** Настраивает обработчики событий. */
    private fun setupEventHandlers() {
        componentSaveButton.setOnAction { saveComponent() }
        componentDeleteButton.setOnAction { deleteComponent() }
        componentRevertButton.setOnAction { revertComponent() }

        portAddButton.setOnAction { addPortRow() }
        portDeleteButton.setOnAction { deletePort() }

        // Слушаем изменения в полях формы для dirty tracking
        val changeListeners = listOf(
            componentNameField.textProperty(),
            componentDescField.textProperty(),
            refPrefixField.textProperty(),
            model3dField.textProperty()
        )
        changeListeners.forEach { prop ->
            prop.addListener { _, _, _ -> markDirty() }
        }
    }

    /** Валидирует refPrefix. */
    private fun validateRefPrefix(prefix: String): Boolean {
        if (prefix.isBlank()) {
            UiUtils.errorAlert("Error", "Ref Prefix cannot be blank")
            return false
        }
        val pattern = Regex("^[A-Z0-9_]+$")
        if (!prefix.matches(pattern)) {
            UiUtils.errorAlert("Error", "Ref Prefix must contain only uppercase letters, digits, and underscores (A-Z0-9_)")
            return false
        }
        return true
    }

    /** Сохраняет компонент. */
    private fun saveComponent() {
        val name = componentNameField.text.trim()
        val description = componentDescField.text.trim()
        val refPrefix = refPrefixField.text.trim().uppercase()
        val model3d = model3dField.text.trim().takeIf { it.isNotEmpty() }

        if (name.isBlank()) {
            UiUtils.errorAlert("Error", "Component name cannot be blank")
            return
        }
        if (!validateRefPrefix(refPrefix)) return

        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() {
                projectPath?.let { dir ->
                    val originalSlug = editingComponentSlug
                    if (originalSlug != null) {
                        // Обновление
                        ComponentRepository.saveComponent(
                            dir, originalSlug, name, description, refPrefix, portList.toList()
                        )
                        logger.info("Component updated: $name")
                    } else {
                        // Создание
                        ComponentRepository.createComponent(
                            dir, name, description, refPrefix, portList.toList()
                        )
                        logger.info("Component created: $name")
                    }
                }
            }
        }

        task.setOnSucceeded {
            // Перезагружаем список компонентов
            projectPath?.let { dir ->
                componentList.setAll(ComponentRepository.listComponents(dir))
            }
            clearComponentForm()
            portList.clear()
            originalPortList.clear()
            isDirty = false
            logger.info("Component saved successfully")
        }
        task.setOnFailed {
            UiUtils.errorAlert("Failed to save component", task.exception?.message ?: "Unknown error")
            logger.error("Failed to save component", task.exception)
        }
        executor.submit(task)
    }

    /** Удаляет выбранный компонент. */
    private fun deleteComponent() {
        val selected = componentTable.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert("Confirm", "Delete component?", "Delete '${selected.name}'?")) return

        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() {
                projectPath?.let { dir ->
                    ComponentRepository.deleteComponent(dir, selected.slug)
                }
            }
        }

        task.setOnSucceeded {
            componentList.remove(selected)
            portList.clear()
            originalPortList.clear()
            clearComponentForm()
            logger.info("Component deleted: ${selected.name}")
        }
        task.setOnFailed {
            UiUtils.errorAlert("Failed to delete component", task.exception?.message ?: "Unknown error")
            logger.error("Failed to delete component", task.exception)
        }
        executor.submit(task)
    }

    /** Отменяет изменения текущего компонента. */
    private fun revertComponent() {
        val selected = componentTable.selectionModel.selectedItem ?: return
        loadComponentIntoForm(selected)
        logger.info("Component reverted: ${selected.name}")
    }

    /** Очищает форму компонента. */
    private fun clearComponentForm() {
        componentNameField.clear()
        componentDescField.clear()
        refPrefixField.clear()
        model3dField.clear()
        editingComponentSlug = null
    }

    /** Загружает компонент в форму. */
    private fun loadComponentIntoForm(summary: ComponentSummary) {
        editingComponentSlug = summary.slug
        componentNameField.text = summary.name
        componentDescField.text = summary.description
        refPrefixField.text = summary.refPrefix
        model3dField.clear()

        // Загружаем полный компонент с портами из файла
        projectPath?.let { dir ->
            val compPath = dir.resolve("components").resolve("${summary.slug}$CFCOMP_EXT")
            logger.info("Loading component: ${summary.slug} from $compPath")

            if (Files.exists(compPath)) {
                try {
                    val component = ComponentRepository.load(compPath)
                    portList.setAll(component.ports)
                    originalPortList.setAll(component.ports)
                    isDirty = false
                    logger.info("Component loaded, ports count: ${component.ports.size}")
                } catch (e: Exception) {
                    logger.error("Failed to load component from $compPath", e)
                    portList.clear()
                    originalPortList.clear()
                }
            } else {
                logger.warn("Component file not found: $compPath")
                portList.clear()
                originalPortList.clear()
            }
        }
    }

    /** Добавляет строку порта с number = max+1. */
    private fun addPortRow() {
        val nextNumber = portList.maxOfOrNull { it.number } ?: 0
        portList.add(Port(
            number = nextNumber + 1,
            pinNumber = portPinNumberField.text.trim(),
            name = portNameField.text.trim(),
            description = portDescField.text.trim()
        ))
        portPinNumberField.clear()
        portNameField.clear()
        portDescField.clear()
        markDirty()
    }

    /** Удаляет выбранный порт. */
    private fun deletePort() {
        val selected = portTable.selectionModel.selectedItem ?: return
        portList.remove(selected)
        markDirty()
    }

    /** Помечает состояние как изменённое. */
    private fun markDirty() {
        isDirty = true
    }

    /** Проверяет, есть ли несохранённые изменения. */
    private fun isDirty(): Boolean = isDirty

    override fun showWindow() = stage.show()

    override fun openProject(projectPath: Path) {
        this.projectPath = projectPath

        // Загружаем компоненты
        val componentsDir = projectPath.resolve("components")
        if (Files.exists(componentsDir)) {
            componentList.setAll(ComponentRepository.listComponents(projectPath))
            logger.info("Components loaded: ${componentList.size}")
        }
    }

    override fun getStage(): Stage = stage
}

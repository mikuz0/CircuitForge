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
import ru.circuitforge.i18n.I18n
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
    private val componentSaveButton = Button(I18n.tr("components.btn.save"))
    private val componentDeleteButton = Button(I18n.tr("components.btn.delete"))
    private val componentRevertButton = Button(I18n.tr("components.btn.revert"))

    // Поля формы порта (для добавления нового порта)
    private val portPinNumberField = TextField()
    private val portNameField = TextField()
    private val portDescField = TextField()

    // Кнопки портов
    private val portAddButton = Button(I18n.tr("components.btn.add.port"))
    private val portDeleteButton = Button(I18n.tr("components.btn.delete.port"))

    private var editingComponentSlug: String? = null

    // Dirty tracking
    private var isDirty = false

    init {
        stage.title = I18n.tr("components.window.title")
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
            Label(I18n.tr("components.label.components")),
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

        // Создаём лейблы один раз и настраиваем их перед добавлением в grid
        val nameLabel = Label(I18n.tr("components.label.name"))
        val descLabel = Label(I18n.tr("components.label.description"))
        val refPrefixLabel = Label(I18n.tr("components.label.ref.prefix"))
        val model3dLabel = Label(I18n.tr("components.label.model.3d"))

        GridPane.setConstraints(nameLabel, 0, 0)
        GridPane.setConstraints(componentNameField, 1, 0)
        GridPane.setConstraints(descLabel, 0, 1)
        GridPane.setConstraints(componentDescField, 1, 1)
        GridPane.setConstraints(refPrefixLabel, 0, 2)
        GridPane.setConstraints(refPrefixField, 1, 2)
        GridPane.setConstraints(model3dLabel, 0, 3)
        GridPane.setConstraints(model3dField, 1, 3)

        grid.children.setAll(
            nameLabel, componentNameField,
            descLabel, componentDescField,
            refPrefixLabel, refPrefixField,
            model3dLabel, model3dField
        )

        val buttonBar = HBox(10.0, componentSaveButton, componentDeleteButton, componentRevertButton)
        buttonBar.alignment = Pos.CENTER_LEFT

        return VBox(10.0, Label(I18n.tr("components.label.properties")), grid, buttonBar).apply {
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

        // Создаём лейблы один раз и настраиваем их перед добавлением в grid
        val pinLabel = Label(I18n.tr("components.label.pin"))
        val portNameLabel = Label(I18n.tr("components.label.name"))
        val portDescLabel = Label(I18n.tr("components.label.description"))

        GridPane.setConstraints(pinLabel, 0, 0)
        GridPane.setConstraints(portPinNumberField, 1, 0)
        GridPane.setConstraints(portNameLabel, 0, 1)
        GridPane.setConstraints(portNameField, 1, 1)
        GridPane.setConstraints(portDescLabel, 0, 2)
        GridPane.setConstraints(portDescField, 1, 2)

        grid.children.setAll(
            pinLabel, portPinNumberField,
            portNameLabel, portNameField,
            portDescLabel, portDescField
        )

        val buttonBar = HBox(10.0, portAddButton, portDeleteButton)
        buttonBar.alignment = Pos.CENTER_LEFT

        return VBox(10.0, Label(I18n.tr("components.label.port.properties")), grid, buttonBar).apply {
            padding = Insets(5.0)
        }
    }

    /** Настраивает таблицу компонентов. */
    private fun setupComponentTable() {
        val nameCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.name"))
        nameCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.name) }
        nameCol.prefWidth = 150.0

        val descCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.description"))
        descCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.description) }
        descCol.prefWidth = 200.0

        val refPrefixCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.ref.prefix"))
        refPrefixCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.refPrefix) }
        refPrefixCol.prefWidth = 80.0

        val portsCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.ports"))
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
        val numberCol = TableColumn<Port, String>(I18n.tr("components.col.number"))
        numberCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.number.toString()) }
        numberCol.prefWidth = 50.0

        val pinCol = TableColumn<Port, String>(I18n.tr("components.col.pin"))
        pinCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.pinNumber) }
        pinCol.prefWidth = 100.0

        val nameCol = TableColumn<Port, String>(I18n.tr("components.col.port.name"))
        nameCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.name) }
        nameCol.prefWidth = 150.0

        val descCol = TableColumn<Port, String>(I18n.tr("components.col.port.description"))
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
            UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.ref.prefix.blank"))
            return false
        }
        val pattern = Regex("^[A-Z0-9_]+$")
        if (!prefix.matches(pattern)) {
            UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.ref.prefix.pattern"))
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
            UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.name.blank"))
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
            UiUtils.errorAlert(I18n.tr("components.alert.save.failed"), task.exception?.message ?: I18n.tr("common.alert.unknown.error"))
            logger.error("Failed to save component", task.exception)
        }
        executor.submit(task)
    }

    /** Удаляет выбранный компонент. */
    private fun deleteComponent() {
        val selected = componentTable.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert(I18n.tr("common.alert.confirm"), I18n.tr("components.alert.delete.confirm"), I18n.tr("components.alert.delete.confirm.text", selected.name))) return

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
            UiUtils.errorAlert(I18n.tr("components.alert.delete.failed"), task.exception?.message ?: I18n.tr("common.alert.unknown.error"))
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

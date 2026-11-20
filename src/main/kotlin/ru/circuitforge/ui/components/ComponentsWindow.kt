package ru.circuitforge.ui.components

import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.transformation.SortedList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.Callback
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

private const val CFCOMP_EXT = ".cfcomp"

class ComponentsWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(ComponentsWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "components-io").apply { isDaemon = true }
    }
    private var projectPaths: ProjectPaths? = null

    private val componentList = FXCollections.observableArrayList<ComponentSummary>()
    private val portList = FXCollections.observableArrayList<Port>()
    private var originalPortList = FXCollections.observableArrayList<Port>()
    private val mainPropertyList = FXCollections.observableArrayList<Property>()
    private var originalMainPropertyList = FXCollections.observableArrayList<Property>()
    private val extraPropertyList = FXCollections.observableArrayList<Property>()
    private var originalExtraPropertyList = FXCollections.observableArrayList<Property>()

    private val componentTable = TableView<ComponentSummary>()
    private val portTable = TableView<Port>()
    private val mainPropertyTable = TableView<Property>()
    private val extraPropertyTable = TableView<Property>()

    // Поля формы компонента
    private val componentNameField = TextField()
    private val componentDescField = TextField()
    private val refPrefixField = TextField()
    private val model3dField = TextField()

    // Кнопки компонентов
    private val componentSaveButton = Button(I18n.tr("components.btn.save"))
    private val componentDeleteButton = Button(I18n.tr("components.btn.delete"))
    private val componentRevertButton = Button(I18n.tr("components.btn.revert"))
    private val componentDuplicateButton = Button(I18n.tr("components.btn.duplicate"))

    // Поля формы порта
    private val portPinNumberField = TextField()
    private val portNameField = TextField()
    private val portDescField = TextField()
    private val portAddButton = Button(I18n.tr("components.btn.add.port"))
    private val portDeleteButton = Button(I18n.tr("components.btn.delete.port"))

    // Поля формы основных свойств
    private val mainPropKeyField = TextField()
    private val mainPropValueField = TextField()
    private val mainPropUnitField = TextField()
    private val mainPropAddButton = Button(I18n.tr("components.btn.add.property"))
    private val mainPropDeleteButton = Button(I18n.tr("components.btn.delete.property"))

    // Поля формы дополнительных свойств
    private val extraPropKeyField = TextField()
    private val extraPropValueField = TextField()
    private val extraPropUnitField = TextField()
    private val extraPropAddButton = Button(I18n.tr("components.btn.add.property"))
    private val extraPropDeleteButton = Button(I18n.tr("components.btn.delete.property"))

    private var editingComponentSlug: String? = null
    private var isDirty = false

    // Сохраняем ссылку на TabPane, чтобы не создавать дважды
    private lateinit var tabPane: TabPane

    init {
        stage.title = I18n.tr("components.window.title")
        setupComponentTable()
        setupPortTable()
        setupPropertyTable(mainPropertyTable, mainPropertyList)
        setupPropertyTable(extraPropertyTable, extraPropertyList)
        setupEventHandlers()
        stage.scene = Scene(createRoot(), 1100.0, 750.0)
    }

    private fun createRoot(): BorderPane {
        val root = BorderPane()
        val leftPanel = VBox(10.0, Label(I18n.tr("components.label.components")), componentTable).apply {
            VBox.setVgrow(componentTable, Priority.ALWAYS)
            padding = Insets(10.0)
            minWidth = 350.0
            style = "-fx-background-color: white; -fx-border-color: #ddd; -fx-border-width: 0 1 0 0;"
        }
        tabPane = createTabPane()
        val rightPanel = VBox(10.0, createComponentFormPanel(), tabPane).apply {
            padding = Insets(10.0)
            VBox.setVgrow(tabPane, Priority.ALWAYS)
        }
        root.center = HBox(0.0, leftPanel, rightPanel).apply {
            HBox.setHgrow(rightPanel, Priority.ALWAYS)
        }
        return root
    }

    private fun createTabPane(): TabPane {
        val portsTab = Tab(I18n.tr("components.tab.ports")).apply {
            content = VBox(10.0,
                Label(I18n.tr("components.label.port.properties")),
                portTable.apply {
                    prefHeight = 200.0
                    minHeight = 100.0
                },
                createPortFormPanel()
            ).apply {
                VBox.setVgrow(portTable, Priority.ALWAYS)
                padding = Insets(5.0)
            }
        }

        val mainPropsTab = Tab(I18n.tr("components.tab.main.properties")).apply {
            content = createPropertyTabContent(mainPropertyTable.apply {
                prefHeight = 200.0
                minHeight = 100.0
            }, mainPropertyList,
                mainPropKeyField, mainPropValueField, mainPropUnitField,
                mainPropAddButton, mainPropDeleteButton)
        }

        val extraPropsTab = Tab(I18n.tr("components.tab.extra.properties")).apply {
            content = createPropertyTabContent(extraPropertyTable.apply {
                prefHeight = 200.0
                minHeight = 100.0
            }, extraPropertyList,
                extraPropKeyField, extraPropValueField, extraPropUnitField,
                extraPropAddButton, extraPropDeleteButton)
        }

        val tp = TabPane(portsTab, mainPropsTab, extraPropsTab)
        tp.tabClosingPolicyProperty().set(TabPane.TabClosingPolicy.UNAVAILABLE)
        return tp
    }

    private fun createPropertyTabContent(
        table: TableView<Property>, list: ObservableList<Property>,
        keyField: TextField, valueField: TextField, unitField: TextField,
        addButton: Button, deleteButton: Button
    ): VBox {
        val grid = GridPane()
        grid.hgap = 10.0; grid.vgap = 5.0; grid.padding = Insets(5.0)
        val keyLabel = Label(I18n.tr("components.label.property.key"))
        val valueLabel = Label(I18n.tr("components.label.property.value"))
        val unitLabel = Label(I18n.tr("components.label.property.unit"))
        GridPane.setConstraints(keyLabel, 0, 0); GridPane.setConstraints(keyField, 1, 0)
        GridPane.setConstraints(valueLabel, 0, 1); GridPane.setConstraints(valueField, 1, 1)
        GridPane.setConstraints(unitLabel, 0, 2); GridPane.setConstraints(unitField, 1, 2)
        grid.children.setAll(keyLabel, keyField, valueLabel, valueField, unitLabel, unitField)
        val buttonBar = HBox(10.0, addButton, deleteButton).apply { alignment = Pos.CENTER_LEFT }
        return VBox(10.0, Label(I18n.tr("components.label.properties")),
            table,
            grid, buttonBar).apply {
            VBox.setVgrow(table, Priority.ALWAYS)
            padding = Insets(5.0)
        }
    }

    private fun createComponentFormPanel(): VBox {
        val grid = GridPane()
        grid.hgap = 10.0; grid.vgap = 8.0; grid.padding = Insets(10.0)
        val nameLabel = Label(I18n.tr("components.label.name"))
        val descLabel = Label(I18n.tr("components.label.description"))
        val refPrefixLabel = Label(I18n.tr("components.label.ref.prefix"))
        val model3dLabel = Label(I18n.tr("components.label.model.3d"))
        GridPane.setConstraints(nameLabel, 0, 0); GridPane.setConstraints(componentNameField, 1, 0)
        GridPane.setConstraints(descLabel, 0, 1); GridPane.setConstraints(componentDescField, 1, 1)
        GridPane.setConstraints(refPrefixLabel, 0, 2); GridPane.setConstraints(refPrefixField, 1, 2)
        GridPane.setConstraints(model3dLabel, 0, 3); GridPane.setConstraints(model3dField, 1, 3)
        grid.children.setAll(nameLabel, componentNameField, descLabel, componentDescField, refPrefixLabel, refPrefixField, model3dLabel, model3dField)
        val buttonBar = HBox(10.0, componentSaveButton, componentDuplicateButton, componentDeleteButton, componentRevertButton).apply { alignment = Pos.CENTER_LEFT }
        return VBox(10.0, Label(I18n.tr("components.label.properties")), grid, buttonBar).apply {
            padding = Insets(10.0); minWidth = 300.0
        }
    }

    private fun createPortFormPanel(): VBox {
        val grid = GridPane()
        grid.hgap = 10.0; grid.vgap = 5.0; grid.padding = Insets(5.0)
        val pinLabel = Label(I18n.tr("components.label.pin"))
        val portNameLabel = Label(I18n.tr("components.label.name"))
        val portDescLabel = Label(I18n.tr("components.label.description"))
        GridPane.setConstraints(pinLabel, 0, 0); GridPane.setConstraints(portPinNumberField, 1, 0)
        GridPane.setConstraints(portNameLabel, 0, 1); GridPane.setConstraints(portNameField, 1, 1)
        GridPane.setConstraints(portDescLabel, 0, 2); GridPane.setConstraints(portDescField, 1, 2)
        grid.children.setAll(pinLabel, portPinNumberField, portNameLabel, portNameField, portDescLabel, portDescField)
        val buttonBar = HBox(10.0, portAddButton, portDeleteButton).apply { alignment = Pos.CENTER_LEFT }
        return VBox(10.0, Label(I18n.tr("components.label.port.properties")), grid, buttonBar).apply { padding = Insets(5.0) }
    }

    private fun setupComponentTable() {
        val nameCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.name")).apply {
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.name) }; prefWidth = 120.0
        }
        val descCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.description")).apply {
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.description) }; prefWidth = 100.0
        }
        val refPrefixCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.ref.prefix")).apply {
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.refPrefix) }; prefWidth = 70.0
        }
        val portsCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.ports")).apply {
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.portsCount.toString()) }; prefWidth = 50.0
        }
        val mainPropsCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.main.properties")).apply {
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.mainPropertiesCount.toString()) }; prefWidth = 70.0
        }
        val extraPropsCol = TableColumn<ComponentSummary, String>(I18n.tr("components.col.extra.properties")).apply {
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.extraPropertiesCount.toString()) }; prefWidth = 70.0
        }
        componentTable.columns.setAll(nameCol, descCol, refPrefixCol, portsCol, mainPropsCol, extraPropsCol)
        componentTable.items = SortedList(componentList)
        componentTable.selectionModel.selectedItemProperty().addListener { _, _, new -> if (new != null) loadComponentIntoForm(new) }
    }

    private fun setupPortTable() {
        portTable.isEditable = true
        val numberCol = TableColumn<Port, String>(I18n.tr("components.col.number")).apply {
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.number.toString()) }
            prefWidth = 50.0; isEditable = false
        }
        val pinCol = TableColumn<Port, String>(I18n.tr("components.col.pin")).apply {
            isEditable = true; prefWidth = 100.0
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.pinNumber) }
            cellFactory = Callback { _: TableColumn<Port, String> ->
                object : TableCell<Port, String>() {
                    private val tf = TextField()
                    init {
                        tf.setOnAction { commitEdit(tf.text) }
                        tf.focusedProperty().addListener { _, _, focused ->
                            if (!focused) commitEdit(tf.text)
                        }
                    }
                    override fun startEdit() { super.startEdit(); tf.text = item ?: ""; graphic = tf; text = null; tf.requestFocus(); tf.selectAll() }
                    override fun cancelEdit() { super.cancelEdit(); text = item; graphic = null }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; graphic = null }
                        else if (isEditing) { tf.text = item; graphic = tf; text = null }
                        else { text = item; graphic = null }
                    }
                }
            }
            setOnEditCommit { event ->
                val port = event.rowValue
                val newPin = event.newValue?.trim() ?: ""
                if (newPin.isBlank()) {
                    UiUtils.errorAlert(I18n.tr("common.alert.error"), "Номер пина не может быть пустым")
                    portTable.refresh()
                    return@setOnEditCommit
                }
                val idx = portList.indexOf(port)
                if (idx >= 0) { portList[idx] = port.copy(pinNumber = newPin); markDirty() }
            }
        }
        val nameCol = TableColumn<Port, String>(I18n.tr("components.col.port.name")).apply {
            isEditable = true; prefWidth = 150.0
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.name) }
            cellFactory = Callback { _: TableColumn<Port, String> ->
                object : TableCell<Port, String>() {
                    private val tf = TextField()
                    init {
                        tf.setOnAction { commitEdit(tf.text) }
                        tf.focusedProperty().addListener { _, _, focused ->
                            if (!focused) commitEdit(tf.text)
                        }
                    }
                    override fun startEdit() { super.startEdit(); tf.text = item ?: ""; graphic = tf; text = null; tf.requestFocus(); tf.selectAll() }
                    override fun cancelEdit() { super.cancelEdit(); text = item; graphic = null }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; graphic = null }
                        else if (isEditing) { tf.text = item; graphic = tf; text = null }
                        else { text = item; graphic = null }
                    }
                }
            }
            setOnEditCommit { event ->
                val port = event.rowValue
                val newName = event.newValue?.trim() ?: ""
                val idx = portList.indexOf(port)
                if (idx >= 0) { portList[idx] = port.copy(name = newName); markDirty() }
            }
        }
        val descCol = TableColumn<Port, String>(I18n.tr("components.col.port.description")).apply {
            isEditable = true; prefWidth = 200.0
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.description) }
            cellFactory = Callback { _: TableColumn<Port, String> ->
                object : TableCell<Port, String>() {
                    private val tf = TextField()
                    init {
                        tf.setOnAction { commitEdit(tf.text) }
                        tf.focusedProperty().addListener { _, _, focused ->
                            if (!focused) commitEdit(tf.text)
                        }
                    }
                    override fun startEdit() { super.startEdit(); tf.text = item ?: ""; graphic = tf; text = null; tf.requestFocus(); tf.selectAll() }
                    override fun cancelEdit() { super.cancelEdit(); text = item; graphic = null }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; graphic = null }
                        else if (isEditing) { tf.text = item; graphic = tf; text = null }
                        else { text = item; graphic = null }
                    }
                }
            }
            setOnEditCommit { event ->
                val port = event.rowValue
                val newDesc = event.newValue?.trim() ?: ""
                val idx = portList.indexOf(port)
                if (idx >= 0) { portList[idx] = port.copy(description = newDesc); markDirty() }
            }
        }
        portTable.columns.setAll(numberCol, pinCol, nameCol, descCol)
        portTable.items = portList
    }

    private fun setupPropertyTable(table: TableView<Property>, list: ObservableList<Property>) {
        table.isEditable = true
        val keyCol = TableColumn<Property, String>(I18n.tr("components.label.property.key")).apply {
            isEditable = true; prefWidth = 150.0
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.key) }
            cellFactory = Callback { _: TableColumn<Property, String> ->
                object : TableCell<Property, String>() {
                    private val tf = TextField()
                    init {
                        tf.setOnAction { commitEdit(tf.text) }
                        tf.focusedProperty().addListener { _, _, focused ->
                            if (!focused) commitEdit(tf.text)
                        }
                    }
                    override fun startEdit() { super.startEdit(); tf.text = item ?: ""; graphic = tf; text = null; tf.requestFocus(); tf.selectAll() }
                    override fun cancelEdit() { super.cancelEdit(); text = item; graphic = null }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; graphic = null }
                        else if (isEditing) { tf.text = item; graphic = tf; text = null }
                        else { text = item; graphic = null }
                    }
                }
            }
            setOnEditCommit { event ->
                val prop = event.rowValue
                val newKey = event.newValue?.trim() ?: ""
                if (newKey.isBlank()) {
                    UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.property.key.blank"))
                    table.refresh()
                    return@setOnEditCommit
                }
                val idx = list.indexOf(prop)
                if (idx >= 0) {
                    val dupIdx = list.indexOfFirst { p -> p.key == newKey && p !== prop }
                    if (dupIdx >= 0) {
                        UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.property.key.duplicate", newKey))
                        table.refresh()
                        return@setOnEditCommit
                    }
                    list[idx] = prop.copy(key = newKey); markDirty()
                }
            }
        }
        val valueCol = TableColumn<Property, String>(I18n.tr("components.label.property.value")).apply {
            isEditable = true; prefWidth = 200.0
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.value) }
            cellFactory = Callback { _: TableColumn<Property, String> ->
                object : TableCell<Property, String>() {
                    private val tf = TextField()
                    init {
                        tf.setOnAction { commitEdit(tf.text) }
                        tf.focusedProperty().addListener { _, _, focused ->
                            if (!focused) commitEdit(tf.text)
                        }
                    }
                    override fun startEdit() { super.startEdit(); tf.text = item ?: ""; graphic = tf; text = null; tf.requestFocus(); tf.selectAll() }
                    override fun cancelEdit() { super.cancelEdit(); text = item; graphic = null }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; graphic = null }
                        else if (isEditing) { tf.text = item; graphic = tf; text = null }
                        else { text = item; graphic = null }
                    }
                }
            }
            setOnEditCommit { event ->
                val prop = event.rowValue
                val newValue = event.newValue?.trim() ?: ""
                val idx = list.indexOf(prop)
                if (idx >= 0) { list[idx] = prop.copy(value = newValue); markDirty() }
            }
        }
        val unitCol = TableColumn<Property, String>(I18n.tr("components.label.property.unit")).apply {
            isEditable = true; prefWidth = 100.0
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.unit) }
            cellFactory = Callback { _: TableColumn<Property, String> ->
                object : TableCell<Property, String>() {
                    private val tf = TextField()
                    init {
                        tf.setOnAction { commitEdit(tf.text) }
                        tf.focusedProperty().addListener { _, _, focused ->
                            if (!focused) commitEdit(tf.text)
                        }
                    }
                    override fun startEdit() { super.startEdit(); tf.text = item ?: ""; graphic = tf; text = null; tf.requestFocus(); tf.selectAll() }
                    override fun cancelEdit() { super.cancelEdit(); text = item; graphic = null }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; graphic = null }
                        else if (isEditing) { tf.text = item; graphic = tf; text = null }
                        else { text = item; graphic = null }
                    }
                }
            }
            setOnEditCommit { event ->
                val prop = event.rowValue
                val newUnit = event.newValue?.trim() ?: ""
                val idx = list.indexOf(prop)
                if (idx >= 0) { list[idx] = prop.copy(unit = newUnit); markDirty() }
            }
        }
        table.columns.setAll(keyCol, valueCol, unitCol)
        table.items = list
    }

    private fun setupEventHandlers() {
        componentSaveButton.setOnAction { saveComponent() }
        componentDuplicateButton.setOnAction { duplicateComponent() }
        componentDeleteButton.setOnAction { deleteComponent() }
        componentRevertButton.setOnAction { revertComponent() }
        portAddButton.setOnAction { addPortRow() }
        portDeleteButton.setOnAction { deletePort() }
        mainPropAddButton.setOnAction { addMainPropertyRow() }
        mainPropDeleteButton.setOnAction { deleteMainProperty() }
        extraPropAddButton.setOnAction { addExtraPropertyRow() }
        extraPropDeleteButton.setOnAction { deleteExtraProperty() }
        listOf(
            componentNameField.textProperty(), componentDescField.textProperty(),
            refPrefixField.textProperty(), model3dField.textProperty(),
            mainPropKeyField.textProperty(), mainPropValueField.textProperty(), mainPropUnitField.textProperty(),
            extraPropKeyField.textProperty(), extraPropValueField.textProperty(), extraPropUnitField.textProperty()
        ).forEach { it.addListener { _, _, _ -> markDirty() } }
    }

    private fun validateRefPrefix(prefix: String): Boolean {
        if (prefix.isBlank()) { UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.ref.prefix.blank")); return false }
        if (!Regex("^[A-Z0-9_]+$").matches(prefix)) { UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.ref.prefix.pattern")); return false }
        return true
    }

    private fun saveComponent() {
        val name = componentNameField.text.trim()
        val description = componentDescField.text.trim()
        val refPrefix = refPrefixField.text.trim().uppercase()
        val model3d = model3dField.text.trim().takeIf { it.isNotEmpty() }
        if (name.isBlank()) { UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.name.blank")); return }
        if (!validateRefPrefix(refPrefix)) return
        val originalSlug = editingComponentSlug
        val task = object : javafx.concurrent.Task<Pair<String, Boolean>>() {
            override fun call(): Pair<String, Boolean> {
                var isUsedInBom = false; var newSlug = originalSlug
                projectPaths?.let { paths ->
                    val dir = paths.projectDir
                    val bomDoc = BomRepository.load(paths)
                    isUsedInBom = bomDoc.items.any { it.componentSlug == originalSlug }
                    if (originalSlug != null) {
                        newSlug = ComponentRepository.saveComponent(dir, originalSlug, name, description, refPrefix, portList.toList(), mainPropertyList.toList(), extraPropertyList.toList())
                    } else {
                        newSlug = ComponentRepository.createComponent(dir, name, description, refPrefix, portList.toList(), mainPropertyList.toList(), extraPropertyList.toList())
                    }
                }
                return Pair(newSlug ?: "", isUsedInBom)
            }
            override fun succeeded() {
                val (slug, usedInBom) = value; editingComponentSlug = slug
                projectPaths?.let { paths -> componentList.setAll(ComponentRepository.listComponents(paths.projectDir)) }
                clearComponentForm()
                portList.clear(); originalPortList.clear(); mainPropertyList.clear(); originalMainPropertyList.clear()
                extraPropertyList.clear(); originalExtraPropertyList.clear()
                if (usedInBom) {
                    val alert = Alert(Alert.AlertType.WARNING)
                    alert.title = "Внимание"; alert.headerText = "Компонент используется в BOM"
                    alert.contentText = "Изменения сохранены.\nПожалуйста, проверьте перечень элементов (ПЭ3) и обновите связи, если префикс или имя компонента изменились."
                    alert.showAndWait()
                }
                logger.info("Component saved successfully")
            }
            override fun failed() {
                UiUtils.errorAlert(I18n.tr("components.alert.save.failed"), exception?.message ?: I18n.tr("common.alert.unknown.error"))
                logger.error("Failed to save component", exception)
            }
        }
        executor.submit(task)
    }

    private fun duplicateComponent() {
        val slug = editingComponentSlug ?: return
        val task = object : javafx.concurrent.Task<String>() {
            override fun call(): String {
                val paths = projectPaths ?: return ""
                return ComponentRepository.duplicateComponent(paths.projectDir, slug)
            }
        }
        task.setOnSucceeded {
            val newSlug = task.value
            if (newSlug.isNotBlank()) {
                projectPaths?.let { paths -> componentList.setAll(ComponentRepository.listComponents(paths.projectDir)) }
                val copy = componentList.find { it.slug == newSlug }
                if (copy != null) componentTable.selectionModel.select(copy)
                logger.info("Component duplicated: $slug -> $newSlug")
            }
        }
        task.setOnFailed {
            UiUtils.errorAlert(I18n.tr("components.alert.duplicate.failed"), task.exception?.message ?: I18n.tr("common.alert.unknown.error"))
            logger.error("Failed to duplicate component", task.exception)
        }
        executor.submit(task)
    }

    private fun deleteComponent() {
        val selected = componentTable.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert(I18n.tr("common.alert.confirm"), I18n.tr("components.alert.delete.confirm"), I18n.tr("components.alert.delete.confirm.text", selected.name))) return
        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() { projectPaths?.let { paths -> ComponentRepository.deleteComponent(paths.projectDir, selected.slug) } }
        }
        task.setOnSucceeded {
            componentList.remove(selected); portList.clear(); originalPortList.clear()
            mainPropertyList.clear(); originalMainPropertyList.clear()
            extraPropertyList.clear(); originalExtraPropertyList.clear()
            clearComponentForm(); logger.info("Component deleted: ${selected.name}")
        }
        task.setOnFailed { UiUtils.errorAlert(I18n.tr("components.alert.delete.failed"), task.exception?.message ?: I18n.tr("common.alert.unknown.error")); logger.error("Failed to delete component", task.exception) }
        executor.submit(task)
    }

    private fun revertComponent() {
        val selected = componentTable.selectionModel.selectedItem ?: return
        loadComponentIntoForm(selected); logger.info("Component reverted: ${selected.name}")
    }

    private fun clearComponentForm() {
        componentNameField.clear(); componentDescField.clear(); refPrefixField.clear(); model3dField.clear()
        editingComponentSlug = null; isDirty = false
    }

    private fun loadComponentIntoForm(summary: ComponentSummary) {
        editingComponentSlug = summary.slug
        componentNameField.text = summary.name; componentDescField.text = summary.description
        refPrefixField.text = summary.refPrefix; model3dField.clear()
        projectPaths?.let { paths ->
            val compPath = paths.componentsDir.resolve("${summary.slug}$CFCOMP_EXT")
            logger.info("Loading component: ${summary.slug} from $compPath")
            if (Files.exists(compPath)) {
                try {
                    val component = ComponentRepository.load(compPath)
                    portList.setAll(component.ports); originalPortList.setAll(component.ports)
                    mainPropertyList.setAll(component.mainProperties); originalMainPropertyList.setAll(component.mainProperties)
                    extraPropertyList.setAll(component.extraProperties); originalExtraPropertyList.setAll(component.extraProperties)
                    isDirty = false
                    logger.info("Component loaded, ports: ${component.ports.size}, mainProps: ${component.mainProperties.size}, extraProps: ${component.extraProperties.size}")
                } catch (e: Exception) {
                    logger.error("Failed to load component from $compPath", e)
                    portList.clear(); originalPortList.clear(); mainPropertyList.clear(); originalMainPropertyList.clear()
                    extraPropertyList.clear(); originalExtraPropertyList.clear()
                }
            } else {
                logger.warn("Component file not found: $compPath")
                portList.clear(); originalPortList.clear(); mainPropertyList.clear(); originalMainPropertyList.clear()
                extraPropertyList.clear(); originalExtraPropertyList.clear()
            }
        }
    }

    private fun addPortRow() {
        val nextNumber = portList.maxOfOrNull { it.number } ?: 0
        portList.add(Port(number = nextNumber + 1, pinNumber = portPinNumberField.text.trim(), name = portNameField.text.trim(), description = portDescField.text.trim()))
        portPinNumberField.clear(); portNameField.clear(); portDescField.clear(); markDirty()
    }

    private fun deletePort() { val selected = portTable.selectionModel.selectedItem ?: return; portList.remove(selected); markDirty() }

    private fun addMainPropertyRow() {
        val key = mainPropKeyField.text.trim(); val value = mainPropValueField.text.trim(); val unit = mainPropUnitField.text.trim()
        if (key.isBlank()) { UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.property.key.blank")); return }
        if (mainPropertyList.any { it.key == key }) { UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.property.key.duplicate", key)); return }
        mainPropertyList.add(Property(key = key, value = value, unit = unit))
        mainPropKeyField.clear(); mainPropValueField.clear(); mainPropUnitField.clear(); markDirty()
    }

    private fun deleteMainProperty() {
        val selected = mainPropertyTable.selectionModel.selectedItem ?: return
        mainPropertyList.remove(selected); markDirty()
    }

    private fun addExtraPropertyRow() {
        val key = extraPropKeyField.text.trim(); val value = extraPropValueField.text.trim(); val unit = extraPropUnitField.text.trim()
        if (key.isBlank()) { UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.property.key.blank")); return }
        if (extraPropertyList.any { it.key == key }) { UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("components.alert.property.key.duplicate", key)); return }
        extraPropertyList.add(Property(key = key, value = value, unit = unit))
        extraPropKeyField.clear(); extraPropValueField.clear(); extraPropUnitField.clear(); markDirty()
    }

    private fun deleteExtraProperty() {
        val selected = extraPropertyTable.selectionModel.selectedItem ?: return
        extraPropertyList.remove(selected); markDirty()
    }

    private fun markDirty() { isDirty = true }
    private fun isDirty(): Boolean = isDirty

    override fun showWindow() = stage.show()

    override fun openProject(projectPath: Path) {
        try {
            val project = ProjectRepository.load(projectPath.resolve("project.cfproj"))
            val paths = ProjectPaths(projectPath, project.machineName)
            this.projectPaths = paths
            val componentsDir = paths.componentsDir
            if (Files.exists(componentsDir)) {
                componentList.setAll(ComponentRepository.listComponents(projectPath))
                logger.info("Components loaded: ${componentList.size}")
            }
        } catch (e: Exception) {
            logger.error("Failed to open project at $projectPath", e)
            UiUtils.errorAlert(I18n.tr("common.alert.error"), "Не удалось открыть проект: ${e.message}")
        }
    }

    override fun getStage(): Stage = stage
}
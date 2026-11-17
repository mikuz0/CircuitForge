package ru.circuitforge.ui.nets

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.FileChooser
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Полноформатный редактор связей (Nets).
 * TabPane: "Список связей" + "Матрица".
 * Dirty tracking, защита от потери данных.
 */
class NetWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(NetWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "nets-io").apply { isDaemon = true }
    }

    private var projectPath: Path? = null

    // Данные
    private val nets = FXCollections.observableArrayList<Net>()
    private val netNames = FXCollections.observableArrayList<String>()
    private var savedSnapshot: String = ""

    // UI — Toolbar
    private val addNetButton = Button(I18n.tr("net.btn.add"))
    private val deleteNetButton = Button(I18n.tr("net.btn.delete"))
    private val exportCsvButton = Button(I18n.tr("net.btn.export.csv"))
    private val projectLabel = Label("Project: <not opened>")

    // UI — Список связей (Tab 1)
    private val netListView = ListView<String>()
    private val connectionsLabel = Label()
    private val connectionsTable = TableView<NetConnection>()
    private val connectionsList = FXCollections.observableArrayList<NetConnection>()
    private val addConnectionButton = Button(I18n.tr("net.btn.add.connection"))
    private val deleteConnectionButton = Button(I18n.tr("net.btn.delete.connection"))

    // UI — Матрица (Tab 2)
    private val matrixTable = TableView<NetMatrixCell>()

    // UI — Bottom
    private val saveButton = Button(I18n.tr("net.btn.save"))
    private val revertButton = Button(I18n.tr("net.btn.revert"))
    private val statusLabel = Label()

    // Текущая выбранная цепь
    private var selectedNetIndex: Int = -1

    init {
        stage.title = I18n.tr("net.window.title")
        setupConnectionTable()
        setupToolbar()
        stage.scene = Scene(createRoot(), 1100.0, 700.0)

        // Защита от потери данных при закрытии
        stage.setOnCloseRequest { event ->
            if (isDirty()) {
                event.consume()
                showCloseConfirmation()
            }
        }
    }

    private fun createRoot(): BorderPane {
        val root = BorderPane()
        root.top = createToolbar()
        root.center = createCenter()
        root.bottom = createBottom()
        return root
    }

    private fun createToolbar(): HBox {
        projectLabel.style = "-fx-font-family: monospace; -fx-text-fill: #555;"
        return HBox(10.0, addNetButton, deleteNetButton, exportCsvButton, projectLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style = "-fx-background-color: #f5f5f5; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;"
        }
    }

    private fun createCenter(): TabPane {
        val tabPane = TabPane()
        tabPane.tabMinWidth = 120.0

        val listTab = Tab(I18n.tr("net.tab.list")).apply { isClosable = false; content = createListView() }
        val matrixTab = Tab(I18n.tr("net.tab.matrix")).apply { isClosable = false; content = createMatrixView() }

        tabPane.tabs.setAll(listTab, matrixTab)
        return tabPane
    }

    private fun createListView(): SplitPane {
        val leftPanel = VBox(10.0, Label(I18n.tr("net.label.nets")), netListView).apply {
            VBox.setVgrow(netListView, Priority.ALWAYS)
            padding = Insets(10.0)
            minWidth = 200.0
            style = "-fx-background-color: white; -fx-border-color: #ddd; -fx-border-width: 0 1 0 0;"
        }
        netListView.items = netNames

        val rightPanel = VBox(10.0, connectionsLabel, connectionsTable,
            HBox(10.0, addConnectionButton, deleteConnectionButton).apply { alignment = Pos.CENTER_LEFT }
        ).apply {
            VBox.setVgrow(connectionsTable, Priority.ALWAYS)
            padding = Insets(10.0)
        }

        return SplitPane(leftPanel, rightPanel).apply { dividerPositions[0] = 0.25 }
    }

    private fun createMatrixView(): ScrollPane {
        return ScrollPane(VBox(matrixTable).apply { style = "-fx-background-color: white;" })
    }

    private fun createBottom(): HBox {
        return HBox(10.0, saveButton, revertButton, statusLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;"
        }
    }

    private fun setupConnectionTable() {
        val refDesCol = TableColumn<NetConnection, String>(I18n.tr("net.col.ref.des"))
        refDesCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.refDes) }
        refDesCol.prefWidth = 200.0

        val pinCol = TableColumn<NetConnection, String>(I18n.tr("net.col.pin.number"))
        pinCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.pinNumber) }
        pinCol.prefWidth = 150.0

        connectionsTable.columns.setAll(refDesCol, pinCol)
        connectionsTable.items = connectionsList
    }

    private fun setupToolbar() {
        addNetButton.setOnAction { addNet() }
        deleteNetButton.setOnAction { deleteNet() }
        exportCsvButton.setOnAction { exportCsv() }

        // Обработчики для соединений
        addConnectionButton.setOnAction { addConnection() }
        deleteConnectionButton.setOnAction { deleteConnection() }

        saveButton.setOnAction { saveNets() }
        revertButton.setOnAction { revertChanges() }

        netListView.selectionModel.selectedItemProperty().addListener { _, _, selectedNetName ->
            if (selectedNetName != null) {
                val net = nets.find { it.name == selectedNetName }
                if (net != null) {
                    selectedNetIndex = nets.indexOf(net)
                    connectionsLabel.text = "${I18n.tr("net.label.connections.for")} '$selectedNetName'"
                    connectionsList.setAll(net.connections.toList())
                }
            } else {
                connectionsLabel.text = I18n.tr("net.label.connections.for")
                connectionsList.clear()
            }
        }
    }

    private fun addNet() {
        val dialog = TextInputDialog("")
        dialog.title = I18n.tr("net.dialog.add")
        dialog.headerText = I18n.tr("net.dialog.create")
        dialog.contentText = I18n.tr("net.dialog.net.name")

        val result = dialog.showAndWait()
        if (result.isPresent && result.get().isNotBlank()) {
            val netName = result.get().trim()
            if (nets.any { it.name == netName }) {
                UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("net.alert.exists", netName))
                return
            }
            nets.add(Net(name = netName, connections = emptyList()))
            netNames.add(netName)
            netListView.selectionModel.select(netName)
            markDirty()
            refreshMatrix()
            logger.info("Net added: $netName")
        }
    }

    private fun deleteNet() {
        val selectedNetName = netListView.selectionModel.selectedItem ?: return
        if (!UiUtils.confirmAlert(I18n.tr("common.alert.confirm"), I18n.tr("net.alert.delete.net"), I18n.tr("net.alert.delete.net.text", selectedNetName))) return
        nets.removeAll { it.name == selectedNetName }
        netNames.remove(selectedNetName)
        selectedNetIndex = -1
        connectionsList.clear()
        markDirty()
        refreshMatrix()
        logger.info("Net deleted: $selectedNetName")
    }

    private fun addConnection() {
        if (selectedNetIndex < 0 || selectedNetIndex >= nets.size) {
            UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("net.alert.select.first"))
            return
        }

        val bomItems = projectPath?.let { BomRepository.load(it).items } ?: emptyList()
        if (bomItems.isEmpty()) {
            UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("net.alert.no.bom"))
            return
        }

        val refDesComboBox = ComboBox<BomItem>().apply {
            items.setAll(bomItems)
            cellFactory = javafx.util.Callback { _: ListView<BomItem> ->
                object : ListCell<BomItem>() {
                    override fun updateItem(item: BomItem?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else item.refDes
                    }
                }
            }
        }
        val pinComboBox = ComboBox<String>()

        refDesComboBox.valueProperty().addListener { _, _, newRefDes ->
            pinComboBox.items.clear()
            if (newRefDes != null && projectPath != null) {
                try {
                    val comp = ComponentRepository.loadComponent(projectPath!!, newRefDes.componentSlug)
                    pinComboBox.items.setAll(comp.ports.map { it.pinNumber })
                } catch (e: Exception) {
                    logger.warn("Failed to load component", e)
                }
            }
        }

        // ИЗМЕНЕНИЕ: Dialog<ButtonType> вместо Dialog<Unit>
        val dialog = Dialog<ButtonType>()
        dialog.title = I18n.tr("net.dialog.add.connection")
        dialog.headerText = I18n.tr("net.dialog.select.connection")
        dialog.dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)

        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 8.0
        grid.padding = Insets(10.0)
        GridPane.setConstraints(Label(I18n.tr("net.label.ref.des")), 0, 0)
        GridPane.setConstraints(refDesComboBox, 1, 0)
        GridPane.setConstraints(Label(I18n.tr("net.label.pin")), 0, 1)
        GridPane.setConstraints(pinComboBox, 1, 1)
        grid.children.setAll(
            Label(I18n.tr("net.label.ref.des")), refDesComboBox,
            Label(I18n.tr("net.label.pin")), pinComboBox
        )
        dialog.dialogPane.content = grid

        dialog.dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(
            refDesComboBox.valueProperty().isNull().or(pinComboBox.valueProperty().isNull())
        )

        // ИЗМЕНЕНИЕ: Используем resultProperty().orElse(CANCEL) == OK
        val result = dialog.showAndWait().orElse(ButtonType.CANCEL)

        if (result == ButtonType.OK) {
            val selectedRefDes = refDesComboBox.value
            val selectedPin = pinComboBox.value
            if (selectedRefDes != null && selectedPin != null) {
                val connection = NetConnection(refDes = selectedRefDes.refDes, pinNumber = selectedPin)
                val currentNet = nets[selectedNetIndex]
                if (currentNet.connections.any { it.refDes == connection.refDes && it.pinNumber == connection.pinNumber }) {
                    UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("net.alert.connection.exists"))
                    return
                }
                val updatedNet = currentNet.copy(connections = currentNet.connections + connection)
                nets[selectedNetIndex] = updatedNet
                connectionsList.setAll(updatedNet.connections.toList())
                markDirty()
                refreshMatrix()
                logger.info("Connection added: ${connection.refDes}.${connection.pinNumber}")
            }
        }
    }

    private fun deleteConnection() {
        val selected = connectionsTable.selectionModel.selectedItem ?: return
        if (selectedNetIndex < 0 || selectedNetIndex >= nets.size) return
        val currentNet = nets[selectedNetIndex]
        val updatedNets = currentNet.connections.filter { it != selected }
        val updatedNet = currentNet.copy(connections = updatedNets)
        nets[selectedNetIndex] = updatedNet
        connectionsList.setAll(updatedNet.connections.toList())
        markDirty()
        refreshMatrix()
        logger.info("Connection deleted: ${selected.refDes}.${selected.pinNumber}")
    }

    private fun refreshMatrix() {
        matrixTable.items = FXCollections.observableArrayList()
        matrixTable.columns.clear()

        if (nets.isEmpty()) return

        // 1. Собираем порты ТОЛЬКО из существующих связей (connections)
        val usedPorts = mutableSetOf<String>()
        nets.forEach { net ->
            net.connections.forEach { conn ->
                usedPorts.add("${conn.refDes}.${conn.pinNumber}")
            }
        }
        val sortedPorts = usedPorts.sorted()

        // Если связей нет вообще — матрица пустая (только колонка "Цепь")
        // 2. Формируем строки
        val matrixRows = FXCollections.observableArrayList<NetMatrixCell>()
        nets.forEach { net ->
            val connectedPorts = net.connections.map { "${it.refDes}.${it.pinNumber}" }.toSet()
            matrixRows.add(NetMatrixCell(
                netName = net.name,
                connectedPorts = connectedPorts,
                net = net
            ))
        }

        // 3. Колонка с именами цепей
        val netNameCol = TableColumn<NetMatrixCell, String>("Цепь")
        netNameCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.netName) }
        netNameCol.prefWidth = 150.0
        netNameCol.isSortable = false
        matrixTable.columns.add(netNameCol)

        // 4. Колонки для используемых портов
        sortedPorts.forEach { portRef ->
            val col = TableColumn<NetMatrixCell, Boolean>(portRef)
            col.prefWidth = 80.0
            col.isSortable = false
            col.setCellValueFactory { cellData ->
                javafx.beans.property.SimpleBooleanProperty(cellData.value.connectedPorts.contains(portRef))
            }
            col.cellFactory = javafx.util.Callback {
                object : TableCell<NetMatrixCell, Boolean>() {
                    private val checkBox = CheckBox().apply {
                        setOnAction {
                            val rowItem = tableRow.item
                            if (rowItem != null) {
                                toggleConnection(rowItem.net, portRef, isSelected)
                            }
                        }
                    }
                    override fun updateItem(item: Boolean?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) {
                            graphic = null
                        } else {
                            graphic = checkBox
                            checkBox.isSelected = item
                        }
                    }
                }
            }
            matrixTable.columns.add(col)
        }

        matrixTable.items = matrixRows
    }

    private fun toggleConnection(net: Net, portRef: String, connected: Boolean) {
        val parts = portRef.split(".", limit = 2)
        val refDes = parts[0]
        val pinNumber = parts.getOrElse(1) { "" }

        val netIndex = nets.indexOfFirst { it.name == net.name }
        if (netIndex < 0) return

        val currentNet = nets[netIndex]
        val existingConn = currentNet.connections.find { it.refDes == refDes && it.pinNumber == pinNumber }

        val newConnections = if (connected) {
            if (existingConn == null) currentNet.connections + NetConnection(refDes, pinNumber)
            else currentNet.connections
        } else {
            currentNet.connections.filter { it != existingConn }
        }

        nets[netIndex] = currentNet.copy(connections = newConnections)

        // Обновляем таблицу соединений (вкладка 1), если эта цепь выбрана
        if (selectedNetIndex == netIndex) {
            connectionsList.setAll(newConnections)
        }

        markDirty()
    }

    private fun exportCsv() {
        val chooser = FileChooser()
        chooser.title = I18n.tr("net.dialog.export.csv")
        chooser.initialFileName = "nets.csv"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("CSV Files", "*.csv"))
        val projectDir = projectPath ?: return
        val exportsDir = projectDir.resolve("exports")
        if (Files.exists(exportsDir)) chooser.initialDirectory = exportsDir.toFile()

        val file = chooser.showSaveDialog(stage)
        if (file != null) {
            val task = object : javafx.concurrent.Task<Unit>() {
                override fun call() { NetRepository.exportToCsv(projectPath!!, file.toPath()) }
            }
            task.setOnSucceeded {
                statusLabel.text = I18n.tr("net.status.exported", file.path)
                statusLabel.style = "-fx-text-fill: green;"
            }
            task.setOnFailed {
                UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("net.status.export.failed"))
                statusLabel.text = I18n.tr("net.status.export.failed")
                statusLabel.style = "-fx-text-fill: red;"
            }
            executor.submit(task)
        }
    }

    private fun saveNets() {
        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() {
                projectPath?.let { dir ->
                    NetRepository.save(dir, NetDocument(schemaVersion = 1, nets = nets.toList()))
                    savedSnapshot = serializeCurrentState()
                }
            }
        }
        task.setOnSucceeded {
            statusLabel.text = I18n.tr("net.status.saved")
            statusLabel.style = "-fx-text-fill: green;"
            logger.info("Netlist saved")
        }
        task.setOnFailed {
            val ex = task.exception
            if (ex is IllegalArgumentException) UiUtils.errorAlert(I18n.tr("net.alert.validation.error"), ex.message ?: I18n.tr("common.alert.unknown.error"))
            else UiUtils.errorAlert(I18n.tr("common.alert.error"), I18n.tr("net.status.save.failed"))
            statusLabel.text = I18n.tr("net.status.save.failed")
            statusLabel.style = "-fx-text-fill: red;"
            logger.error("Failed to save netlist", ex)
        }
        executor.submit(task)
    }

    private fun revertChanges() {
        projectPath?.let { dir ->
            val doc = NetRepository.load(dir)
            nets.setAll(doc.nets)
            netNames.setAll(doc.nets.map { it.name })
            savedSnapshot = serializeCurrentState()
            selectedNetIndex = -1
            connectionsList.clear()
            statusLabel.text = I18n.tr("net.status.reverted")
            statusLabel.style = "-fx-text-fill: blue;"
            refreshMatrix()
            logger.info("Netlist reverted")
        }
    }

    /** Диалог подтверждения закрытия при несохранённых изменениях. */
    private fun showCloseConfirmation() {
        val alert = Alert(Alert.AlertType.CONFIRMATION)
        alert.title = I18n.tr("bom.alert.unsaved")
        alert.headerText = I18n.tr("bom.alert.unsaved.header")
        alert.contentText = I18n.tr("bom.alert.unsaved.text")

        val result = alert.showAndWait()
        when {
            result.orElse(ButtonType.CANCEL) == ButtonType.YES -> {
                saveNets()
                if (!isDirty()) stage.close()
            }
            result.orElse(ButtonType.CANCEL) == ButtonType.NO -> stage.close()
        }
    }

    private fun serializeCurrentState(): String {
        return nets.map { net ->
            "${net.name}|${net.connections.sortedBy { it.refDes + it.pinNumber }.joinToString(";") { "${it.refDes}.${it.pinNumber}" }}"
        }.joinToString("|")
    }

    private fun isDirty(): Boolean = serializeCurrentState() != savedSnapshot
    private fun markDirty() {
        statusLabel.text = I18n.tr("bom.alert.unsaved")
        statusLabel.style = "-fx-text-fill: orange; -fx-font-size: 11;"
    }

    override fun showWindow() = stage.show()

    override fun openProject(projectPath: Path) {
        this.projectPath = projectPath
        projectLabel.text = "Project: ${projectPath.toString()}"
        val doc = NetRepository.load(projectPath)
        nets.setAll(doc.nets)
        netNames.setAll(doc.nets.map { it.name })
        savedSnapshot = serializeCurrentState()
        selectedNetIndex = -1
        connectionsList.clear()
        refreshMatrix()
        logger.info("Netlist opened for project: $projectPath, nets count: ${nets.size}")
    }

    override fun getStage(): Stage = stage
}

/** Ячейка матрицы связей. */
/** Ячейка матрицы связей. Одна строка = одна цепь. */
data class NetMatrixCell(
    val netName: String,
    val connectedPorts: Set<String>, // например, setOf("DD2.1", "R5.1")
    val net: Net
)

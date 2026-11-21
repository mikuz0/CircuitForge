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

    private var projectPaths: ProjectPaths? = null

    // Данные
    private val nets = FXCollections.observableArrayList<Net>()
    private val netList = FXCollections.observableArrayList<Net>()
    private var savedSnapshot: String = ""

    // UI — Toolbar
    private val addNetButton = Button(I18n.tr("net.btn.add"))
    private val deleteNetButton = Button(I18n.tr("net.btn.delete"))
    private val renameNetButton = Button(I18n.tr("net.btn.rename"))
    private val mergeNetsButton = Button(I18n.tr("net.btn.merge"))
    private val exportCsvButton = Button(I18n.tr("net.btn.export.csv"))
    private val projectLabel = Label("Project: <not opened>")

    // UI — Список связей (Tab 1)
    private val netTable = TableView<Net>()
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
        setupNetTable()
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
        return HBox(10.0, addNetButton, renameNetButton, mergeNetsButton, deleteNetButton, exportCsvButton, projectLabel).apply {
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
        val leftPanel = VBox(10.0, Label(I18n.tr("net.label.nets")), netTable).apply {
            VBox.setVgrow(netTable, Priority.ALWAYS)
            padding = Insets(10.0)
            minWidth = 300.0
            style = "-fx-background-color: white; -fx-border-color: #ddd; -fx-border-width: 0 1 0 0;"
        }

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

    private fun setupNetTable() {
        val nameCol = TableColumn<Net, String>(I18n.tr("net.col.name"))
        nameCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.name) }
        nameCol.prefWidth = 180.0

        val pinCountCol = TableColumn<Net, Int>(I18n.tr("net.col.pin.count"))
        pinCountCol.prefWidth = 80.0
        pinCountCol.cellFactory = javafx.util.Callback { _: TableColumn<Net, Int> ->
            object : TableCell<Net, Int>() {
                override fun updateItem(item: Int?, empty: Boolean) {
                    super.updateItem(item, empty)
                    val net = tableRow.item
                    text = if (empty || item == null) "" else net?.connections?.size?.toString() ?: ""
                }
            }
        }

        val labelCol = TableColumn<Net, Boolean>(I18n.tr("net.col.label.mode"))
        labelCol.setCellValueFactory { javafx.beans.property.SimpleBooleanProperty(it.value.labelMode) }
        labelCol.prefWidth = 80.0
        labelCol.setCellFactory { _ ->
            object : TableCell<Net, Boolean>() {
                private val checkBox = CheckBox()
                override fun updateItem(item: Boolean?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        graphic = null
                    } else {
                        val net = tableRow.item
                        if (net != null) {
                            checkBox.isSelected = item
                            checkBox.selectedProperty().addListener { _, _, newVal ->
                                net.labelMode = newVal
                                markDirty()
                                saveNetsAsync()
                            }
                            graphic = checkBox
                        } else {
                            graphic = null
                        }
                    }
                }
            }
        }

        netTable.columns.setAll(nameCol, pinCountCol, labelCol)
        netTable.items = netList
        netTable.selectionModel.selectionMode = javafx.scene.control.SelectionMode.MULTIPLE

        // Обработчик выбора цепи
        netTable.selectionModel.selectedItemProperty().addListener { _, _, selectedNet ->
            if (selectedNet != null) {
                selectedNetIndex = nets.indexOf(selectedNet)
                connectionsLabel.text = "${I18n.tr("net.label.connections.for")} '${selectedNet.name}'"
                connectionsList.setAll(selectedNet.connections.toList())
            } else {
                connectionsList.clear()
                selectedNetIndex = -1
            }
        }
    }

    private fun setupToolbar() {
        addNetButton.setOnAction { addNet() }
        deleteNetButton.setOnAction { deleteNet() }
        exportCsvButton.setOnAction { exportCsv() }
        renameNetButton.setOnAction { renameNet() }
        mergeNetsButton.setOnAction { mergeNets() }
        // Включение/выключение кнопки "Объединить" при изменении выделения
        netTable.selectionModel.selectedItems.addListener(
            javafx.collections.ListChangeListener { change ->
                val selectedCount = netTable.selectionModel.selectedItems.size
                mergeNetsButton.isDisable = (selectedCount != 2)
            }
        )
        // Обработчики для соединений
        addConnectionButton.setOnAction { addConnection() }
        deleteConnectionButton.setOnAction { deleteConnection() }

        saveButton.setOnAction { saveNets() }
        revertButton.setOnAction { revertChanges() }
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
            val newNet = Net(name = netName, connections = emptyList())
            nets.add(newNet)
            netList.add(newNet)
            netTable.selectionModel.select(newNet)
            markDirty()
            refreshMatrix()
            logger.info("Net added: $netName")
        }
    }

    private fun deleteNet() {
        val selectedNet = netTable.selectionModel.selectedItem ?: return
        val selectedNetName = selectedNet.name
        if (!UiUtils.confirmAlert(I18n.tr("common.alert.confirm"), I18n.tr("net.alert.delete.net"), I18n.tr("net.alert.delete.net.text", selectedNetName))) return
        nets.remove(selectedNet)
        netList.remove(selectedNet)
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

        val paths = projectPaths ?: return
        val selectedNet = nets[selectedNetIndex]

        // Проверяем, что BOM не пустой
        val bomRows = BomRepository.loadRows(paths)
        if (bomRows.isEmpty()) {
            UiUtils.errorAlert(
                I18n.tr("common.alert.error"),
                I18n.tr("nets.dialog.add.connection.no.bom") ?: "BOM пуст. Сначала создайте BOM в редакторе."
            )
            return
        }

        AddConnectionDialog(
            ownerStage = stage,
            projectPaths = paths,
            currentNet = selectedNet,
            allNets = nets.toList()
        ) { connection ->
            // Добавляем соединение к текущей цепи
            val currentNet = nets[selectedNetIndex]
            val updatedNet = currentNet.copy(connections = currentNet.connections + connection)
            nets[selectedNetIndex] = updatedNet
            connectionsList.setAll(updatedNet.connections.toList())
            markDirty()
            refreshMatrix()
            logger.info("Connection added: ${connection.refDes}.${connection.pinNumber}")

            // Автоматически сохраняем
            saveNetsAsync()
        }.show()
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
        val paths = projectPaths ?: return
        val exportsDir = paths.exportsDir
        if (Files.exists(exportsDir)) chooser.initialDirectory = exportsDir.toFile()

        val file = chooser.showSaveDialog(stage)
        if (file != null) {
            val task = object : javafx.concurrent.Task<Unit>() {
                override fun call() { NetRepository.exportToCsv(paths, file.toPath()) }
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
                projectPaths?.let { paths ->
                    NetRepository.save(paths, NetDocument(schemaVersion = 1, nets = nets.toList()))
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

    /** Асинхронное сохранение без обновления snapshot (для автосохранения после добавления соединения). */
    private fun saveNetsAsync() {
        val task = object : javafx.concurrent.Task<Unit>() {
            override fun call() {
                projectPaths?.let { paths ->
                    NetRepository.save(paths, NetDocument(schemaVersion = 1, nets = nets.toList()))
                }
            }
            override fun succeeded() {
                logger.info("Netlist auto-saved after connection add")
            }
            override fun failed() {
                logger.error("Failed to auto-save netlist", exception)
            }
        }
        executor.submit(task)
    }

    private fun revertChanges() {
        projectPaths?.let { paths ->
            val doc = NetRepository.load(paths)
            nets.setAll(doc.nets)
            netList.setAll(doc.nets)
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
        try {
            val project = ProjectRepository.load(projectPath.resolve("project.cfproj"))
            val paths = ProjectPaths(projectPath, project.machineName)
            this.projectPaths = paths
            projectLabel.text = "Project: ${projectPath}"
            val doc = NetRepository.load(paths)
            nets.setAll(doc.nets)
            netList.setAll(doc.nets)
            savedSnapshot = serializeCurrentState()
            selectedNetIndex = -1
            connectionsList.clear()
            refreshMatrix()
            logger.info("Netlist opened for project: $projectPath, nets count: ${nets.size}")
        } catch (e: Exception) {
            logger.error("Failed to open project at $projectPath", e)
            UiUtils.errorAlert(I18n.tr("common.alert.error"), "Не удалось открыть проект: ${e.message}")
        }
    }

    override fun getStage(): Stage = stage
    private fun renameNet() {
        val selectedNet = netTable.selectionModel.selectedItem ?: return
        val selectedNetName = selectedNet.name
        val dialog = TextInputDialog(selectedNetName)
        dialog.title = I18n.tr("net.dialog.rename")
        dialog.headerText = I18n.tr("net.dialog.rename.header")
        dialog.contentText = I18n.tr("net.dialog.net.name")

        val result = dialog.showAndWait()
        if (result.isPresent && result.get().isNotBlank()) {
            val newName = result.get().trim()
            if (newName == selectedNetName) return

            if (nets.any { it.name == newName }) {
                UiUtils.errorAlert(I18n.tr("common.alert.error"),
                    "Цепь с именем '$newName' уже существует. Используйте 'Объединить' для слияния.")
                return
            }

            val netIndex = nets.indexOfFirst { it.name == selectedNetName }
            if (netIndex >= 0) {
                nets[netIndex] = nets[netIndex].copy(name = newName)
                netList[netIndex] = nets[netIndex]
                netTable.selectionModel.select(netIndex)
                refreshMatrix()
                markDirty()
                logger.info("Net renamed: $selectedNetName -> $newName")
            }
        }
    }

    private fun mergeNets() {
        val selectedItems = netTable.selectionModel.selectedItems
        if (selectedItems.size != 2) return

        val net1 = selectedItems[0]
        val net2 = selectedItems[1]
        val net1Name = net1.name
        val net2Name = net2.name

        // Диалог выбора, какое имя оставить
        val dialog = ChoiceDialog(net1Name, listOf(net1Name, net2Name))
        dialog.title = I18n.tr("net.dialog.merge")
        dialog.headerText = I18n.tr("net.dialog.merge.header")
        dialog.contentText = "Выберите имя для объединенной цепи:"

        val result = dialog.showAndWait()
        if (result.isPresent) {
            val targetName = result.get()
            val sourceName = if (targetName == net1Name) net2Name else net1Name

            val targetIndex = nets.indexOfFirst { it.name == targetName }
            val sourceIndex = nets.indexOfFirst { it.name == sourceName }

            if (targetIndex >= 0 && sourceIndex >= 0) {
                val targetNet = nets[targetIndex]
                val sourceNet = nets[sourceIndex]

                // Объединяем соединения и убираем дубликаты
                val mergedConnections = (targetNet.connections + sourceNet.connections).distinct()

                // Обновляем целевую цепь
                nets[targetIndex] = targetNet.copy(connections = mergedConnections)
                netList[targetIndex] = nets[targetIndex]

                // Удаляем исходную цепь
                nets.removeAt(sourceIndex)
                netList.removeAt(sourceIndex)

                // Если выбранная цепь была исходной — переключаемся на целевую
                if (selectedNetIndex == sourceIndex) {
                    selectedNetIndex = targetIndex
                } else if (selectedNetIndex > sourceIndex) {
                    selectedNetIndex-- // Сдвиг индекса после удаления
                }

                // Обновляем UI
                netTable.selectionModel.clearSelection()
                netTable.selectionModel.select(targetIndex)
                connectionsList.setAll(mergedConnections)

                refreshMatrix()
                markDirty()
                logger.info("Nets merged: $net1Name + $net2Name -> $targetName")
            }
        }
    }


}

/** Ячейка матрицы связей. */
/** Ячейка матрицы связей. Одна строка = одна цепь. */
data class NetMatrixCell(
    val netName: String,
    val connectedPorts: Set<String>, // например, setOf("DD2.1", "R5.1")
    val net: Net
)

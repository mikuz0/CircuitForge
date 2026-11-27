package ru.circuitforge.ui.nets

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.*
import javafx.stage.Modality
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.UiUtils
import javafx.beans.value.ChangeListener

/**
 * Отображение пина в ComboBox с именем контакта.
 */
data class PinDisplay(
    val pinNumber: String,
    val portName: String
) {
    override fun toString(): String {
        return if (portName.isNotBlank()) {
            "$pinNumber ($portName)"
        } else {
            pinNumber
        }
    }
}

/**
 * Диалог добавления соединения к текущей цепи.
 *
 * Показывает таблицу BOM с поиском и ComboBox пинов для каждого компонента.
 */
class AddConnectionDialog(
    ownerStage: Stage,
    private val projectPaths: ProjectPaths,
    private val currentNet: Net,
    private val allNets: List<Net>,
    private val onConfirm: (NetConnection) -> Unit
) {
    private val logger = LoggerFactory.getLogger(AddConnectionDialog::class.java)

    // Данные
    private val bomRows = mutableListOf<BomRow>()
    private val componentsMap = mutableMapOf<String, Component>()
    private val searchStrings = mutableMapOf<BomRow, String>()
    private val pinOptionsMap = mutableMapOf<String, List<PinDisplay>>() // refDes -> List<PinDisplay>

    // UI
    private val stage = Stage()
    private val searchField = TextField()
    private val clearButton = Button(I18n.tr("nets.dialog.add.connection.clear") ?: "Очистить")
    private val resultsTable = TableView<BomRow>()
    private val resultsList = FXCollections.observableArrayList<BomRow>()
    private val pinComboBoxes = FXCollections.observableMap(mutableMapOf<String, ComboBox<String>>())
    private val statusLabel = Label()
    private val addButton = Button(I18n.tr("nets.dialog.add.connection.confirm") ?: "Добавить")
    private val cancelButton = Button(I18n.tr("nets.dialog.add.connection.cancel") ?: "Отмена")

    // Состояние
    private val selectedPins = mutableMapOf<String, PinDisplay?>()

    init {
        stage.initOwner(ownerStage)
        stage.initModality(Modality.APPLICATION_MODAL)
        stage.title = I18n.tr("nets.dialog.add.connection.title", currentNet.name)
            .replace("{0}", currentNet.name)
        loadData()
        setupSearchField()
        setupTable()
        setupButtons()
        stage.scene = Scene(createRoot(), 850.0, 500.0)
    }

    // ─── Загрузка данных ──────────────────────────────────────────

    private fun loadData() {
        try {
            val rows = BomRepository.loadRows(projectPaths)
            if (rows.isEmpty()) {
                UiUtils.errorAlert(
                    I18n.tr("common.alert.error") ?: "Ошибка",
                    I18n.tr("nets.dialog.add.connection.no.bom") ?: "BOM пуст. Сначала создайте BOM в редакторе."
                )
                stage.close()
                return
            }

            bomRows.addAll(rows)

            // Загружаем компоненты для пинов
            for (row in rows) {
                try {
                    val comp = ComponentRepository.loadComponentById(projectPaths.projectDir, row.componentId)
                    if (comp != null) {
                        componentsMap[row.componentId] = comp
                        // Создаём PinDisplay для каждого порта
                        val pinDisplays = comp.ports.map { port ->
                            PinDisplay(pinNumber = port.pinNumber, portName = port.name)
                        }
                        pinOptionsMap[row.refDes] = pinDisplays
                    } else {
                        pinOptionsMap[row.refDes] = emptyList()
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to load component for refDes=${row.refDes}", e)
                    pinOptionsMap[row.refDes] = emptyList()
                }
                searchStrings[row] = buildSearchString(row)
            }

            // Начальная фильтрация (все строки)
            applySearch()
        } catch (e: Exception) {
            logger.error("Failed to load data for connection dialog", e)
            UiUtils.errorAlert(
                I18n.tr("common.alert.error") ?: "Ошибка",
                I18n.tr("nets.dialog.add.connection.load.error") ?: "Не удалось загрузить данные: ${e.message}"
            )
            stage.close()
        }
    }

    private fun buildSearchString(row: BomRow): String {
        return "${row.refDes} ${row.componentName} ${row.comment}".lowercase()
    }

    // ─── Поиск ────────────────────────────────────────────────────

    private fun setupSearchField() {
        searchField.promptText = I18n.tr("nets.dialog.add.connection.search.prompt")
            ?: "Поиск по RefDes, имени компонента, комментарию..."
        searchField.textProperty().addListener { _, _, _ -> applySearch() }

        // Ctrl+A — выделить весь текст
        searchField.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.isControlDown && event.code == KeyCode.A) {
                searchField.selectAll()
                event.consume()
            }
            // Enter — добавить выделенную строку
            if (event.code == KeyCode.ENTER && searchField.text.trim().isNotEmpty()) {
                val selection = resultsTable.selectionModel.selectedItem
                if (selection != null) {
                    confirmSelection(selection)
                    event.consume()
                }
            }
        }

        clearButton.setOnAction {
            searchField.clear()
            searchField.requestFocus()
        }
    }

    private fun applySearch() {
        val query = searchField.text.trim()

        val filtered = if (query.isEmpty()) {
            bomRows
        } else {
            bomRows.filter { row ->
                searchStrings[row]?.contains(query.lowercase()) == true
            }
        }

        resultsList.setAll(filtered)
        updateStatus()
        updateAddButtonState()
    }

    // ─── Таблица ──────────────────────────────────────────────────

    private fun setupTable() {
        resultsTable.items = resultsList
        resultsTable.selectionModel.selectionMode = javafx.scene.control.SelectionMode.SINGLE

        // Колонка: RefDes
        val refDesCol = TableColumn<BomRow, String>(I18n.tr("net.col.ref.des"))
        refDesCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.refDes) }
        refDesCol.prefWidth = 100.0
        refDesCol.isEditable = false

        // Колонка: Компонент
        val componentCol = TableColumn<BomRow, String>(I18n.tr("nets.dialog.add.connection.col.component"))
        componentCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.componentName) }
        componentCol.prefWidth = 200.0
        componentCol.isEditable = false

        // Колонка: Комментарий
        val commentCol = TableColumn<BomRow, String>(I18n.tr("nets.dialog.add.connection.col.comment"))
        commentCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.comment) }
        commentCol.prefWidth = 200.0
        commentCol.isEditable = false

        // Колонка: Пин
        val pinCol = TableColumn<BomRow, String>(I18n.tr("net.col.pin.number"))
        pinCol.prefWidth = 150.0
        pinCol.isEditable = false
        pinCol.setCellFactory {
            object : TableCell<BomRow, String>() {
                private val comboBox = ComboBox<PinDisplay>().apply {
                    isEditable = true
                    promptText = "—"
                }

                init {
                    comboBox.cellFactory = javafx.util.Callback { 
                        object : ListCell<PinDisplay>() {
                            override fun updateItem(item: PinDisplay?, empty: Boolean) {
                                super.updateItem(item, empty)
                                text = if (empty || item == null) null else item.toString()
                            }
                        }
                    }
                }

                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty) {
                        graphic = null
                    } else {
                        val row = tableRow.item
                        if (row != null) {
                            val refDes = row.refDes
                            val pins = pinOptionsMap[refDes] ?: emptyList()

                            if (pins.isEmpty()) {
                                comboBox.promptText = I18n.tr("nets.dialog.add.connection.pin.not.found")
                                    ?: "<компонент не найден>"
                            } else {
                                if (comboBox.items.isEmpty()) {
                                    comboBox.items.setAll(pins)
                                }
                            }

                            // Устанавливаем текущее выбранное значение
                            val currentPin = selectedPins[refDes]
                            comboBox.selectionModel.select(currentPin)

                            // Создаём слушатель (при пересоздании ячейки старый listener автоматически detached)
                            val listener = ChangeListener<PinDisplay> { _, _, newValue ->
                                selectedPins[refDes] = newValue
                                updateAddButtonState()
                            }
                            comboBox.selectionModel.selectedItemProperty().addListener(listener)

                            graphic = comboBox
                        } else {
                            graphic = null
                        }
                    }
                }

                override fun cancelEdit() {
                    super.cancelEdit()
                }

                override fun startEdit() {
                    super.startEdit()
                }
            }
        }

        resultsTable.columns.setAll(refDesCol, componentCol, commentCol, pinCol)

        // Подсветка уже добавленных соединений
        resultsTable.rowFactory = javafx.util.Callback {
            object : TableRow<BomRow>() {
                override fun updateItem(row: BomRow?, empty: Boolean) {
                    super.updateItem(row, empty)
                    if (empty || row == null) {
                        style = ""
                    } else {
                        // Проверяем, есть ли уже такое соединение в текущей цепи
                        val isAlreadyConnected = currentNet.connections.any {
                            it.refDes == row.refDes && it.pinNumber.isNotEmpty()
                        }
                        if (isAlreadyConnected) {
                            style = "-fx-background-color: #f0f0f0;"
                        } else {
                            style = ""
                        }
                    }
                }
            }
        }

        // Двойной клик → добавить
        resultsTable.onMouseClicked = javafx.event.EventHandler { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
                val selection = resultsTable.selectionModel.selectedItem
                if (selection != null) {
                    confirmSelection(selection)
                }
            }
        }
    }

    // ─── Проверка занятости пина ──────────────────────────────────

    /**
     * Проверяет, используется ли (refDes, pinNumber) в других цепях.
     * Возвращает имя цепи или null.
     */
    private fun isPinUsedInOtherNet(refDes: String, pinNumber: String): String? {
        for (net in allNets) {
            if (net.name == currentNet.name) continue // пропускаем текущую цепь
            if (net.connections.any { it.refDes == refDes && it.pinNumber == pinNumber }) {
                return net.name
            }
        }
        return null
    }

    // ─── Кнопки ───────────────────────────────────────────────────

    private fun setupButtons() {
        (addButton as javafx.scene.control.Button).isDisable = true
        addButton.setOnAction {
            val selection = resultsTable.selectionModel.selectedItem
            if (selection != null) confirmSelection(selection)
        }

        cancelButton.setOnAction { stage.close() }

        // Escape → закрыть
        stage.sceneProperty().addListener { _, _, scene ->
            scene?.onKeyReleased = javafx.event.EventHandler { event ->
                if (event.code == KeyCode.ESCAPE) {
                    stage.close()
                    event.consume()
                }
            }
        }
    }

    private fun updateStatus() {
        statusLabel.text = I18n.tr("nets.dialog.add.connection.found", resultsList.size)
            .replace("{0}", resultsList.size.toString())
    }

    private fun updateAddButtonState() {
        val selection = resultsTable.selectionModel.selectedItem
        if (selection == null) {
            (addButton as javafx.scene.control.Button).isDisable = true
            return
        }
        val pinDisplay = selectedPins[selection.refDes]
        (addButton as javafx.scene.control.Button).isDisable = pinDisplay == null || pinDisplay.pinNumber.isBlank()
    }

    // ─── Добавление соединения ────────────────────────────────────

    private fun confirmSelection(row: BomRow) {
        val pinDisplay = selectedPins[row.refDes] ?: return
        val pin = pinDisplay.pinNumber

        // Проверка: пин уже в текущей цепи — можно добавить дубликат (визуально)
        // Проверка: пин занят в другой цепи
        val otherNetName = isPinUsedInOtherNet(row.refDes, pin)
        if (otherNetName != null) {
            val alert = Alert(Alert.AlertType.WARNING)
            alert.title = I18n.tr("nets.dialog.add.connection.pin.in.use.title")
                ?: "Пин уже используется"
            alert.headerText = I18n.tr("nets.dialog.add.connection.pin.in.use.header")
                ?: "Внимание"
            alert.contentText = I18n.tr("nets.dialog.add.connection.pin.in.use.text", row.refDes, pin, otherNetName)
                ?: "Пин ${row.refDes}-$pin уже используется в цепи '$otherNetName'. Добавить?"
            alert.dialogPane.buttonTypes.setAll(ButtonType.YES, ButtonType.NO)

            val result = alert.showAndWait()
            if (result.orElse(ButtonType.NO) != ButtonType.YES) {
                return // пользователь отменил
            }
        }

        val connection = NetConnection(refDes = row.refDes, pinNumber = pin)
        onConfirm(connection)
        stage.close()
    }

    // ─── UI Layout ────────────────────────────────────────────────

    private fun createRoot(): BorderPane {
        val root = BorderPane()

        // Top: search bar
        val searchBar = HBox(10.0, searchField, clearButton).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;"
        }
        root.top = searchBar

        // Center: table
        val scrollPane = ScrollPane(resultsTable).apply {
            hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            isFitToWidth = true
        }
        root.center = scrollPane

        // Bottom: status + buttons
        val bottomBar = HBox(10.0, statusLabel, Region(), addButton, cancelButton).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;"
        }
        root.bottom = bottomBar

        return root
    }

    // ─── Show ─────────────────────────────────────────────────────

    fun show() {
        stage.showAndWait()
    }
}

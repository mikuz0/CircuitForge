package ru.circuitforge.ui.editor

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
import java.nio.file.Path

/**
 * Результат поиска компонента с полями совпадений.
 */
data class ComponentSearchResult(
    val component: Component,
    val matchFields: List<String>,
)

/**
 * Двухэтапный диалог добавления компонента в BOM.
 *
 * Этап 1: текстовый поиск по всем полям компонента → таблица результатов.
 * Этап 2: выбор строки → динамическая панель фильтров по mainProperties.
 */
class AddBomItemDialog(
    ownerStage: Stage,
    private val projectDir: Path,
    private val onConfirm: (BomRow) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(AddBomItemDialog::class.java)

    // Данные
    private val allComponents = mutableListOf<Component>()
    private val searchStrings = mutableMapOf<Component, String>()

    // UI
    private val stage = Stage()
    private val searchField = TextField()
    private val clearButton = Button(I18n.tr("bom.dialog.add.clear") ?: "Очистить")
    private val resultsTable = TableView<ComponentSearchResult>()
    private val resultsList = FXCollections.observableArrayList<ComponentSearchResult>()
    private val filterPane = VBox(8.0)
    private val filterComboBoxes = mutableMapOf<String, ComboBox<String>>()
    private val statusLabel = Label()
    private val addButton = Button(I18n.tr("bom.dialog.add.confirm") ?: "Добавить")
    private val cancelButton = Button(I18n.tr("bom.dialog.add.cancel") ?: "Отмена")

    // Состояние
    private var selectedComponent: Component? = null
    private val filterValues = mutableMapOf<String, String?>()

    init {
        stage.initOwner(ownerStage)
        stage.initModality(Modality.APPLICATION_MODAL)
        stage.title = I18n.tr("bom.dialog.add.title") ?: "Добавить элемент в BOM"
        loadComponents()
        setupSearchField()
        setupTable()
        setupButtons()
        stage.scene = Scene(createRoot(), 900.0, 550.0)
    }

    // ─── Загрузка данных ──────────────────────────────────────────

    private fun loadComponents() {
        try {
            val summaries = ComponentRepository.listComponents(projectDir)
            if (summaries.isEmpty()) {
                UiUtils.errorAlert(
                    I18n.tr("common.alert.error") ?: "Ошибка",
                    I18n.tr("bom.dialog.add.no.components") ?: "В проекте нет компонентов. Создайте их в редакторе компонентов.",
                )
                stage.close()
                return
            }

            for (slug in summaries.map { it.slug }) {
                try {
                    val comp = ComponentRepository.loadComponent(projectDir, slug)
                    allComponents.add(comp)
                    searchStrings[comp] = buildSearchString(comp)
                } catch (e: Exception) {
                    logger.warn("Failed to load component slug=$slug", e)
                }
            }

            // Начальная фильтрация (пока все)
            applySearchAndFilters()
        } catch (e: Exception) {
            logger.error("Failed to load components from $projectDir", e)
            UiUtils.errorAlert(
                I18n.tr("common.alert.error") ?: "Ошибка",
                I18n.tr("bom.dialog.add.load.error") ?: "Не удалось загрузить компоненты: ${e.message}",
            )
            stage.close()
        }
    }

    /** Построение поисковой строки из всех полей компонента. */
    private fun buildSearchString(comp: Component): String {
        val mainProps = comp.mainProperties.joinToString(" ") { "${it.key} ${it.value} ${it.unit}" }
        val extraProps = comp.extraProperties.joinToString(" ") { "${it.key} ${it.value} ${it.unit}" }
        return "${comp.name} ${comp.description} ${comp.refPrefix} $mainProps $extraProps".lowercase()
    }

    /** Найти поля, в которых совпал запрос. */
    private fun findMatches(
        comp: Component,
        query: String,
    ): List<String> {
        val matches = mutableListOf<String>()
        val q = query.lowercase()

        if (comp.name.lowercase().contains(q)) matches.add(I18n.tr("bom.dialog.add.match.name") ?: "Имя")
        if (comp.description.lowercase().contains(q)) matches.add(I18n.tr("bom.dialog.add.match.description") ?: "Описание")
        if (comp.refPrefix.lowercase().contains(q)) matches.add(I18n.tr("bom.dialog.add.match.prefix") ?: "Префикс")

        for (prop in comp.mainProperties + comp.extraProperties) {
            if (prop.key.lowercase().contains(q)) {
                matches.add("${prop.key} (${I18n.tr("bom.dialog.add.match.key") ?: "ключ"})")
            }
            if (prop.value.lowercase().contains(q)) {
                matches.add("${prop.key}: ${prop.value}")
            }
        }
        return matches
    }

    // ─── Поиск ────────────────────────────────────────────────────

    private fun setupSearchField() {
        searchField.promptText = I18n.tr("bom.dialog.add.search.prompt") ?: "Поиск по имени, описанию, свойствам..."
        searchField.textProperty().addListener { _, _, _ -> applySearchAndFilters() }

        // Ctrl+A — выделить весь текст
        searchField.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.isControlDown && event.code == KeyCode.A) {
                searchField.selectAll()
                event.consume()
            }
            // Enter — добавить выделенный
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

    /** Первичная фильтрация + вторичные фильтры. */
    private fun applySearchAndFilters() {
        val query = searchField.text.trim()

        val searched =
            if (query.isEmpty()) {
                allComponents.map { comp -> ComponentSearchResult(comp, emptyList()) }
            } else {
                allComponents.mapNotNull { comp ->
                    val searchString = searchStrings[comp] ?: return@mapNotNull null
                    if (searchString.contains(query.lowercase())) {
                        val matches = findMatches(comp, query)
                        ComponentSearchResult(comp, matches)
                    } else {
                        null
                    }
                }
            }

        // Вторичная фильтрация по свойствам
        val filtered =
            if (filterValues.isEmpty()) {
                searched
            } else {
                searched.filter { result ->
                    filterValues.all { (key, value) ->
                        if (value.isNullOrBlank()) {
                            true
                        } else {
                            result.component.mainProperties.any { it.key == key && it.value.lowercase().contains(value.lowercase()) }
                        }
                    }
                }
            }

        resultsList.setAll(filtered)

        // Проверка: если выбранный компонент больше не в результатах — сбросить
        selectedComponent?.let { comp ->
            if (resultsList.none { it.component == comp }) {
                resultsTable.selectionModel.clearSelection()
                selectedComponent = null
                rebuildFilterPanel()
            }
        }

        updateStatus()
        updateAddButtonState()
    }

    // ─── Таблица ──────────────────────────────────────────────────

    private fun setupTable() {
        resultsTable.items = resultsList
        resultsTable.selectionModel.selectionMode = javafx.scene.control.SelectionMode.SINGLE

        // Колонка: Имя
        val nameCol = TableColumn<ComponentSearchResult, String>(I18n.tr("bom.dialog.add.col.name") ?: "Имя")
        nameCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.component.name) }
        nameCol.prefWidth = 180.0

        // Колонка: Префикс
        val prefixCol = TableColumn<ComponentSearchResult, String>(I18n.tr("bom.dialog.add.col.prefix") ?: "Преф.")
        prefixCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.component.refPrefix) }
        prefixCol.prefWidth = 50.0

        // Колонка: Описание
        val descCol = TableColumn<ComponentSearchResult, String>(I18n.tr("bom.dialog.add.col.description") ?: "Описание")
        descCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.component.description) }
        descCol.prefWidth = 250.0

        // Колонка: Основные свойства
        val propsCol = TableColumn<ComponentSearchResult, String>(I18n.tr("bom.dialog.add.col.mainProps") ?: "Основные свойства")
        propsCol.setCellValueFactory { cell ->
            val props = cell.value.component.mainProperties
            val text =
                if (props.isEmpty()) {
                    I18n.tr("bom.dialog.add.col.no.props") ?: "—"
                } else {
                    props.joinToString(", ") {
                        if (it.unit.isNotBlank()) {
                            "${it.key}: ${it.value} ${it.unit}"
                        } else {
                            "${it.key}: ${it.value}"
                        }
                    }
                }
            javafx.beans.property.SimpleStringProperty(text)
        }
        propsCol.prefWidth = 300.0
        propsCol.setCellFactory {
            object : TableCell<ComponentSearchResult, String>() {
                private val tooltip = Tooltip()

                override fun updateItem(
                    item: String?,
                    empty: Boolean,
                ) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                        tooltip.text = null
                    } else {
                        text = item
                        graphic = null
                        val comp = tableRow.item?.component
                        if (comp != null && comp.mainProperties.isNotEmpty()) {
                            tooltip.text =
                                comp.mainProperties.joinToString("\n") {
                                    if (it.unit.isNotBlank()) {
                                        "${it.key}: ${it.value} ${it.unit}"
                                    } else {
                                        "${it.key}: ${it.value}"
                                    }
                                }
                        }
                    }
                }
            }
        }

        // Колонка: Пинов
        val portsCol = TableColumn<ComponentSearchResult, String>(I18n.tr("bom.dialog.add.col.ports") ?: "Пинов")
        portsCol.setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.component.ports.size.toString()) }
        portsCol.prefWidth = 50.0

        // Колонка: Совпадения
        val matchCol = TableColumn<ComponentSearchResult, String>(I18n.tr("bom.dialog.add.col.matches") ?: "Совпадения")
        matchCol.setCellValueFactory { cell ->
            val matches = cell.value.matchFields
            javafx.beans.property.SimpleStringProperty(if (matches.isEmpty()) "" else matches.joinToString(", "))
        }
        matchCol.prefWidth = 180.0

        resultsTable.columns.setAll(nameCol, prefixCol, descCol, propsCol, portsCol, matchCol)

        // Двойной клик → добавить
        resultsTable.onMouseClicked =
            javafx.event.EventHandler { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
                    val selection = resultsTable.selectionModel.selectedItem
                    if (selection != null) {
                        confirmSelection(selection)
                    }
                }
            }

        // Выбор строки → перестроить панель фильтров
        resultsTable.selectionModel.selectedItemProperty().addListener { _, _, _ ->
            val selection = resultsTable.selectionModel.selectedItem
            selectedComponent = selection?.component
            rebuildFilterPanel()
            applySearchAndFilters()
        }
    }

    // ─── Панель фильтров (Этап 2) ────────────────────────────────

    private fun rebuildFilterPanel() {
        filterPane.children.clear()
        filterComboBoxes.clear()
        filterValues.clear()

        val comp = selectedComponent ?: return
        if (comp.mainProperties.isEmpty()) return

        filterPane.style = "-fx-padding: 8; -fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;"

        for (prop in comp.mainProperties) {
            val key = prop.key
            val uniqueValues = getUniqueValuesForKey(key)

            val labelRow =
                HBox(8.0, Label("$key:")).apply {
                    alignment = Pos.CENTER_LEFT
                    style = "-fx-font-weight: bold;"
                }

            val combo =
                ComboBox<String>().apply {
                    isEditable = true
                    promptText = I18n.tr("bom.dialog.add.filter.all") ?: "Все"
                    if (uniqueValues.isNotEmpty()) {
                        items.setAll(uniqueValues)
                    }
                    valueProperty().addListener { _, _, _ ->
                        filterValues[key] = this.value
                        applySearchAndFilters()
                    }
                }
            filterComboBoxes[key] = combo

            val filterRow =
                HBox(8.0, labelRow, combo).apply {
                    alignment = Pos.CENTER_LEFT
                }

            filterPane.children.add(filterRow)
        }
    }

    /** Собрать уникальные значения свойства по всем компонентам. */
    private fun getUniqueValuesForKey(key: String): List<String> {
        return allComponents
            .flatMap { it.mainProperties.filter { p -> p.key == key }.map { it.value } }
            .distinct()
            .filter { it.isNotBlank() }
            .sorted()
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
            scene?.onKeyReleased =
                javafx.event.EventHandler { event ->
                    if (event.code == KeyCode.ESCAPE) {
                        stage.close()
                        event.consume()
                    }
                }
        }
    }

    private fun updateStatus() {
        statusLabel.text =
            I18n.tr("bom.dialog.add.found", resultsList.size)
                .replace("{0}", resultsList.size.toString())
    }

    private fun updateAddButtonState() {
        val hasSelection = resultsTable.selectionModel.selectedItem != null
        val selectionInResults = hasSelection && resultsList.any { it.component == selectedComponent }
        (addButton as javafx.scene.control.Button).isDisable = !selectionInResults
    }

    // ─── Добавление в BOM ─────────────────────────────────────────

    private fun confirmSelection(result: ComponentSearchResult) {
        val comp = result.component

        val newRow =
            BomRow(
                bomnetId = java.util.UUID.randomUUID().toString(),
                number = 0,
                refDes = "",
                componentId = comp.id,
                componentSlug = ComponentRepository.generateSlug(comp.name),
                componentName = comp.name,
                comment = comp.mainProperties.firstOrNull { it.key.equals("Номинал", ignoreCase = true) }?.value ?: "",
                refPrefix = comp.refPrefix.uppercase(),
            )

        onConfirm(newRow)
        stage.close()
    }

    // ─── UI Layout ────────────────────────────────────────────────

    private fun createRoot(): BorderPane {
        val root = BorderPane()

        // Top: search bar
        val searchBar =
            HBox(10.0, searchField, clearButton).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(10.0)
                style = "-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;"
            }
        root.top = searchBar

        // Center: table + filter panel
        val center = VBox(0.0)
        val scrollPane =
            ScrollPane(resultsTable).apply {
                hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
                vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
                isFitToWidth = true
            }
        center.children.add(scrollPane)
        center.children.add(filterPane)
        root.center = center

        // Bottom: status + buttons
        val bottomBar =
            HBox(10.0, statusLabel, Region(), addButton, cancelButton).apply {
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

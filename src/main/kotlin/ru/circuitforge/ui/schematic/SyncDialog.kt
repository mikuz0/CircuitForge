package ru.circuitforge.ui.schematic

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Modality
import javafx.stage.Stage
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.BomRow
import ru.circuitforge.project.SchematicNode

/**
 * Результат сравнения схемы с проектом.
 */
data class SyncReport(
    val newRows: List<BomRow>,
    val missingNodes: List<SchematicNode>
)

/**
 * Диалог синхронизации схемы с проектом.
 */
class SyncDialog(
    ownerStage: Stage,
    private val report: SyncReport
) {
    private val stage = Stage()

    // UI элементы
    private val newComponentsList = ListView<String>()
    private val missingComponentsList = ListView<String>()
    private val addNewCheckbox = CheckBox(I18n.tr("schematic.sync.add.new"))
    private val removeMissingCheckbox = CheckBox(I18n.tr("schematic.sync.remove.missing"))
    private val placementNearRadio = RadioButton(I18n.tr("schematic.sync.placement.near"))
    private val placementFreeRadio = RadioButton(I18n.tr("schematic.sync.placement.free"))
    private val placementGroup = ToggleGroup()
    private val syncButton = Button(I18n.tr("schematic.sync.button.sync"))
    private val cancelButton = Button(I18n.tr("schematic.sync.button.cancel"))
    private val statusLabel = Label()

    // Результат (устанавливается в кнопках)
    private var _result: SyncResult? = null

    init {
        stage.initOwner(ownerStage)
        stage.initModality(Modality.APPLICATION_MODAL)
        stage.title = I18n.tr("schematic.sync.title") ?: "Синхронизация схемы"

        addNewCheckbox.selectedProperty().addListener { _, _, _ -> updateSyncButtonState() }
        removeMissingCheckbox.selectedProperty().addListener { _, _, _ -> updateSyncButtonState() }

        placementNearRadio.toggleGroup = placementGroup
        placementFreeRadio.toggleGroup = placementGroup

        setupUI()
        stage.scene = Scene(createRoot(), 600.0, 500.0)
    }

    private fun setupUI() {
        // Список новых компонентов
        report.newRows.forEach { row ->
            newComponentsList.items.add("${row.refDes} (${row.componentName}${if (row.comment.isNotBlank()) ", ${row.comment}" else ""})")
        }

        // Список отсутствующих компонентов
        report.missingNodes.forEach { node ->
            missingComponentsList.items.add("${node.bomnetId} (отсутствует в BOM)")
        }

        // Статус
        val totalChanges = report.newRows.size + report.missingNodes.size
        if (totalChanges == 0) {
            statusLabel.text = I18n.tr("schematic.sync.no.changes") ?: "Схема актуальна"
            (syncButton as Button).isDisable = true
        } else {
            statusLabel.text = I18n.tr("schematic.sync.changes.found", totalChanges)
                .replace("{0}", totalChanges.toString())
        }

        // Чекбоксы
        addNewCheckbox.selectedProperty().setValue(true) // по умолчанию включён
        removeMissingCheckbox.selectedProperty().setValue(false) // по умолчанию выключен
    }

    private fun updateSyncButtonState() {
        val hasChanges = report.newRows.isNotEmpty() || report.missingNodes.isNotEmpty()
        val addChecked = addNewCheckbox.isSelected
        val removeChecked = removeMissingCheckbox.isSelected
        // Размещение нужно только при добавлении
        val placementSelected = !addChecked || (placementNearRadio.isSelected || placementFreeRadio.isSelected)

        (syncButton as Button).isDisable = !hasChanges || (!addChecked && !removeChecked) || !placementSelected
    }

    private fun createRoot(): BorderPane {
        val root = BorderPane()

        // Top: status
        statusLabel.style = "-fx-font-size: 12; -fx-padding: 10;"
        root.top = statusLabel

        // Center: lists and options
        val center = VBox(10.0)

        // Новые компоненты
        val newLabel = Label(I18n.tr("schematic.sync.new.components", report.newRows.size))
            .apply { style = "-fx-font-weight: bold;" }
        val newScrollPane = ScrollPane(newComponentsList).apply {
            prefHeight = 120.0
            prefWidth = 560.0
        }
        val newSection = VBox(5.0, newLabel, newScrollPane).apply {
            padding = Insets(5.0)
            style = "-fx-border-color: #ddd; -fx-border-width: 1;"
        }

        // Отсутствующие компоненты
        val missingLabel = Label(I18n.tr("schematic.sync.missing.components", report.missingNodes.size))
            .apply { style = "-fx-font-weight: bold;" }
        val missingScrollPane = ScrollPane(missingComponentsList).apply {
            prefHeight = 80.0
            prefWidth = 560.0
        }
        val missingSection = VBox(5.0, missingLabel, missingScrollPane).apply {
            padding = Insets(5.0)
            style = "-fx-border-color: #ddd; -fx-border-width: 1;"
        }

        // Чекбоксы
        val checkboxes = VBox(5.0, addNewCheckbox, removeMissingCheckbox).apply {
            padding = Insets(5.0)
        }

        // Размещение
        val placementLabel = Label(I18n.tr("schematic.sync.placement.title"))
            .apply { style = "-fx-font-weight: bold;" }
        val placementOptions = VBox(5.0, placementNearRadio, placementFreeRadio).apply {
            padding = Insets(5.0)
        }
        val placementSection = VBox(5.0, placementLabel, placementOptions).apply {
            padding = Insets(5.0)
            style = "-fx-border-color: #ddd; -fx-border-width: 1;"
        }

        // Добавляем слушатели для radio buttons
        placementNearRadio.selectedProperty().addListener { _, _, _ -> updateSyncButtonState() }
        placementFreeRadio.selectedProperty().addListener { _, _, _ -> updateSyncButtonState() }

        center.children.setAll(newSection, missingSection, checkboxes, placementSection)
        center.alignment = Pos.TOP_CENTER
        root.center = center

        // Bottom: buttons
        val bottomBar = HBox(10.0, syncButton, cancelButton).apply {
            alignment = Pos.CENTER_RIGHT
            padding = Insets(10.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;"
        }
        root.bottom = bottomBar

        // Устанавливаем результат ДО закрытия
        syncButton.setOnAction {
            _result = SyncResult(
                addNew = addNewCheckbox.isSelected,
                removeMissing = removeMissingCheckbox.isSelected,
                placementNear = placementNearRadio.isSelected
            )
            stage.close()
        }
        cancelButton.setOnAction {
            stage.close()
        }

        return root
    }

    /** Результаты выбора пользователя */
    data class SyncResult(
        val addNew: Boolean,
        val removeMissing: Boolean,
        val placementNear: Boolean
    )

    fun showAndWait(): SyncResult? {
        stage.showAndWait()
        return _result
    }
}

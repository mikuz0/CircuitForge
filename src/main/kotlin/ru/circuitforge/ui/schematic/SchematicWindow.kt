package ru.circuitforge.ui.schematic

import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseEvent
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.CubicCurve
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Окно редактора схемы.
 * Контекстная визуализация: линии связей показываются только при нажатии на компонент.
 */
class SchematicWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(SchematicWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "schematic-io").apply { isDaemon = true }
    }
    private var projectPath: Path? = null

    // Данные
    private val bomRows = FXCollections.observableArrayList<BomRow>()
    private val nets = FXCollections.observableArrayList<Net>()
    private val schematicNodes = FXCollections.observableArrayList<SchematicNode>()

    // Map для быстрого доступа
    private val nodesMap = mutableMapOf<String, SchematicNode>()
    private val bomRowsMap = mutableMapOf<String, BomRow>()
    private val componentsMap = mutableMapOf<String, Component>()

    // UI контейнеры — 3 слоя
    private val canvas = Canvas(5000.0, 5000.0)          // задний: сетка
    private val linePane = Pane()                        // средний: кривые Безье
    private val componentPane = Pane()                   // передний: компоненты
    private val mainPane = Pane()
    private val scrollPane = ScrollPane()
    private val saveButton = Button(I18n.tr("bom.btn.save") ?: "Сохранить")
    private val revertButton = Button(I18n.tr("bom.btn.revert") ?: "Отменить")
    private val statusLabel = Label("")

    // Drag & Drop компонентов
    private var dragNode: SchematicNode? = null
    private var dragOffsetX: Double = 0.0
    private var dragOffsetY: Double = 0.0

    // Режим фокуса — активен при нажатии на компонент
    private var focusedNode: SchematicNode? = null

    // Map для хранения Group компонентов (для подсветки портов)
    private val componentGroups = mutableMapOf<String, Group>()

    init {
        stage.title = I18n.tr("schematic.window.title") ?: "Схема"
        setupLayout()
        setupToolbar()
    }

    private fun setupLayout() {
        // Слой компонентов
        componentPane.prefWidth = 5000.0
        componentPane.prefHeight = 5000.0
        componentPane.isManaged = false
        componentPane.isPickOnBounds = false

        // linePane — прозрачный, не перехватывает мышь
        linePane.prefWidth = 5000.0
        linePane.prefHeight = 5000.0
        linePane.isManaged = false
        linePane.isPickOnBounds = false

        // Основной Pane — 3 слоя: сетка (canvas), линии, компоненты
        mainPane.children.setAll(canvas, linePane, componentPane)
        mainPane.prefWidth = 5000.0
        mainPane.prefHeight = 5000.0

        val group = Group(mainPane)
        scrollPane.content = group
        scrollPane.hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.isFitToWidth = false
        scrollPane.isFitToHeight = false

        // Панель инструментов
        val bottomBar = HBox(10.0, saveButton, revertButton, statusLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;"
        }

        val mainLayout = BorderPane().apply {
            center = scrollPane
            bottom = bottomBar
        }

        stage.scene = Scene(mainLayout, 1200.0, 800.0)
    }

    private fun setupToolbar() {
        saveButton.setOnAction { saveSchematic() }
        revertButton.setOnAction { revertChanges() }
    }

    private fun drawSchematic() {
        nodesMap.clear()
        schematicNodes.forEach { node -> nodesMap[node.bomnetId] = node }
        bomRowsMap.clear()
        bomRows.forEach { row -> bomRowsMap[row.bomnetId] = row }
        drawGridOnCanvas()
        drawComponents()
    }

    private fun drawGridOnCanvas() {
        val gc = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height
        gc.fill = Color.WHITE
        gc.fillRect(0.0, 0.0, width, height)

        val minorStep = 12.5
        val majorStep = 50.0

        // Мелкая сетка
        gc.stroke = Color.web("#ececec")
        gc.lineWidth = 0.3
        var x = 0.0
        while (x <= canvas.width) {
            gc.strokeLine(x, 0.0, x, canvas.height)
            x += minorStep
        }
        var y = 0.0
        while (y <= canvas.height) {
            gc.strokeLine(0.0, y, canvas.width, y)
            y += minorStep
        }

        // Основная сетка
        gc.stroke = Color.LIGHTGRAY
        gc.lineWidth = 0.6
        x = 0.0
        while (x <= canvas.width) {
            gc.strokeLine(x, 0.0, x, canvas.height)
            x += majorStep
        }
        y = 0.0
        while (y <= canvas.height) {
            gc.strokeLine(0.0, y, canvas.width, y)
            y += majorStep
        }
    }

    /**
     * Рисует кривые Безье для всех цепей указанного компонента.
     * Контрольные точки смещены перпендикулярно прямой между пинами,
     * чтобы линии обходили компоненты и выглядели красиво.
     */
    private fun drawFocusConnections(node: SchematicNode) {
        linePane.children.clear()

        // Находим все цепи, в которых участвует этот компонент
        val refDes = bomRows.find { it.bomnetId == node.bomnetId }?.refDes ?: return

        nets.forEach { net ->
            val hasComponent = net.connections.any { conn -> conn.refDes == refDes }
            if (!hasComponent) return@forEach

            // Собираем все порты этой цепи
            val connections = net.connections
            val portCoords = mutableListOf<Pair<String, Pair<Double, Double>>>() // (refDes+pin, coords)

            connections.forEach { conn ->
                val row = bomRows.find { it.refDes == conn.refDes } ?: return@forEach
                val coords = PortCalculator.getPortCoordinates(nodesMap, componentsMap, row, conn.pinNumber)
                if (coords != null) {
                    portCoords.add("${conn.refDes}:${conn.pinNumber}" to coords)
                }
            }

            if (portCoords.size < 2) return@forEach

            // Цвет цепи
            val netColor = netColor(net.name)

            // Рисуем кривые Безье: от фокусируемого компонента ко всем остальным
            val focusPorts = portCoords.filter { it.first.startsWith("$refDes:") }
            val otherPorts = portCoords.filter { !it.first.startsWith("$refDes:") }

            if (focusPorts.isEmpty() || otherPorts.isEmpty()) return@forEach

            // Для каждого пина фокусируемого компонента — кривая к каждому другому пину
            focusPorts.forEach { focusPort ->
                val focusStart = focusPort.second

                otherPorts.forEach { otherPort ->
                    val otherEnd = otherPort.second

                    // Вычисляем контрольные точки для кривой Безье
                    val cp1 = controlPoint(focusStart, otherEnd, offset = 60.0)
                    val cp2 = controlPoint(otherEnd, focusStart, offset = 60.0)

                    val curve = CubicCurve(
                        focusStart.first, focusStart.second,
                        cp1.first, cp1.second,
                        cp2.first, cp2.second,
                        otherEnd.first, otherEnd.second
                    ).apply {
                        stroke = netColor
                        fill = null
                        strokeWidth = 2.0
                        opacity = 0.7
                    }
                    linePane.children.add(curve)
                }
            }
        }
    }

    /**
     * Вычисляет контрольную точку для кривой Безье.
     * Смещаем перпендикулярно прямой между start и end на offset пикселей.
     * Это создаёт плавный изгиб, который обходит компоненты.
     */
    private fun controlPoint(start: Pair<Double, Double>, end: Pair<Double, Double>, offset: Double): Pair<Double, Double> {
        val dx = end.first - start.first
        val dy = end.second - start.second
        val length = kotlin.math.hypot(dx, dy)
        if (length == 0.0) return Pair(start.first, start.second)

        // Нормаль (перпендикуляр) к направлению
        val nx = -dy / length
        val ny = dx / length

        // Контрольная точка на 1/3 пути + смещение по нормали
        val t = 0.33
        val midX = start.first + dx * t
        val midY = start.second + dy * t

        return Pair(midX + nx * offset, midY + ny * offset)
    }

    /** Генерирует стабильный цвет из имени цепи. */
    private fun netColor(name: String): Color {
        val hash = name.hashCode()
        val hue = (hash % 360 + 360) % 360.0 / 360.0
        return Color.hsb(hue * 360, 0.7, 0.8)
    }

    private fun drawComponents() {
        componentPane.children.clear()
        linePane.children.clear()
        componentGroups.clear()

        bomRowsMap.values.forEach { row ->
            val node = nodesMap[row.bomnetId] ?: return@forEach
            val comp = componentsMap[row.componentId] ?: return@forEach
            val group = Group()

            // Используем ComponentRenderer
            when (comp.name.lowercase()) {
                "resistor" -> ComponentRenderer.drawCustomResistor(group, row, comp)
                "capacitor" -> ComponentRenderer.drawCustomCapacitor(group, row, comp)
                else -> ComponentRenderer.drawDefaultComponent(group, row, comp)
            }

            group.layoutX = node.x
            group.layoutY = node.y
            group.rotate = node.rotation

            // Обработчики мыши для контекстной визуализации
            group.setOnMousePressed { event ->
                // Активируем режим фокуса
                focusedNode = node
                drawFocusConnections(node)

                // Начинаем перетаскивание
                dragNode = node
                dragOffsetX = event.sceneX - group.layoutX
                dragOffsetY = event.sceneY - group.layoutY
                group.toFront()
                event.consume()
            }

            group.setOnMouseDragged { event ->
                val currentNode = dragNode
                if (currentNode != null) {
                    val rawX = event.sceneX - dragOffsetX
                    val rawY = event.sceneY - dragOffsetY

                    val gridStep = 12.5
                    val snappedX = kotlin.math.round(rawX / gridStep) * gridStep
                    val snappedY = kotlin.math.round(rawY / gridStep) * gridStep

                    val updatedNode = currentNode.copy(x = snappedX, y = snappedY, rotation = currentNode.rotation)
                    nodesMap[currentNode.bomnetId] = updatedNode
                    val idx = schematicNodes.indexOfFirst { it.bomnetId == currentNode.bomnetId }
                    if (idx >= 0) schematicNodes[idx] = updatedNode

                    group.layoutX = snappedX
                    group.layoutY = snappedY

                    // Если фокус активен — обновляем линии
                    if (focusedNode != null) {
                        drawFocusConnections(focusedNode!!)
                    }
                }
                event.consume()
            }

            group.setOnMouseReleased { event ->
                // Деактивируем режим фокуса
                focusedNode = null
                linePane.children.clear()
                dragNode = null
                saveSchematicAsync()
                event.consume()
            }

            componentPane.children.add(group)
            componentGroups[row.bomnetId] = group
        }
    }

    private fun generateGridCoordinates(rows: List<BomRow>): List<SchematicNode> {
        val nodes = mutableListOf<SchematicNode>()
        val columns = 4
        val stepX = 250.0
        val stepY = 200.0
        rows.forEachIndexed { index, row ->
            val col = index % columns
            val rowIdx = index / columns
            nodes.add(
                SchematicNode(
                    bomnetId = row.bomnetId,
                    x = 100.0 + col * stepX,
                    y = 100.0 + rowIdx * stepY,
                    rotation = 0.0
                )
            )
        }
        return nodes
    }

    private fun saveSchematic() {
        val task = object : Task<Unit>() {
            override fun call() {
                projectPath?.let { dir ->
                    val doc = SchematicDocument(
                        schemaVersion = 1,
                        nodes = schematicNodes.toList()
                    )
                    SchematicRepository.save(dir, doc)
                }
            }

            override fun succeeded() {
                javafx.application.Platform.runLater {
                    statusLabel.text = I18n.tr("net.status.saved") ?: "Сохранено"
                    statusLabel.style = "-fx-text-fill: green;"
                    logger.info("Schematic saved")
                }
            }

            override fun failed() {
                javafx.application.Platform.runLater {
                    val ex = exception
                    UiUtils.errorAlert(
                        I18n.tr("common.alert.error") ?: "Ошибка",
                        ex?.message ?: "Unknown error"
                    )
                    logger.error("Failed to save schematic", ex)
                }
            }
        }
        executor.submit(task)
    }

    private fun saveSchematicAsync() {
        val task = object : Task<Unit>() {
            override fun call() {
                projectPath?.let { dir ->
                    val doc = SchematicDocument(
                        schemaVersion = 1,
                        nodes = schematicNodes.toList()
                    )
                    SchematicRepository.save(dir, doc)
                }
            }

            override fun succeeded() {
                logger.info("Schematic auto-saved after drag")
            }

            override fun failed() {
                logger.error("Failed to auto-save schematic", exception)
            }
        }
        executor.submit(task)
    }

    private fun revertChanges() {
        projectPath?.let { dir ->
            val doc = SchematicRepository.load(dir)
            schematicNodes.setAll(doc.nodes)
            drawSchematic()
            statusLabel.text = I18n.tr("net.status.reverted") ?: "Отменено"
            statusLabel.style = "-fx-text-fill: blue;"
            logger.info("Schematic reverted")
        }
    }

    override fun showWindow() = stage.show()

    override fun openProject(projectPath: Path) {
        this.projectPath = projectPath
        stage.title = "CircuitForge — Schematic — $projectPath"

        val loadTask = object : Task<Unit>() {
            override fun call() {
                val rows = BomRepository.loadRows(projectPath)
                bomRows.setAll(rows)
                bomRowsMap.clear()
                rows.forEach { row -> bomRowsMap[row.bomnetId] = row }

                componentsMap.clear()
                rows.forEach { row ->
                    try {
                        val comp = ComponentRepository.loadComponentById(projectPath, row.componentId)
                        if (comp != null) componentsMap[row.componentId] = comp
                    } catch (e: Exception) {
                        logger.warn("Failed to load component for row ${row.refDes}", e)
                    }
                }

                val netDoc = NetRepository.load(projectPath)
                nets.setAll(netDoc.nets)

                val existingDoc = SchematicRepository.load(projectPath)
                if (existingDoc.nodes.isEmpty()) {
                    logger.info("No schematic data found, generating grid layout")
                    val nodes = generateGridCoordinates(rows)
                    SchematicRepository.save(projectPath, SchematicDocument(schemaVersion = 1, nodes = nodes))
                    schematicNodes.setAll(nodes)
                } else {
                    schematicNodes.setAll(existingDoc.nodes)
                }
            }

            override fun succeeded() {
                javafx.application.Platform.runLater {
                    drawSchematic()
                    statusLabel.text = "Загружено: ${bomRows.size} компонентов, ${nets.size} цепей"
                    logger.info("Schematic opened: ${bomRows.size} components, ${nets.size} nets")
                }
            }

            override fun failed() {
                javafx.application.Platform.runLater {
                    val ex = exception
                    UiUtils.errorAlert(
                        I18n.tr("common.alert.error") ?: "Ошибка",
                        ex?.message ?: "Unknown error"
                    )
                    logger.error("Failed to open schematic", ex)
                }
            }
        }
        executor.submit(loadTask)
    }

    override fun getStage(): Stage = stage
}

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
import javafx.scene.input.MouseEvent
import javafx.scene.layout.*
import javafx.scene.paint.Color
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
 * Отвечает только за UI и координацию между компонентами.
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
    private val netPaths = FXCollections.observableArrayList<NetPath>()

    // Map для быстрого доступа
    private val nodesMap = mutableMapOf<String, SchematicNode>()
    private val bomRowsMap = mutableMapOf<String, BomRow>()
    private val componentsMap = mutableMapOf<String, Component>()

    // UI контейнеры
    private val canvas = Canvas(5000.0, 5000.0)
    private val componentPane = Pane()
    private val mainPane = Pane()
    private val scrollPane = ScrollPane()
    private val saveButton = Button(I18n.tr("bom.btn.save") ?: "Сохранить")
    private val revertButton = Button(I18n.tr("bom.btn.revert") ?: "Отменить")
    private val statusLabel = Label("")

    // Drag & Drop компонентов
    private var dragNode: SchematicNode? = null
    private var dragOffsetX: Double = 0.0
    private var dragOffsetY: Double = 0.0

    // Редактор проводов (делегирование)
    private val wireEditor: WireEditor by lazy {
        WireEditor(
            nets = nets,
            netPaths = netPaths,
            bomRows = bomRows,
            nodesMap = nodesMap,
            componentsMap = componentsMap,
            projectPath = projectPath,
            executor = executor,
            onSave = { drawLines(); saveSchematicAsync() }
        )
    }

    init {
        stage.title = I18n.tr("schematic.window.title") ?: "Схема"
        setupLayout()
        setupToolbar()
    }

    private fun setupLayout() {
        // Обработчики мыши на Canvas делегируем в WireEditor
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED)  { wireEditor.handleMousePressed(it) }
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED)  { wireEditor.handleMouseDragged(it) }
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED) { wireEditor.handleMouseReleased(it) }

        // Слой компонентов
        componentPane.prefWidth = 5000.0
        componentPane.prefHeight = 5000.0
        componentPane.isManaged = false
        componentPane.isPickOnBounds = false

        // Основной Pane
        mainPane.children.setAll(canvas, componentPane)
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
        wireEditor.reset()
        drawLines()
        drawComponents()
    }

    private fun drawLines() {
        val gc = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height
        gc.fill = Color.WHITE
        gc.fillRect(0.0, 0.0, width, height)
        drawGrid(gc)
        drawConnections(gc)
    }

    private fun drawGrid(gc: GraphicsContext) {
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

    private fun drawConnections(gc: GraphicsContext) {
        gc.lineWidth = 2.0
        gc.stroke = Color.web("#2196F3")

        // 1. Пользовательские пути (с waypoints)
        netPaths.forEach { netPath ->
            val net = nets.find { it.name == netPath.netName } ?: return@forEach
            if (netPath.waypoints.isEmpty()) return@forEach

            val pinPoints = getNetPoints(net)
            if (pinPoints.isNotEmpty()) {
                // От первого пина до первой точки излома
                val firstPin = pinPoints[0]
                val firstWP = netPath.waypoints[0]
                drawOrthogonalLine(gc, firstPin, Pair(firstWP.x, firstWP.y))

                // Между точками излома
                for (i in 1 until netPath.waypoints.size) {
                    val prev = netPath.waypoints[i - 1]
                    val curr = netPath.waypoints[i]
                    drawOrthogonalLine(gc, Pair(prev.x, prev.y), Pair(curr.x, curr.y))
                }

                // От последней точки излома до последнего пина
                if (pinPoints.size > 1) {
                    val lastPin = pinPoints.last()
                    val lastWP = netPath.waypoints.last()
                    drawOrthogonalLine(gc, Pair(lastWP.x, lastWP.y), lastPin)
                }
            }
        }

        // 2. Рисуем маркеры точек излома ОДИН РАЗ для всех путей (ВЫНЕСЕНО ИЗ ЦИКЛА!)
        wireEditor.drawWaypointMarkers(gc, netPaths)

        // 3. Автоматические пути (без пользовательских waypoints)
        nets.forEach { net ->
            // Пропускаем, если для этой цепи уже есть пользовательский путь
            if (netPaths.any { it.netName == net.name }) return@forEach
            if (net.connections.isEmpty()) return@forEach

            val points = getNetPoints(net)
            if (points.size >= 2) {
                // Рисуем линии от первого пина ко всем остальным (звездообразная топология)
                val startPoint = points[0]
                for (i in 1 until points.size) {
                    val endPoint = points[i]
                    drawOrthogonalLine(gc, startPoint, endPoint)
                }
            } else if (points.size == 1) {
                drawConnectionDot(gc, points[0])
            }
        }
    }

    private fun getNetPoints(net: Net): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        net.connections.forEach { conn ->
            val row = bomRows.find { it.refDes == conn.refDes }
            if (row != null) {
                val coords = wireEditor.getPortCoordinates(row, conn.pinNumber)
                if (coords != null) {
                    points.add(coords)
                }
            }
        }
        return points
    }

    private fun drawOrthogonalLine(gc: GraphicsContext, start: Pair<Double, Double>, end: Pair<Double, Double>) {
        val (x1, y1) = start
        val (x2, y2) = end
        gc.strokeLine(x1, y1, x2, y1)
        gc.strokeLine(x2, y1, x2, y2)
    }

    private fun drawConnectionDot(gc: GraphicsContext, point: Pair<Double, Double>) {
        gc.fill = Color.web("#2196F3")
        gc.fillOval(point.first - 4.0, point.second - 4.0, 8.0, 8.0)
    }

    private fun drawComponents() {
        componentPane.children.clear()
        bomRowsMap.values.forEach { row ->
            val node = nodesMap[row.bomnetId] ?: return@forEach
            val comp = componentsMap[row.componentId] ?: return@forEach
            val group = Group()

            // Используем ComponentRenderer вместо дублирования кода
            when (comp.name.lowercase()) {
                "resistor" -> ComponentRenderer.drawCustomResistor(group, row, comp)
                "capacitor" -> ComponentRenderer.drawCustomCapacitor(group, row, comp)
                else -> ComponentRenderer.drawDefaultComponent(group, row, comp)
            }

            group.layoutX = node.x
            group.layoutY = node.y

            // Drag & Drop компонентов
            group.setOnMousePressed { event ->
                dragNode = node
                dragOffsetX = event.sceneX - group.layoutX
                dragOffsetY = event.sceneY - group.layoutY
                group.toFront()
            }
            group.setOnMouseDragged { event ->
                val currentNode = dragNode
                if (currentNode != null) {
                    val newX = event.sceneX - dragOffsetX
                    val newY = event.sceneY - dragOffsetY
                    val updatedNode = currentNode.copy(x = newX, y = newY)
                    nodesMap[currentNode.bomnetId] = updatedNode
                    val idx = schematicNodes.indexOfFirst { it.bomnetId == currentNode.bomnetId }
                    if (idx >= 0) schematicNodes[idx] = updatedNode
                    group.layoutX = newX
                    group.layoutY = newY
                    drawLines()
                }
            }
            group.setOnMouseReleased {
                dragNode = null
                saveSchematicAsync()
            }
            componentPane.children.add(group)
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
                    y = 100.0 + rowIdx * stepY
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
                        nodes = schematicNodes.toList(),
                        paths = netPaths.toList()
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
                        nodes = schematicNodes.toList(),
                        paths = netPaths.toList()
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
            netPaths.setAll(doc.paths)
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

                netPaths.setAll(existingDoc.paths)
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

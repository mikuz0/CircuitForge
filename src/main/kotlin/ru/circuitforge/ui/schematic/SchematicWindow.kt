package ru.circuitforge.ui.schematic

import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.Cursor
import javafx.scene.input.MouseEvent
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.CubicCurve
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import ru.circuitforge.ui.schematic.layout.ElkLayoutEngine
import ru.circuitforge.ui.schematic.symbols.SymbolRegistry
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
    private var projectPaths: ProjectPaths? = null

    // Данные
    private val bomRows = FXCollections.observableArrayList<BomRow>()
    private val nets = FXCollections.observableArrayList<Net>()
    private val schematicNodes = FXCollections.observableArrayList<SchematicNode>()
    private val schematicWires = FXCollections.observableArrayList<WireConnection>()

    // Map для быстрого доступа
    private val nodesMap = mutableMapOf<String, SchematicNode>()
    private val bomRowsMap = mutableMapOf<String, BomRow>()
    private val componentsMap = mutableMapOf<String, Component>()

    // UI контейнеры — 5 слоёв
    private val canvas = Canvas(5000.0, 5000.0)          // задний: сетка
    private val wirePane = Pane()                        // слой для проводов из ELK (Polyline)
    private val linePane = Pane()                        // средний: кривые Безье
    private val portPane = Pane()                        // слой для кружочков портов
    private val componentPane = Pane()                   // передний: компоненты
    private val mainPane = Pane()
    private val scrollPane = ScrollPane()
    private val saveButton = Button(I18n.tr("bom.btn.save") ?: "Сохранить")
    private val revertButton = Button(I18n.tr("bom.btn.revert") ?: "Отменить")
    private val syncButton = Button(I18n.tr("schematic.btn.sync") ?: "Синхронизировать")
    private val autoLayoutButton = Button(I18n.tr("schematic.btn.auto_layout") ?: "Авто-раскладка")
    private val statusLabel = Label("")

    // Drag & Drop компонентов
    private var dragNode: SchematicNode? = null
    private var dragOffsetX: Double = 0.0
    private var dragOffsetY: Double = 0.0

    // Активные порты: Set<"refDes:pinNumber">
    private val activePorts = mutableSetOf<String>()

    // Map для хранения Group компонентов
    private val componentGroups = mutableMapOf<String, Group>()

    // Map кружочков портов: "refDes:pinNumber" -> Circle
    private val portCircles = mutableMapOf<String, Circle>()

    /** Ключ порта: "refDes:pinNumber" */
    private fun portKey(refDes: String, pinNumber: String) = "$refDes:$pinNumber"

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

        // Слой линий
        linePane.prefWidth = 5000.0
        linePane.prefHeight = 5000.0
        linePane.isManaged = false
        linePane.isPickOnBounds = false

        // Слой проводов из ELK
        wirePane.prefWidth = 5000.0
        wirePane.prefHeight = 5000.0
        wirePane.isManaged = false
        wirePane.isPickOnBounds = false

        // Слой кружочков
        portPane.prefWidth = 5000.0
        portPane.prefHeight = 5000.0
        portPane.isManaged = false
        portPane.isPickOnBounds = false

        // Основной Pane — 5 слоёв: сетка (canvas), провода ELK (wirePane), линии (linePane), порты, компоненты
        mainPane.children.setAll(canvas, wirePane, linePane, portPane, componentPane)
        mainPane.prefWidth = 5000.0
        mainPane.prefHeight = 5000.0

        val group = Group(mainPane)
        scrollPane.content = group
        scrollPane.hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.isFitToWidth = false
        scrollPane.isFitToHeight = false

        // Панель инструментов
        val bottomBar = HBox(10.0, saveButton, revertButton, syncButton, autoLayoutButton, statusLabel).apply {
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
        syncButton.setOnAction { openSyncDialog() }
        autoLayoutButton.setOnAction { runAutoLayout() }
    }

    private fun drawSchematic() {
        nodesMap.clear()
        schematicNodes.forEach { node -> nodesMap[node.bomnetId] = node }
        bomRowsMap.clear()
        bomRows.forEach { row -> bomRowsMap[row.bomnetId] = row }
        drawGridOnCanvas()
        drawComponents()
        drawAllWires()
        drawAllPorts() // <-- Рисуем кружочки один раз
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
     * Рисует все кружочки портов на слое portPane.
     */
    private fun drawAllPorts() {
        portPane.children.clear()
        portCircles.clear()

        bomRowsMap.values.forEach { row ->
            val node = nodesMap[row.bomnetId] ?: return@forEach
            val comp = componentsMap[row.componentId] ?: return@forEach
            val connectedPorts = getConnectedPorts(row.refDes, comp)

            comp.ports.forEach { port ->
                val key = portKey(row.refDes, port.pinNumber)
                if (!connectedPorts.contains(key)) return@forEach

                val coords = PortCalculator.getPortCoordinates(nodesMap, componentsMap, row, port.pinNumber)
                if (coords != null) {
                    val circle = Circle(coords.first, coords.second, 5.0).apply {
                        fill = if (activePorts.contains(key)) Color.web("#F44336") else Color.web("#2196F3")
                        stroke = Color.web("#1976D2")
                        strokeWidth = 1.5
                        cursor = Cursor.HAND

                        setOnMousePressed { event ->
                            if (activePorts.contains(key)) {
                                activePorts.remove(key)
                            } else {
                                activePorts.add(key)
                            }
                            drawAllPorts() // перерисовать цвет
                            drawPortConnections(activePorts.toSet())
                            event.consume()
                        }
                        setOnMouseEntered {
                            if (!activePorts.contains(key)) this.fill = Color.web("#64B5F6")
                        }
                        setOnMouseExited {
                            if (!activePorts.contains(key)) this.fill = Color.web("#2196F3")
                        }
                    }
                    portPane.children.add(circle)
                    portCircles[key] = circle
                }
            }
        }
    }

    /**
     * Рисует кривые Безье для всех активных портов.
     */
    private fun drawPortConnections(activePorts: Set<String>) {
        linePane.children.clear()

        activePorts.forEach { activeKey ->
            val (activeRefDes, activePin) = activeKey.split(":", limit = 2)

            nets.forEach { net ->
                val hasActivePort = net.connections.any { conn ->
                    conn.refDes == activeRefDes && conn.pinNumber == activePin
                }
                if (!hasActivePort) return@forEach

                // Собираем координаты ТОЛЬКО для найденных портов
                val portCoords = mutableListOf<Pair<String, Pair<Double, Double>>>()
                net.connections.forEach { conn ->
                    val coords = getPinCoordinates(conn.refDes, conn.pinNumber)
                    if (coords != null) {
                        portCoords.add(portKey(conn.refDes, conn.pinNumber) to coords)
                    }
                }

                // Рисуем линии только если найдено минимум 2 точки
                if (portCoords.size < 2) return@forEach

                val netColor = netColor(net.name)
                val activePortKey = portKey(activeRefDes, activePin)
                val activePortCoord = portCoords.find { it.first == activePortKey }
                if (activePortCoord == null) return@forEach

                val start = activePortCoord.second

                portCoords.forEach { (portK, portC) ->
                    if (portK == activePortKey) return@forEach

                    val end = portC
                    val cp1 = controlPoint(start, end, offset = 60.0)
                    val cp2 = controlPoint(end, start, offset = 60.0)

                    val curve = CubicCurve(
                        start.first, start.second,
                        cp1.first, cp1.second,
                        cp2.first, cp2.second,
                        end.first, end.second
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

        // Рисуем метки для всех цепей с активными портами и labelMode
        drawNetLabelsForActivePorts(activePorts)
    }

    private fun controlPoint(start: Pair<Double, Double>, end: Pair<Double, Double>, offset: Double): Pair<Double, Double> {
        val dx = end.first - start.first
        val dy = end.second - start.second
        val length = kotlin.math.hypot(dx, dy)
        if (length == 0.0) return Pair(start.first, start.second)

        val nx = -dy / length
        val ny = dx / length

        val t = 0.33
        val midX = start.first + dx * t
        val midY = start.second + dy * t

        return Pair(midX + nx * offset, midY + ny * offset)
    }

    /**
     * Рисует все провода из ELK (WireConnection с bendPoints) на wirePane.
     */
    private fun drawAllWires() {
        wirePane.children.clear()

        if (schematicWires.isEmpty()) return

        schematicWires.forEach { wire ->
            val netColor = netColor(wire.netName)
            val startCoord = getPinCoordinates(wire.refDesFrom, wire.pinFrom)
            val endCoord = getPinCoordinates(wire.refDesTo, wire.pinTo)

            if (startCoord == null || endCoord == null) return@forEach

            val coords = mutableListOf<Double>().apply {
                add(startCoord.first)
                add(startCoord.second)
                wire.bendPoints.forEach { bp ->
                    add(bp.x)
                    add(bp.y)
                }
                add(endCoord.first)
                add(endCoord.second)
            }

            if (coords.size >= 4) {
                val polyline = javafx.scene.shape.Polyline().apply {
                    this.points.setAll(coords)
                    stroke = netColor
                    fill = null
                    strokeWidth = 2.0
                    opacity = 0.7
                }
                wirePane.children.add(polyline)
            }
        }
    }

    /**
     * Получает координаты пина по refDes и pinNumber.
     */
    private fun getPinCoordinates(refDes: String, pinNumber: String): Pair<Double, Double>? {
        val key = portKey(refDes, pinNumber)
        val circle = portCircles[key] ?: return null
        // Circle создаётся с координатами в centerX/centerY
        return Pair(circle.centerX, circle.centerY)
    }

    /**
     * Рисует текстовые метки у каждого пина для цепей с labelMode.
     */
    private fun drawNetLabelsForActivePorts(activePorts: Set<String>) {
        // Находим цепи, которые содержат активные порты И включённую метку
        val activeNets = nets.filter { net ->
            net.labelMode && net.connections.any { conn ->
                activePorts.contains(portKey(conn.refDes, conn.pinNumber))
            }
        }

        activeNets.forEach { net ->
            // Рисуем метки только для найденных пинов
            val drawnPins = mutableListOf<String>()
            net.connections.forEach { conn ->
                val pinCoords = getPinCoordinates(conn.refDes, conn.pinNumber) ?: return@forEach
                val row = bomRows.find { it.refDes == conn.refDes } ?: return@forEach
                val comp = componentsMap[row.componentId] ?: return@forEach
                val node = nodesMap[row.bomnetId] ?: return@forEach

                val direction = getPinDirection(comp, conn.pinNumber, node)

                val offset = 12.0
                val fontSize = 10.0
                val netColorObj = netColor(net.name)
                val colorCss = String.format("#%02X%02X%02X", (netColorObj.red * 255).toInt(), (netColorObj.green * 255).toInt(), (netColorObj.blue * 255).toInt())

                val label = Label(net.name).apply {
                    style = "-fx-font-size: $fontSize; -fx-font-family: SansSerif; -fx-text-fill: $colorCss;"
                    layoutX = when (direction) {
                        PinDirection.LEFT -> pinCoords.first - offset - 40
                        PinDirection.RIGHT -> pinCoords.first + offset
                        PinDirection.TOP, PinDirection.BOTTOM -> pinCoords.first - 30
                    }
                    layoutY = when (direction) {
                        PinDirection.LEFT, PinDirection.RIGHT -> pinCoords.second - 5
                        PinDirection.TOP -> pinCoords.second - offset - 10
                        PinDirection.BOTTOM -> pinCoords.second + offset
                    }
                }
                linePane.children.add(label)
                drawnPins.add(portKey(conn.refDes, conn.pinNumber))
            }
            // Метки рисуются даже если не все пины найдены
        }
    }

    /**
     * Определяет направление пина компонента.
     */

    private fun getPinDirection(comp: Component, pinNumber: String, node: SchematicNode): PinDirection {
        val registry = SymbolRegistry.getInstance()
        val portPos = registry.getPortPosition(comp.name, pinNumber) ?: return PinDirection.RIGHT
        val (symW, symH) = registry.getSymbolSize(comp.name)
        val (px, py) = portPos
        val dx = px - symW / 2.0
        val dy = py - symH / 2.0
        return when {
            kotlin.math.abs(dx) >= kotlin.math.abs(dy) ->
                if (dx < 0) PinDirection.LEFT else PinDirection.RIGHT
            else ->
                if (dy < 0) PinDirection.TOP else PinDirection.BOTTOM
        }
    }

    private fun netColor(name: String): Color {
        val hash = name.hashCode()
        val hue = (hash % 360 + 360) % 360.0 / 360.0
        return Color.hsb(hue * 360, 0.7, 0.8)
    }

    private fun getConnectedPorts(refDes: String, comp: Component): Set<String> {
        val connected = mutableSetOf<String>()
        // Получаем список допустимых номеров пинов для этого компонента
        val validPinNumbers = comp.ports.map { it.pinNumber }.toSet()
        
        nets.forEach { net ->
            net.connections.filter { it.refDes == refDes }.forEach { conn ->
                // Добавляем только если пин существует в компоненте
                if (conn.pinNumber in validPinNumbers) {
                    connected.add(portKey(refDes, conn.pinNumber))
                }
            }
        }
        return connected
    }

    private fun drawComponents() {
        componentPane.children.clear()
        linePane.children.clear()
        componentGroups.clear()

        bomRowsMap.values.forEach { row ->
            val node = nodesMap[row.bomnetId] ?: return@forEach
            val comp = componentsMap[row.componentId] ?: return@forEach
            val group = Group()

            ComponentRenderer.drawComponent(group, row, comp)

            group.layoutX = node.x
            group.layoutY = node.y
            group.rotate = node.rotation

            // Обработчики мыши на группе компонента
            group.setOnMousePressed { event ->
                // Проверяем, не клик ли был на кружочке
                val target = event.target
                val isPortCircle = portCircles.values.any { it == target }

                if (!isPortCircle) {
                    // Активируем все порты компонента
                    val connectedPorts = getConnectedPorts(row.refDes, comp)
                    activePorts.addAll(connectedPorts)
                    drawAllPorts()
                    drawPortConnections(activePorts.toSet())
                }
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

                    // Перерисовываем кружочки и линии
                    drawAllPorts()
                    drawPortConnections(activePorts.toSet())
                    drawAllWires()
                }
                event.consume()
            }

            group.setOnMouseReleased { event ->
                // Деактивируем порты этого компонента
                val connectedPorts = getConnectedPorts(row.refDes, comp)
                activePorts.removeAll(connectedPorts)
                drawAllPorts()
                drawPortConnections(activePorts.toSet())
                drawAllWires()
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
                projectPaths?.let { paths ->
                    val doc = SchematicDocument(schemaVersion = 1, nodes = schematicNodes.toList(), wires = schematicWires.toList())
                    SchematicRepository.save(paths, doc)
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
                projectPaths?.let { paths ->
                    val doc = SchematicDocument(
                        schemaVersion = 1,
                        nodes = schematicNodes.toList(),
                        wires = schematicWires.toList()
                    )
                    SchematicRepository.save(paths, doc)
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
        projectPaths?.let { paths ->
            val doc = SchematicRepository.load(paths)
            schematicNodes.setAll(doc.nodes)
            drawSchematic()
            statusLabel.text = I18n.tr("net.status.reverted") ?: "Отменено"
            statusLabel.style = "-fx-text-fill: blue;"
            logger.info("Schematic reverted")
        }
    }

    /** Запуск авто-раскладки ELK */
    private fun runAutoLayout() {
        if (bomRows.isEmpty() || componentsMap.isEmpty()) {
            UiUtils.errorAlert(
                I18n.tr("common.alert.error") ?: "Ошибка",
                I18n.tr("schematic.error.no_components") ?: "Нет компонентов для раскладки"
            )
            return
        }

        statusLabel.text = I18n.tr("schematic.status.layout_running") ?: "Выполняется раскладка..."
        statusLabel.style = "-fx-text-fill: orange;"

        val task = object : Task<Unit>() {
            override fun call() {
                try {
                    val engine = ElkLayoutEngine()
                    val document = engine.layout(bomRows.toList(), nets.toList(), componentsMap)

                    javafx.application.Platform.runLater {
                        schematicNodes.setAll(document.nodes)
                        schematicWires.setAll(document.wires)
                        drawSchematic()
                        statusLabel.text = I18n.tr("schematic.status.layout_done")?.replace("{0}", document.nodes.size.toString())
                            ?: "Раскладка завершена: ${document.nodes.size} компонентов"
                        statusLabel.style = "-fx-text-fill: green;"
                        logger.info("ELK auto-layout completed: ${document.nodes.size} components")
                    }
                } catch (e: Exception) {
                    logger.error("ELK auto-layout failed", e)
                    javafx.application.Platform.runLater {
                        UiUtils.errorAlert(
                            I18n.tr("common.alert.error") ?: "Ошибка",
                            I18n.tr("schematic.error.layout_failed")?.replace("{0}", e.message ?: "Unknown")
                                ?: "Ошибка раскладки: ${e.message}"
                        )
                        statusLabel.text = ""
                    }
                }
            }
        }
        executor.submit(task)
    }

    /** Открыть диалог синхронизации */
    private fun openSyncDialog() {
        val paths = projectPaths ?: return

        // Загружаем актуальные данные
        val newBomRows = try {
            BomRepository.loadRows(paths)
        } catch (e: Exception) {
            logger.error("Failed to load BOM for sync", e)
            UiUtils.errorAlert(
                I18n.tr("common.alert.error") ?: "Ошибка",
                I18n.tr("schematic.sync.error.load.bom") ?: "Не удалось загрузить BOM: ${e.message}"
            )
            return
        }

        if (newBomRows.isEmpty()) {
            UiUtils.errorAlert(
                I18n.tr("common.alert.error") ?: "Ошибка",
                I18n.tr("schematic.sync.error.empty.bom") ?: "BOM пуст. Сначала создайте BOM."
            )
            return
        }

        // Сравниваем
        val currentBomnetIds = schematicNodes.map { it.bomnetId }.toSet()
        val newBomnetIds = newBomRows.map { it.bomnetId }.toSet()

        val newRows = newBomRows.filter { it.bomnetId !in currentBomnetIds }
        val missingNodes = schematicNodes.filter { it.bomnetId !in newBomnetIds }

        val report = SyncReport(newRows, missingNodes)

        // Если нет изменений — показываем сообщение
        if (report.newRows.isEmpty() && report.missingNodes.isEmpty()) {
            val alert = Alert(Alert.AlertType.INFORMATION)
            alert.title = I18n.tr("schematic.sync.title") ?: "Синхронизация схемы"
            alert.headerText = I18n.tr("schematic.sync.no.changes") ?: "Схема актуальна"
            alert.contentText = I18n.tr("schematic.sync.no.changes.message") ?: "Нет изменений для синхронизации."
            alert.showAndWait()
            return
        }

        // Открываем диалог
        val dialog = SyncDialog(stage, report)
        val result = dialog.showAndWait() ?: return

        // Применяем изменения
        syncWithProject(result, paths, newBomRows, report)
    }

    /** Применить синхронизацию */
    private fun syncWithProject(result: SyncDialog.SyncResult, paths: ProjectPaths, newBomRows: List<BomRow>, report: SyncReport) {
        val task = object : Task<Unit>() {
            override fun call() {
                val currentNodes = schematicNodes.toList()
                val currentBomnetIds = currentNodes.map { it.bomnetId }.toSet()

                // Загружаем актуальные компоненты для размещения
                val componentsMap = mutableMapOf<String, Component>()
                newBomRows.forEach { row ->
                    try {
                        val comp = ComponentRepository.loadComponentById(paths.projectDir, row.componentId)
                        if (comp != null) componentsMap[row.componentId] = comp
                    } catch (e: Exception) {
                        logger.warn("Failed to load component for row ${row.refDes}", e)
                    }
                }

                // Загружаем nets для force-directed placement
                val netDoc = NetRepository.load(paths)

                // Добавляем новые компоненты
                if (result.addNew && report.newRows.isNotEmpty()) {
                    val newNodes = placeNewComponents(report.newRows, currentNodes, netDoc.nets, result.placementNear, componentsMap)
                    schematicNodes.addAll(newNodes)
                    logger.info("Added ${newNodes.size} new components to schematic")
                }

                // Удаляем отсутствующие компоненты
                if (result.removeMissing && report.missingNodes.isNotEmpty()) {
                    val missingIds = report.missingNodes.map { it.bomnetId }.toSet()
                    schematicNodes.removeAll { it.bomnetId in missingIds }
                    logger.info("Removed ${missingIds.size} components from schematic")
                }

                // Сохраняем
                val doc = SchematicDocument(schemaVersion = 1, nodes = schematicNodes.toList(), wires = schematicWires.toList())
                SchematicRepository.save(paths, doc)
            }

            override fun succeeded() {
                javafx.application.Platform.runLater {
                    drawSchematic()
                    val added = if (result.addNew) report.newRows.size else 0
                    val removed = if (result.removeMissing) report.missingNodes.size else 0
                    statusLabel.text = if (added > 0 && removed > 0) {
                        I18n.tr("schematic.sync.status.both", added, removed)
                            .replace("{0}", added.toString()).replace("{1}", removed.toString())
                    } else if (added > 0) {
                        I18n.tr("schematic.sync.status.added", added)
                            .replace("{0}", added.toString())
                    } else {
                        I18n.tr("schematic.sync.status.removed", removed)
                            .replace("{0}", removed.toString())
                    }
                    statusLabel.style = "-fx-text-fill: green;"
                    logger.info("Schematic synchronized: +$added - $removed")
                }
            }

            override fun failed() {
                javafx.application.Platform.runLater {
                    val ex = exception
                    UiUtils.errorAlert(
                        I18n.tr("common.alert.error") ?: "Ошибка",
                        ex?.message ?: "Unknown error"
                    )
                    logger.error("Failed to sync schematic", ex)
                }
            }
        }
        executor.submit(task)
    }

    /** Разместить новые компоненты на схеме */
    private fun placeNewComponents(
        newRows: List<BomRow>,
        existingNodes: List<SchematicNode>,
        nets: List<Net>,
        placementNear: Boolean,
        componentsMap: Map<String, Component>
    ): List<SchematicNode> {
        if (newRows.isEmpty()) return emptyList()

        val newNodes = mutableListOf<SchematicNode>()
        val existingBomnetIds = existingNodes.map { it.bomnetId }.toSet()

        if (placementNear && existingNodes.isNotEmpty()) {
            // Force-directed placement: ищем связанных компонентов и размещаем рядом
            newRows.forEach { newRow ->
                // Найти цепи, в которые входит новый компонент
                val connectedNets = nets.filter { net ->
                    net.connections.any { conn -> conn.refDes == newRow.refDes }
                }

                // Собрать bomnetId связанных компонентов
                val connectedBomnetIds = mutableSetOf<String>()
                connectedNets.forEach { net ->
                    net.connections.forEach { conn ->
                        if (conn.refDes != newRow.refDes && conn.refDes.isNotBlank()) {
                            // Найти bomnetId по refDes
                            val connectedRow = newRows.find { it.refDes == conn.refDes }
                                ?: BomRepository.loadRows(ProjectPaths(newRows[0].let { r -> java.nio.file.Path.of(r.comment) }, "")).find { it.refDes == conn.refDes } // fallback
                            // Проще: ищем в existingNodes по refDes через bomRowsMap
                            // Но у нас нет доступа к BomRow -> bomnetId маппингу
                            // Поэтому используем refDes напрямую
                            val existingNode = existingNodes.find { node ->
                                // Ищем node по refDes из BomRow
                                bomRowsMap.entries.find { it.value.refDes == conn.refDes }?.value?.bomnetId == node.bomnetId
                            }
                            if (existingNode != null) {
                                connectedBomnetIds.add(existingNode.bomnetId)
                            }
                        }
                    }
                }

                // Разместить новый компонент
                if (connectedBomnetIds.isNotEmpty()) {
                    // Найти центр связанных компонентов
                    val connectedNodes = existingNodes.filter { it.bomnetId in connectedBomnetIds }
                    if (connectedNodes.isNotEmpty()) {
                        val centerX = connectedNodes.map { it.x }.average()
                        val centerY = connectedNodes.map { it.y }.average()

                        // Найти свободное место вокруг центра
                        val idealLength = 150.0
                        var placed = false
                        for (angle in 0 until 360 step 45) {
                            val rad = Math.toRadians(angle.toDouble())
                            val newX = centerX + Math.cos(rad) * idealLength
                            val newY = centerY + Math.sin(rad) * idealLength

                            // Проверить, не занято ли место
                            val isOccupied = newNodes.any { it.x == newX && it.y == newY }
                                || existingNodes.any { kotlin.math.abs(it.x - newX) < 50 && kotlin.math.abs(it.y - newY) < 50 }

                            if (!isOccupied) {
                                newNodes.add(SchematicNode(newRow.bomnetId, newX, newY, 0.0))
                                placed = true
                                break
                            }
                        }
                        if (!placed) {
                            newNodes.add(SchematicNode(newRow.bomnetId, centerX + idealLength, centerY, 0.0))
                        }
                    } else {
                        newNodes.add(SchematicNode(newRow.bomnetId, 0.0, 0.0, 0.0))
                    }
                } else {
                    // Нет связанных компонентов — разместить в свободном месте
                    newNodes.add(SchematicNode(newRow.bomnetId, 0.0, 0.0, 0.0))
                }
            }
        } else {
            // Разместить в свободном месте справа
            val maxX = if (existingNodes.isNotEmpty()) {
                existingNodes.maxOfOrNull { it.x } ?: 0.0
            } else {
                0.0
            }

            val columns = 4
            val stepX = 250.0
            val stepY = 200.0
            val startX = maxX + 100.0

            newRows.forEachIndexed { index, row ->
                val col = index % columns
                val rowIdx = index / columns
                newNodes.add(
                    SchematicNode(
                        bomnetId = row.bomnetId,
                        x = startX + col * stepX,
                        y = 100.0 + rowIdx * stepY,
                        rotation = 0.0
                    )
                )
            }
        }

        return newNodes
    }

    override fun showWindow() = stage.show()

    override fun openProject(projectPath: Path) {
        val paths = try {
            val project = ProjectRepository.load(projectPath.resolve("project.cfproj"))
            ProjectPaths(projectPath, project.machineName)
        } catch (e: Exception) {
            UiUtils.errorAlert(I18n.tr("common.alert.error"), "Не удалось открыть проект: ${e.message}")
            logger.error("Failed to open project at $projectPath", e)
            return
        }
        this.projectPaths = paths
        SymbolRegistry.initialize(paths.projectDir)
        stage.title = "CircuitForge — Schematic — $projectPath"

        val loadTask = object : Task<Unit>() {
            override fun call() {
                val rows = BomRepository.loadRows(paths)
                bomRows.setAll(rows)
                bomRowsMap.clear()
                rows.forEach { row -> bomRowsMap[row.bomnetId] = row }

                componentsMap.clear()
                rows.forEach { row ->
                    try {
                        val comp = ComponentRepository.loadComponentById(paths.projectDir, row.componentId)
                        if (comp != null) componentsMap[row.componentId] = comp
                    } catch (e: Exception) {
                        logger.warn("Failed to load component for row ${row.refDes}", e)
                    }
                }

                val netDoc = NetRepository.load(paths)
                nets.setAll(netDoc.nets)

                val existingDoc = SchematicRepository.load(paths)
                if (existingDoc.nodes.isEmpty()) {
                    logger.info("No schematic data found, running ELK auto-layout")
                    try {
                        val engine = ElkLayoutEngine()
                        val document = engine.layout(rows, netDoc.nets, componentsMap)
                        SchematicRepository.save(paths, document)
                        schematicNodes.setAll(document.nodes)
                        schematicWires.setAll(document.wires)
                        logger.info("ELK auto-layout completed: ${document.nodes.size} components")
                    } catch (e: Exception) {
                        logger.warn("ELK auto-layout failed, falling back to grid layout: ${e.message}", e)
                        val nodes = generateGridCoordinates(rows)
                        SchematicRepository.save(paths, SchematicDocument(schemaVersion = 1, nodes = nodes))
                        schematicNodes.setAll(nodes)
                        schematicWires.clear()
                    }
                } else {
                    schematicNodes.setAll(existingDoc.nodes)
                    schematicWires.setAll(existingDoc.wires)
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

/**
 * Направление пина компонента.
 */
enum class PinDirection {
    LEFT, RIGHT, TOP, BOTTOM
}
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
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Path
import java.util.concurrent.Executors

class SchematicWindow : ProjectAware {
    private val logger = LoggerFactory.getLogger(SchematicWindow::class.java)
    private val stage = Stage()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "schematic-io").apply { isDaemon = true }
    }

    private var projectPath: Path? = null

    // Данные (Observable для UI)
    private val bomRows = FXCollections.observableArrayList<BomRow>()
    private val nets = FXCollections.observableArrayList<Net>()
    private val schematicNodes = FXCollections.observableArrayList<SchematicNode>()

    // Map для быстрого доступа
    private val nodesMap = mutableMapOf<String, SchematicNode>()
    private val bomRowsMap = mutableMapOf<String, BomRow>() // bomnetId -> BomRow
    private val componentsMap = mutableMapOf<String, Component>() // componentId -> Component

    // UI контейнеры
    private val canvas = Canvas(3000.0, 3000.0)
    private val componentPane = Pane()
    private val mainPane = Pane()
    private val scrollPane = ScrollPane()
    private val saveButton = Button(I18n.tr("bom.btn.save") ?: "Сохранить")
    private val revertButton = Button(I18n.tr("bom.btn.revert") ?: "Отменить")
    private val statusLabel = Label("")

    // Drag & Drop состояние
    private var dragNode: SchematicNode? = null
    private var dragOffsetX: Double = 0.0
    private var dragOffsetY: Double = 0.0

    init {
        stage.title = I18n.tr("schematic.window.title") ?: "Схема"
        setupLayout()
        setupToolbar()
    }

    private fun setupLayout() {
        // 1. Размеры холста (5000x5000)
        canvas.width = 5000.0
        canvas.height = 5000.0

        // 2. Слой компонентов
        componentPane.prefWidth = 5000.0
        componentPane.prefHeight = 5000.0
        componentPane.isManaged = false
        componentPane.isPickOnBounds = false

        // 3. Основной Pane, содержащий Canvas и компоненты
        mainPane.children.setAll(canvas, componentPane)
        mainPane.prefWidth = 5000.0
        mainPane.prefHeight = 5000.0

        // 4. Оборачиваем mainPane в Group.
        // Group автоматически подстраивает свои границы под содержимое (5000x5000).
        val group = Group(mainPane)

        // 5. Настраиваем ScrollPane
        scrollPane.content = group
        scrollPane.hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        // Отключаем fitToWidth/Height, чтобы ScrollPane не сжимал содержимое
        scrollPane.isFitToWidth = false
        scrollPane.isFitToHeight = false

        // Панель инструментов снизу
        val bottomBar = HBox(10.0, saveButton, revertButton, statusLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;"
        }

        // BorderPane: ScrollPane сверху, кнопки снизу
        val mainLayout = BorderPane().apply {
            center = scrollPane
            bottom = bottomBar
        }

        // Сцена содержит BorderPane
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
        gc.stroke = Color.LIGHTGRAY
        gc.lineWidth = 0.5

        (0..canvas.width.toInt() step 50).forEach { x ->
            gc.strokeLine(x.toDouble(), 0.0, x.toDouble(), canvas.height)
        }

        (0..canvas.height.toInt() step 50).forEach { y ->
            gc.strokeLine(0.0, y.toDouble(), canvas.width, y.toDouble())
        }
    }

    private fun drawConnections(gc: GraphicsContext) {
        gc.lineWidth = 2.0
        gc.stroke = Color.web("#2196F3") // Синий цвет

        nets.forEach { net ->
            if (net.connections.isEmpty()) return@forEach

            // Собираем точные координаты (x, y) для каждого подключения в цепи
            val points = mutableListOf<Pair<Double, Double>>()

            net.connections.forEach { conn ->
                val row = bomRows.find { it.refDes == conn.refDes }
                if (row != null) {
                    val coords = getPortCoordinates(row, conn.pinNumber)
                    if (coords != null) {
                        points.add(coords)
                    }
                }
            }

            // Рисуем линии между точками
            if (points.size >= 2) {
                // Соединяем все точки со первой (звезда)
                val startPoint = points[0]
                for (i in 1 until points.size) {
                    val endPoint = points[i]
                    drawOrthogonalLine(gc, startPoint, endPoint)
                }
            } else if (points.size == 1) {
                // Одиночное подключение — рисуем точку-маркер
                drawConnectionDot(gc, points[0])
            }
        }
    }

    private fun drawOrthogonalLine(gc: GraphicsContext, start: Pair<Double, Double>, end: Pair<Double, Double>) {
        val (x1, y1) = start
        val (x2, y2) = end

        // Простой L-образный путь: сначала горизонталь, потом вертикаль
        gc.strokeLine(x1, y1, x2, y1)
        gc.strokeLine(x2, y1, x2, y2)
    }

    /**
     * Рисует точку-маркер для одиночного подключения (нет второй точки для линии).
     */
    private fun drawConnectionDot(gc: GraphicsContext, point: Pair<Double, Double>) {
        gc.fill = Color.web("#2196F3")
        gc.fillOval(point.first - 4.0, point.second - 4.0, 8.0, 8.0)
    }

    private fun drawComponents() {
        componentPane.children.clear()

        bomRowsMap.values.forEach { row ->
            val node = nodesMap[row.bomnetId] ?: return@forEach
            val comp = componentsMap[row.componentId]

            val group = Group()

            // Определяем тип компонента и рисуем
            comp?.let { component ->
                when (component.name.lowercase()) {
                    "resistor" -> drawCustomResistor(group, row, component)
                    "capacitor" -> drawCustomCapacitor(group, row, component)
                    else -> drawDefaultComponent(group, row, component)
                }
            } ?: return@forEach

            group.layoutX = node.x
            group.layoutY = node.y

            // Drag & Drop на Group
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

    /**
     * Резистор: горизонтальный прямоугольник 40x15, ножки слева/справа.
     * RefDes над компонентом, Comment под ним.
     */
    private fun drawCustomResistor(group: Group, row: BomRow, comp: Component) {
        val rectW = 40.0
        val rectH = 15.0
        val leadLen = 20.0

        // Прямоугольник корпуса
        val rect = Rectangle(rectW, rectH).apply {
            fill = Color.WHITE
            stroke = Color.BLACK
            strokeWidth = 1.5
            x = -rectW / 2
            y = -rectH / 2
        }
        group.children.add(rect)

        // Левая ножка (pin 1)
        val leftLead = Line(-rectW / 2, 0.0, -rectW / 2 - leadLen, 0.0).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(leftLead)

        // Правая ножка (pin 2)
        val rightLead = Line(rectW / 2, 0.0, rectW / 2 + leadLen, 0.0).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(rightLead)

        // RefDes над компонентом
        val refDesLabel = Label(row.refDes).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: black;"
            layoutX = -8.0
            layoutY = -rectH / 2 - leadLen - 18.0
        }
        group.children.add(refDesLabel)

        // Comment (номинал) под компонентом
        val commentLabel = Label(row.comment).apply {
            style = "-fx-font-size: 11px; -fx-text-fill: black;"
            layoutX = -8.0
            layoutY = rectH / 2 + leadLen + 4.0
        }
        group.children.add(commentLabel)

        // Номера пинов (рядом с концами ножек)
        val pin1Label = Label(getPinNumberForIndex(comp, 0)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = -rectW / 2 - leadLen - 12.0
            layoutY = -10.0
        }
        group.children.add(pin1Label)

        val pin2Label = Label(getPinNumberForIndex(comp, 1)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = rectW / 2 + leadLen + 4.0
            layoutY = -10.0
        }
        group.children.add(pin2Label)
    }

    /**
     * Конденсатор: две горизонтальные параллельные линии (пласты),
     * вертикальные ножки сверху и снизу.
     * RefDes и Comment справа от обозначения.
     */
    private fun drawCustomCapacitor(group: Group, row: BomRow, comp: Component) {
        val plateW = 16.0   // ширина пластин
        val plateGap = 6.0  // расстояние между пластинами
        val leadLen = 20.0

        // Верхняя ножка (pin 1) — от центра вверх
        val topLead = Line(0.0, -leadLen, 0.0, 0.0).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(topLead)

        // Нижняя ножка (pin 2) — от центра вниз
        val bottomLead = Line(0.0, 0.0, 0.0, leadLen).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(bottomLead)

        // Левый пласт (горизонтальная линия)
        val plate1 = Line(-plateW / 2, -plateGap / 2, plateW / 2, -plateGap / 2).apply {
            stroke = Color.BLACK
            strokeWidth = 2.0
        }
        group.children.add(plate1)

        // Правый пласт (горизонтальная линия)
        val plate2 = Line(-plateW / 2, plateGap / 2, plateW / 2, plateGap / 2).apply {
            stroke = Color.BLACK
            strokeWidth = 2.0
        }
        group.children.add(plate2)

        // RefDes и Comment справа от пластин
        val refDesLabel = Label(row.refDes).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: black;"
            layoutX = plateW / 2 + leadLen + 8.0
            layoutY = -plateGap / 2 - 5.0
        }
        group.children.add(refDesLabel)

        val commentLabel = Label(row.comment).apply {
            style = "-fx-font-size: 11px; -fx-text-fill: black;"
            layoutX = plateW / 2 + leadLen + 8.0
            layoutY = plateGap / 2 + 4.0
        }
        group.children.add(commentLabel)

        // Номера пинов
        val pin1Label = Label(getPinNumberForIndex(comp, 0)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = 6.0
            layoutY = -leadLen - 10.0
        }
        group.children.add(pin1Label)

        val pin2Label = Label(getPinNumberForIndex(comp, 1)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = 6.0
            layoutY = leadLen + 10.0
        }
        group.children.add(pin2Label)
    }

    /**
     * Стандартный компонент: прямоугольник с портами слева/справа (как было).
     */
    private fun drawDefaultComponent(group: Group, row: BomRow, comp: Component) {
        val allPorts = comp?.ports ?: emptyList()
        val mid = (allPorts.size + 1) / 2
        val leftPorts = allPorts.take(mid)
        val rightPorts = allPorts.drop(mid)

        val portSpacing = 25.0
        val paddingTop = 60.0
        val paddingBottom = 20.0
        val minHeight = 100.0

        val maxPorts = maxOf(leftPorts.size, rightPorts.size)
        val calculatedHeight = maxOf(minHeight, (paddingTop + maxPorts * portSpacing + paddingBottom))

        val width = 160.0
        val height = calculatedHeight

        val rect = Rectangle(width, height).apply {
            fill = Color.WHITE
            stroke = Color.BLACK
            strokeWidth = 1.5
            x = -width / 2
            y = -height / 2
        }
        group.children.add(rect)

        val refDesLabel = Label(row.refDes).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: black;"
            layoutX = -width / 2 + 10.0
            layoutY = -height / 2 + 10.0
        }
        group.children.add(refDesLabel)

        val nameLabel = Label(comp?.name ?: "").apply {
            style = "-fx-font-size: 10px; -fx-text-fill: gray;"
            layoutX = -width / 2 + 10.0
            layoutY = -height / 2 + 28.0
        }
        group.children.add(nameLabel)

        // Левые порты
        leftPorts.forEachIndexed { index, port ->
            val pinY = -height / 2 + paddingTop + index * portSpacing

            val line = Line(-width / 2, pinY, -width / 2 - 20.0, pinY).apply {
                stroke = Color.BLACK
            }
            group.children.add(line)

            val pinNumLabel = Label(port.pinNumber).apply {
                style = "-fx-font-size: 9px; -fx-text-fill: black;"
                layoutX = -width / 2 - 18.0
                layoutY = pinY - 14.0
            }
            group.children.add(pinNumLabel)

            val portNameLabel = Label(port.name).apply {
                style = "-fx-font-size: 10px; -fx-text-fill: black;"
                layoutX = -width / 2 + 5.0
                layoutY = pinY - 8.0
            }
            group.children.add(portNameLabel)
        }

        // Правые порты
        rightPorts.forEachIndexed { index, port ->
            val pinY = -height / 2 + paddingTop + index * portSpacing

            val line = Line(width / 2, pinY, width / 2 + 20.0, pinY).apply {
                stroke = Color.BLACK
            }
            group.children.add(line)

            val pinNumLabel = Label(port.pinNumber).apply {
                style = "-fx-font-size: 9px; -fx-text-fill: black;"
                layoutX = width / 2 + 4.0
                layoutY = pinY - 14.0
            }
            group.children.add(pinNumLabel)

            val portNameLabel = Label(port.name).apply {
                style = "-fx-font-size: 10px; -fx-text-fill: black;"
                layoutX = width / 2 - 45.0
                layoutY = pinY - 8.0
            }
            group.children.add(portNameLabel)
        }
    }

    /**
     * Возвращает номер пина по индексу из ports компонента.
     */
    private fun getPinNumberForIndex(comp: Component, index: Int): String {
        return if (index < comp.ports.size) comp.ports[index].pinNumber else "${index + 1}"
    }

    private fun calculateRectHeight(row: BomRow): Double {
        val portCount = nets.flatMap { net ->
            net.connections.filter { it.refDes == row.refDes }
        }.distinctBy { it.pinNumber }.size

        val halfSize = (portCount + 1) / 2
        val maxPorts = maxOf(halfSize, portCount - halfSize)
        return maxOf(100, maxPorts * 25 + 40).toDouble()
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

                // Заполняем bomRowsMap
                bomRowsMap.clear()
                rows.forEach { row -> bomRowsMap[row.bomnetId] = row }

                // Загружаем компоненты
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
    /**
     * Вычисляет точные координаты (x, y) для конкретного порта компонента.
     * Возвращает КОНЕЦ ножки (где начинается провод связи).
     * @param row Строка BOM (для получения RefDes и bomnetId)
     * @param pinNumber Номер вывода (например, "14")
     * @return Пара (x, y) или null, если порт не найден
     */
    private fun getPortCoordinates(row: BomRow, pinNumber: String): Pair<Double, Double>? {
        val node = nodesMap[row.bomnetId] ?: return null
        val comp = componentsMap[row.componentId] ?: return null

        // 1. Находим индекс порта
        val portIndex = comp.ports.indexOfFirst { it.pinNumber == pinNumber }
        if (portIndex == -1) return null

        // 2. Определяем тип компонента
        when (comp.name.lowercase()) {
            "resistor" -> {
                // Резистор: ножки слева и справа
                // pin 1 → левая ножка, pin 2 → правая ножка
                if (portIndex == 0) {
                    // Левая ножка: конец на x = -20 - 20 = -40
                    return Pair(node.x - 40.0, node.y)
                } else if (portIndex == 1) {
                    // Правая ножка: конец на x = 20 + 20 = 40
                    return Pair(node.x + 40.0, node.y)
                }
            }
            "capacitor" -> {
                // Конденсатор: ножки сверху (pin 1) и снизу (pin 2)
                // Pin 1: конец ножки на y = -leadLen = -20
                // Pin 2: конец ножки на y = leadLen = 20
                if (portIndex == 0) {
                    return Pair(node.x, node.y - 20.0)
                } else if (portIndex == 1) {
                    return Pair(node.x, node.y + 20.0)
                }
            }
            else -> {
                // Стандартный: прямоугольник с портами слева/справа
                val allPorts = comp.ports
                val mid = (allPorts.size + 1) / 2
                val isLeft = portIndex < mid
                val indexInHalf = if (isLeft) portIndex else portIndex - mid

                val portSpacing = 25.0
                val paddingTop = 60.0
                val paddingBottom = 20.0
                val minHeight = 100.0

                val leftPorts = allPorts.take(mid)
                val rightPorts = allPorts.drop(mid)
                val maxPorts = maxOf(leftPorts.size, rightPorts.size)
                val height = maxOf(100.0, (paddingTop + maxPorts * portSpacing + paddingBottom))
                val width = 160.0

                // Y относительно центра
                val pinY = -height / 2 + paddingTop + indexInHalf * portSpacing

                // X = конец ножки (±20 от края)
                val pinX = if (isLeft) -width / 2 - 20.0 else width / 2 + 20.0

                return Pair(node.x + pinX, node.y + pinY)
            }
        }

        return null
    }
}

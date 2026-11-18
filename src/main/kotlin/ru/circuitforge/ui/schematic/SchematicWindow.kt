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
            if (net.connections.size < 2) return@forEach

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
                // Для простоты соединяем все с первой точкой (звездой)
                val startPoint = points[0]
                for (i in 1 until points.size) {
                    val endPoint = points[i]
                    drawOrthogonalLine(gc, startPoint, endPoint)
                }
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

    private fun drawComponents() {
        componentPane.children.clear()

        bomRowsMap.values.forEach { row ->
            val node = nodesMap[row.bomnetId] ?: return@forEach
            val comp = componentsMap[row.componentId]

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

            val group = Group()

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

            group.layoutX = node.x
            group.layoutY = node.y

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

                    // Обновляем схему
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
     * @param row Строка BOM (для получения RefDes и bomnetId)
     * @param pinNumber Номер вывода (например, "14")
     * @return Пара (x, y) или null, если порт не найден
     */
    private fun getPortCoordinates(row: BomRow, pinNumber: String): Pair<Double, Double>? {
        val node = nodesMap[row.bomnetId] ?: return null
        val comp = componentsMap[row.componentId] ?: return null

        // 1. Находим порт в компоненте
        val portIndex = comp.ports.indexOfFirst { it.pinNumber == pinNumber }
        if (portIndex == -1) return null

        // 2. Рассчитываем параметры прямоугольника (как в drawComponents)
        val allPorts = comp.ports
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

        // 3. Определяем, слева или справа порт
        val isLeft = portIndex < mid
        val indexInHalf = if (isLeft) portIndex else portIndex - mid

        // 4. Вычисляем Y (относительно центра группы)
        val pinY = -height / 2 + paddingTop + indexInHalf * portSpacing

        // 5. Вычисляем X (относительно центра группы)
        // Если слева, то X = -width/2 - 20 (конец ножки). Если справа, то +width/2 + 20.
        // Мы берем конец ножки, чтобы линия шла от конца, а не от края прямоугольника.
        val pinX = if (isLeft) -width / 2 - 20.0 else width / 2 + 20.0

        // 6. Абсолютные координаты на холсте
        val absX = node.x + pinX
        val absY = node.y + pinY

        return Pair(absX, absY)
    }
}

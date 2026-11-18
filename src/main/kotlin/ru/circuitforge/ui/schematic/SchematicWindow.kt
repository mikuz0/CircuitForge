package ru.circuitforge.ui.schematic

import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
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
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import ru.circuitforge.i18n.I18n
import ru.circuitforge.project.*
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Окно визуальной схемы (Schematic).
 * Canvas (сетка + линии) + Pane (компоненты с Drag & Drop).
 * Исправлен Layout Loop через isManaged = false и фиксированные размеры.
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
    
    // Map для быстрого доступа к узлам по bomnetId
    private val nodesMap = mutableMapOf<String, SchematicNode>()

    // UI контейнеры
    private val canvas = Canvas(3000.0, 3000.0)
    private val componentPane = Pane()
    private val mainPane = Pane()
    private val scrollPane = ScrollPane()
    private val saveButton = Button(I18n.tr("bom.btn.save") ?: "Сохранить")
    private val revertButton = Button(I18n.tr("bom.btn.revert") ?: "Отменить")
    private val statusLabel = Label("")

    // Drag & Drop состояние
    private var draggingNode: SchematicNode? = null
    private var dragOffsetX: Double = 0.0
    private var dragOffsetY: Double = 0.0

    init {
        stage.title = I18n.tr("schematic.window.title") ?: "Схема"
        setupLayout()
        setupToolbar()
    }

    private fun setupLayout() {
        // Фиксируем размеры Canvas
        canvas.width = 3000.0
        canvas.height = 3000.0
        
        // ComponentPane для компонентов (с фиксированными размерами и isManaged = false)
        componentPane.prefWidth = 3000.0
        componentPane.prefHeight = 3000.0
        componentPane.isManaged = false
        componentPane.isPickOnBounds = false

        // MainPane содержит Canvas и componentPane
        mainPane.children.setAll(canvas, componentPane)
        mainPane.prefWidth = 3000.0
        mainPane.prefHeight = 3000.0
        mainPane.isManaged = false

        // ScrollPane с fitToWidth/Height
        scrollPane.content = mainPane
        scrollPane.hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.fitToWidthProperty().value = true
        scrollPane.fitToHeightProperty().value = true

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
        // Очищаем map узлов
        nodesMap.clear()
        
        // Сохраняем узлы в map
        schematicNodes.forEach { node ->
            nodesMap[node.bomnetId] = node
        }

        // Рисуем линии на Canvas
        drawLines()

        // Создаём компоненты в componentPane
        drawComponents()
    }

    private fun drawLines() {
        val gc = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height

        // Очистка
        gc.fill = Color.WHITE
        gc.fillRect(0.0, 0.0, width, height)

        // Рисуем сетку
        drawGrid(gc)

        // Рисуем связи
        drawConnections(gc)
    }

    private fun drawGrid(gc: GraphicsContext) {
        gc.stroke = Color.LIGHTGRAY
        gc.lineWidth = 0.5

        // Вертикальные линии каждые 50px
        (0..canvas.width.toInt() step 50).forEach { x ->
            gc.strokeLine(x.toDouble(), 0.0, x.toDouble(), canvas.height)
        }

        // Горизонтальные линии каждые 50px
        (0..canvas.height.toInt() step 50).forEach { y ->
            gc.strokeLine(0.0, y.toDouble(), canvas.width, y.toDouble())
        }
    }

    private fun drawConnections(gc: GraphicsContext) {
        gc.lineWidth = 2.0
        gc.stroke = Color.web("#2196F3") // Accent color

        nets.forEach { net ->
            if (net.connections.isEmpty()) return@forEach

            // Собираем координаты всех подключенных компонентов
            val points = mutableListOf<Pair<Double, Double>>()
            net.connections.forEach { conn ->
                val row = bomRows.find { it.refDes == conn.refDes }
                row?.let { bomRow ->
                    val node = nodesMap[bomRow.bomnetId]
                    node?.let {
                        points.add(Pair(it.x + 75.0, it.y + 50.0)) // Центр компонента (150x100 / 2)
                    }
                }
            }

            // Рисуем ортогональные линии между точками
            if (points.size >= 2) {
                for (i in 0 until points.size - 1) {
                    drawOrthogonalLine(gc, points[i], points[i + 1])
                }
            }
        }
    }

    private fun drawOrthogonalLine(gc: GraphicsContext, start: Pair<Double, Double>, end: Pair<Double, Double>) {
        val (x1, y1) = start
        val (x2, y2) = end

        // L-образная форма: сначала горизонталь, потом вертикаль
        gc.strokeLine(x1, y1, x2, y1)
        gc.strokeLine(x2, y1, x2, y2)
    }

    private fun drawComponents() {
        componentPane.children.clear()

        bomRows.forEach { row ->
            val node = nodesMap[row.bomnetId] ?: return@forEach

            val x = node.x
            val y = node.y

            // Прямоугольник компонента — позиция (0, 0) относительно Group
            val rect = Rectangle(0.0, 0.0, 150.0, 100.0).apply {
                fill = Color.WHITE
                stroke = Color.BLACK
                strokeWidth = 1.5
                cursor = Cursor.MOVE
            }

            // Label для RefDes
            val refDesLabel = Label(row.refDes).apply {
                font = Font.font("Arial", FontWeight.BOLD, 14.0)
                layoutX = 10.0
                layoutY = 15.0
            }

            // Label для имени компонента
            val nameLabel = Label(row.componentName).apply {
                font = Font.font("Arial", FontWeight.NORMAL, 11.0)
                layoutX = 10.0
                layoutY = 35.0
            }

            // Labels для портов
            val portLabels = mutableListOf<Label>()
            var portY = 55.0
            nets.forEach { net ->
                net.connections.forEach { conn ->
                    if (conn.refDes == row.refDes && conn.pinNumber.isNotBlank()) {
                        Label("P${conn.pinNumber}").apply {
                            font = Font.font("Arial", FontWeight.NORMAL, 10.0)
                            layoutX = 10.0
                            layoutY = portY
                            portLabels.add(this)
                        }
                        portY += 15.0
                    }
                }
            }

            // VBox для текста внутри компонента
            val textVBox = VBox(5.0, refDesLabel, nameLabel).apply {
                layoutX = 10.0
                layoutY = 10.0
                children.addAll(portLabels)
            }

            // Group объединяет Rectangle и VBox — двигаются вместе
            val componentGroup = Group(rect, textVBox).apply {
                layoutX = x
                layoutY = y
            }

            // Обработчики Drag & Drop на Group
            componentGroup.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
                draggingNode = node
                dragOffsetX = event.sceneX - componentGroup.layoutXProperty().value
                dragOffsetY = event.sceneY - componentGroup.layoutYProperty().value
                componentGroup.toFront()
                rect.fill = Color.web("#E3F2FD") // Подсветка
            }

            componentGroup.addEventHandler(MouseEvent.MOUSE_DRAGGED) { event ->
                if (draggingNode == null) return@addEventHandler
                
                val newX = event.sceneX - dragOffsetX
                val newY = event.sceneY - dragOffsetY
                
                // Вычисляем смещение
                val dx = newX - componentGroup.layoutXProperty().value
                val dy = newY - componentGroup.layoutYProperty().value
                
                // Обновляем координаты в модели
                draggingNode?.let { n ->
                    n.x += dx
                    n.y += dy
                }
                
                // Перемещаем Group
                componentGroup.layoutX = newX
                componentGroup.layoutY = newY
                
                // Перерисовываем линии
                drawLines()
            }

            componentGroup.addEventHandler(MouseEvent.MOUSE_RELEASED) { event ->
                if (draggingNode == null) return@addEventHandler
                
                draggingNode = null
                rect.fill = Color.WHITE // Возвращаем белый цвет
                
                // Автосохранение
                saveSchematicAsync()
            }

            componentPane.children.add(componentGroup)
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

        // Загружаем данные в фоновом потоке
        val loadTask = object : Task<Unit>() {
            override fun call() {
                // Загружаем BOM
                val rows = BomRepository.loadRows(projectPath)
                bomRows.setAll(rows)

                // Загружаем Nets
                val netDoc = NetRepository.load(projectPath)
                nets.setAll(netDoc.nets)

                // Загружаем или генерируем Schematic
                val existingDoc = SchematicRepository.load(projectPath)
                if (existingDoc.nodes.isEmpty()) {
                    // Первый запуск — генерируем координаты
                    logger.info("No schematic data found, generating grid layout")
                    val nodes = generateGridCoordinates(rows)
                    SchematicRepository.save(projectPath, SchematicDocument(schemaVersion = 1, nodes = nodes))
                    schematicNodes.setAll(nodes)
                } else {
                    schematicNodes.setAll(existingDoc.nodes)
                }
            }

            override fun succeeded() {
                // Отрисовка на FX потоке
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

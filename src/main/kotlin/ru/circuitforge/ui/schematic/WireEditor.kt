package ru.circuitforge.ui.schematic

import javafx.concurrent.Task
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import org.slf4j.LoggerFactory
import ru.circuitforge.project.*
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Редактор линий связи на схеме.
 * Отвечает за интерактивное редактирование путей: создание точек излома,
 * перетаскивание, удаление, визуальную обратную связь.
 */
class WireEditor(
    private val nets: MutableList<Net>,
    private val netPaths: MutableList<NetPath>,
    private val bomRows: MutableList<BomRow>,
    private val nodesMap: Map<String, SchematicNode>,
    private val componentsMap: Map<String, Component>,
    private val projectPath: Path?,
    private val executor: java.util.concurrent.ExecutorService,
    private val onSave: () -> Unit
) {
    private val logger = LoggerFactory.getLogger(WireEditor::class.java)

    // Состояние drag точки излома
    var draggingPoint: Pair<String, Int>? = null
        private set
    private var dragPointOffsetX: Double = 0.0
    private var dragPointOffsetY: Double = 0.0

    // Выделенная точка излома
    var selectedPoint: Pair<String, Int>? = null
        private set

    // Временная точка для визуальной обратной связи при клике
    var tempClickPoint: Pair<Double, Double>? = null
        private set

    /** Радиус захвата точки излома (пиксели). */
    private val POINT_HIT_RADIUS = 8.0

    /** Проверка: находится ли точка рядом с данным координатам. */
    fun isNearPoint(x: Double, y: Double, px: Double, py: Double): Boolean {
        return kotlin.math.hypot(x - px, y - py) <= POINT_HIT_RADIUS
    }

    /** Расстояние от точки до отрезка. */
    fun distanceToSegment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return kotlin.math.hypot(px - x1, py - y1)
        var t = ((px - x1) * dx + (py - y1) * dy) / lengthSq
        t = t.coerceIn(0.0, 1.0)
        val projX = x1 + t * dx
        val projY = y1 + t * dy
        return kotlin.math.hypot(px - projX, py - projY)
    }

    /**
     * Обработчик нажатия мыши на Canvas.
     * 1. SHIFT+клик — удаление точки излома
     * 2. CTRL+клик — создание новой точки излома
     * 3. Клик на точку — выделение и начало перетаскивания
     * 4. Клик в пустоту — сброс выделения
     */
    fun handleMousePressed(event: MouseEvent) {
        // 1. SHIFT + Клик: Удаление точки излома
        if (event.isShiftDown) {
            val (netName, idx) = findPointAt(event.x, event.y) ?: return
            val path = netPaths.find { it.netName == netName } ?: return
            if (path.waypoints.size > 2) {
                val updated = path.copy(waypoints = path.waypoints.toMutableList().apply { removeAt(idx) })
                val pathIdx = netPaths.indexOf(path)
                if (pathIdx >= 0) netPaths[pathIdx] = updated
                selectedPoint = null

                onSave()
            }
            event.consume()
            return
        }

        // 2. CTRL + Клик: Создание новой точки излома на ближайшей линии
        if (event.isControlDown) {
            val (netName, clickX, clickY) = findNearestNetLine(event.x, event.y) ?: return
            createPointOnNetLine(netName, clickX, clickY)
            event.consume()
            return
        }

        // 3. Обычный клик: Выделение и начало перетаскивания существующей точки
        val existingPoint = findPointAt(event.x, event.y)
        if (existingPoint != null) {
            val (netName, idx) = existingPoint
            val path = netPaths.find { it.netName == netName } ?: return
            if (idx > 0 && idx < path.waypoints.size - 1) {
                selectedPoint = Pair(netName, idx)
                draggingPoint = Pair(netName, idx)
                val wp = path.waypoints[idx]
                dragPointOffsetX = event.x - wp.x
                dragPointOffsetY = event.y - wp.y
            } else {
                selectedPoint = null
            }
            onSave()
            event.consume()
            return
        }

        // 4. Клик в пустоту: сброс выделения
        selectedPoint = null
        tempClickPoint = null
        onSave()
    }

    /** Обработчик перетаскивания мыши на Canvas. */
    fun handleMouseDragged(event: MouseEvent) {
        val (netName, idx) = draggingPoint ?: return
        val path = netPaths.find { it.netName == netName } ?: return

        val newX = (event.x - dragPointOffsetX).roundToGrid()
        val newY = (event.y - dragPointOffsetY).roundToGrid()

        val updated = path.copy(waypoints = path.waypoints.toMutableList().apply {
            set(idx, WirePoint(newX, newY))
        })
        val pathIdx = netPaths.indexOf(path)
        if (pathIdx >= 0) netPaths[pathIdx] = updated

        onSave()
    }

    /** Обработчик отпускания мыши на Canvas. */
    fun handleMouseReleased(event: MouseEvent) {
        draggingPoint = null
        tempClickPoint = null
        onSave()
    }

    /** Находит ближайшую точку излома. Возвращает (netName, waypointIndex) или null. */
    fun findPointAt(sceneX: Double, sceneY: Double): Pair<String, Int>? {
        netPaths.forEach { netPath ->
            netPath.waypoints.forEachIndexed { index, wp ->
                if (index > 0 && index < netPath.waypoints.size - 1) {
                    if (isNearPoint(sceneX, sceneY, wp.x, wp.y)) {
                        return Pair(netPath.netName, index)
                    }
                }
            }
        }
        return null
    }

    /**
     * Находит ближайшую автоматическую линию цепи.
     * Возвращает (netName, snapX, snapY) — имя цепи и координаты привязки.
     */

    fun findNearestNetLine(sceneX: Double, sceneY: Double): Triple<String, Double, Double>? {
        var bestDist = 10.0
        var bestNetName: String? = null
        var bestSnapX = 0.0
        var bestSnapY = 0.0

        nets.forEach { net ->
            if (net.connections.size < 2) return@forEach
            val pinPoints = getNetPoints(net)
            if (pinPoints.size < 2) return@forEach

            // Строим цепочку сегментов, соответствующую реальной отрисовке
            val chain = mutableListOf<Pair<Double, Double>>()
            val userPath = netPaths.find { it.netName == net.name }

            if (userPath != null && userPath.waypoints.isNotEmpty()) {
                // Пользовательский путь: пин1 -> waypoints -> пин2
                chain.add(pinPoints[0])
                chain.addAll(userPath.waypoints.map { Pair(it.x, it.y) })
                chain.add(pinPoints[pinPoints.size - 1])
            } else {
                // Автоматический путь: пин1 -> угол -> пин2 (для первых двух пинов)
                // Если пинов больше двух, там рисуются "звезды" от первого пина.
                val p1 = pinPoints[0]
                for (i in 1 until pinPoints.size) {
                    val p2 = pinPoints[i]
                    // Угол (ортогональная трассировка): сначала горизонталь, потом вертикаль
                    val cornerX = p2.first
                    val cornerY = p1.second

                    chain.add(p1)
                    chain.add(Pair(cornerX, cornerY))
                    chain.add(p2)
                }
            }

            // Ищем ближайший сегмент
            for (i in 0 until chain.size - 1) {
                val p1 = chain[i]
                val p2 = chain[i + 1]

                // Пропускаем сегменты нулевой длины
                if (p1.first == p2.first && p1.second == p2.second) continue

                val dist = distanceToSegment(sceneX, sceneY, p1.first, p1.second, p2.first, p2.second)

                if (dist < bestDist) {
                    // Вычисляем точку на сегменте (проекция)
                    val dx = p2.first - p1.first
                    val dy = p2.second - p1.second
                    val lenSq = dx * dx + dy * dy
                    var t = if (lenSq > 0) ((sceneX - p1.first) * dx + (sceneY - p1.second) * dy) / lenSq else 0.0
                    t = t.coerceIn(0.0, 1.0)

                    val snapX = (p1.first + t * dx).roundToGrid()
                    val snapY = (p1.second + t * dy).roundToGrid()

                    // Не ставим точку, если она слишком близко к концам (пинам)
                    val distToStart = kotlin.math.hypot(snapX - p1.first, snapY - p1.second)
                    val distToEnd = kotlin.math.hypot(snapX - p2.first, snapY - p2.second)
                    if (distToStart < 15.0 || distToEnd < 15.0) continue

                    bestNetName = net.name
                    bestSnapX = snapX
                    bestSnapY = snapY
                    bestDist = dist
                }
            }
        }
        return if (bestNetName != null) Triple(bestNetName, bestSnapX, bestSnapY) else null
    }
    /** Привязка координаты к сетке 12.5px. */
    private fun Double.roundToGrid(): Double {
        val gridStep = 12.5
        return kotlin.math.round(this / gridStep) * gridStep
    }

    /**
     * Создаёт новую точку излома на линии цепи.
     */
    private fun createPointOnNetLine(netName: String, snapX: Double, snapY: Double) {
        val net = nets.find { it.name == netName } ?: return
        val points = getNetPoints(net)
        if (points.size < 2) return

        val existingPath = netPaths.find { it.netName == netName }
        if (existingPath != null) {
            // Полная цепочка: пин1 → wps... → пинN
            val chain = mutableListOf<Pair<Double, Double>>()
            chain.add(points[0])
            chain.addAll(existingPath.waypoints.map { Pair(it.x, it.y) })
            chain.add(points[points.size - 1])

            // Ищем сегмент, ближайший к точке
            var bestIdx = 0
            var bestDist = Double.MAX_VALUE
            for (i in 0 until chain.size - 1) {
                val d = distanceToSegment(snapX, snapY,
                    chain[i].first, chain[i].second,
                    chain[i + 1].first, chain[i + 1].second)
                if (d < bestDist) { bestDist = d; bestIdx = i }
            }
            // Вставка: индекс сегмента = индекс waypoint (т.к. chain[0] — это пин)
            val insertAt = bestIdx  // перед chain[bestIdx+1]
            val updated = existingPath.copy(
                waypoints = existingPath.waypoints.toMutableList().apply {
                    add(insertAt.coerceAtMost(size), WirePoint(snapX, snapY))
                })
            netPaths[netPaths.indexOf(existingPath)] = updated
        } else {
            val newPath = NetPath(netName, listOf(
                WirePoint(points[0].first, points[0].second),
                WirePoint(snapX, snapY),
                WirePoint(points[1].first, points[1].second)
            ))
            netPaths.add(newPath)
        }
        onSave()
    }

    /** Собирает координаты портов цепи. */
    private fun getNetPoints(net: Net): List<Pair<Double, Double>> {
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
        return points
    }

    /** Вычисляет координаты конца ножки порта. */
    fun getPortCoordinates(row: BomRow, pinNumber: String): Pair<Double, Double>? {
        val node = nodesMap[row.bomnetId] ?: return null
        val comp = componentsMap[row.componentId] ?: return null

        val portIndex = comp.ports.indexOfFirst { it.pinNumber == pinNumber }
        if (portIndex == -1) return null

        when (comp.name.lowercase()) {
            "resistor" -> {
                if (portIndex == 0) return Pair(node.x - 50.0, node.y)
                else if (portIndex == 1) return Pair(node.x + 50.0, node.y)
            }
            "capacitor" -> {
                if (portIndex == 0) return Pair(node.x - 50.0, node.y) // Левая ножка
                else if (portIndex == 1) return Pair(node.x + 50.0, node.y) // Правая ножка
            }
            else -> {
                val allPorts = comp.ports
                val mid = (allPorts.size + 1) / 2
                val isLeft = portIndex < mid
                val indexInHalf = if (isLeft) portIndex else portIndex - mid

                val portSpacing = 25.0
                val paddingTop = 50.0
                val paddingBottom = 50.0
                val minHeight = 100.0

                val leftPorts = allPorts.take(mid)
                val rightPorts = allPorts.drop(mid)
                val maxPorts = maxOf(leftPorts.size, rightPorts.size)
                val height = maxOf(minHeight, (paddingTop + maxPorts * portSpacing + paddingBottom))
                val width = 150.0

                val pinY = -height / 2 + paddingTop + indexInHalf * portSpacing
                val pinX = if (isLeft) -100.0 else 100.0

                return Pair(node.x + pinX, node.y + pinY)
            }
        }
        return null
    }




    /** Рисует точки излома на Canvas. */
    fun drawWaypointMarkers(gc: javafx.scene.canvas.GraphicsContext, netPaths: List<NetPath>) {
        netPaths.forEach { netPath ->
            netPath.waypoints.forEachIndexed { index, point ->
                if (index > 0 && index < netPath.waypoints.size - 1) {
                    val isSelected = selectedPoint == Pair(netPath.netName, index)
                    gc.fill = if (isSelected) Color.web("#FF5722") else Color.WHITE
                    gc.stroke = Color.web("#2196F3")
                    gc.lineWidth = 2.0
                    val radius = if (isSelected) 6.0 else 4.0
                    gc.fillOval(point.x - radius, point.y - radius, radius * 2, radius * 2)
                    gc.strokeOval(point.x - radius, point.y - radius, radius * 2, radius * 2)
                }
            }
        }

        // Временная точка клика
        tempClickPoint?.let { (tx, ty) ->
            gc.fill = Color.web("#4CAF50")
            gc.stroke = Color.web("#2E7D32")
            gc.lineWidth = 2.0
            gc.fillOval(tx - 5.0, ty - 5.0, 10.0, 10.0)
            gc.strokeOval(tx - 5.0, ty - 5.0, 10.0, 10.0)
        }
    }

    /** Сбрасывает состояние. */
    fun reset() {
        selectedPoint = null
        tempClickPoint = null
        draggingPoint = null
    }
}

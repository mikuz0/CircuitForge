package ru.circuitforge.ui.schematic

import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.project.SchematicNode
import ru.circuitforge.ui.schematic.symbols.SymbolRegistry

/**
 * Утилитный класс для вычисления координат портов на холсте схемы.
 * Преобразует координаты из локальной системы SVG-символа в глобальную систему схемы.
 *
 * Координаты портов в symbols.json заданы относительно top-left SVG (viewBox="0 0 W H").
 * Group.layoutX/layoutY = node.x/node.y — позиция top-left SVG на холсте.
 *
 * Формула: canvasX = node.x + portX, canvasY = node.y + portY
 */
object PortCalculator {
    private val registry = SymbolRegistry.getInstance()

    /**
     * Вычисляет координаты порта на холсте схемы.
     */
    fun getPortCoordinates(
        nodesMap: Map<String, SchematicNode>,
        componentsMap: Map<String, Component>,
        row: BomRow,
        pinNumber: String,
    ): Pair<Double, Double>? {
        val node = nodesMap[row.bomnetId] ?: return null
        val comp = componentsMap[row.componentId] ?: return null

        // 1. Пробуем получить точные координаты из symbols.json
        val portPos = registry.getPortPosition(comp.name, pinNumber)

        if (portPos != null) {
            val (symW, symH) = registry.getSymbolSize(comp.name) ?: Pair(80.0, 40.0)
            val centerX = node.x + symW / 2.0
            val centerY = node.y + symH / 2.0
            val (portX, portY) = portPos

            // portX/portY — координаты SVG относительно top-left
            var baseX = node.x + portX
            var baseY = node.y + portY

            // Применяем поворот, если компонент повёрнут
            if (node.rotation != 0.0) {
                val rad = Math.toRadians(node.rotation)
                val dx = baseX - centerX
                val dy = baseY - centerY
                baseX = centerX + dx * Math.cos(rad) - dy * Math.sin(rad)
                baseY = centerY + dx * Math.sin(rad) + dy * Math.cos(rad)
            }
            return Pair(baseX, baseY)
        }

        // 2. Fallback: если порта нет в конфиге, раскидываем по периметру (для многопиновых)
        val portIndex = comp.ports.indexOfFirst { it.pinNumber == pinNumber }
        if (portIndex == -1) return null

        val totalPorts = comp.ports.size

        // Рассчитываем размеры с контролем минимального шага между портами
        val minPinSpacing = 4 * 12.5 // 50px
        val minWidth = 100.0
        val minHeight = 80.0

        val perSide = if (totalPorts > 2) (totalPorts + 3) / 4 else 1
        val dynamicWidth = maxOf(perSide * minPinSpacing, minWidth)
        val dynamicHeight = maxOf(perSide * minPinSpacing, minHeight)

        val centerX = node.x + dynamicWidth / 2.0
        val centerY = node.y + dynamicHeight / 2.0
        val halfW = dynamicWidth / 2.0
        val halfH = dynamicHeight / 2.0

        val (pinX, pinY) =
            if (totalPorts <= 2) {
                if (portIndex == 0) Pair(-halfW, 0.0) else Pair(halfW, 0.0)
            } else {
                // Многопиновый: по периметру (верх-право-низ-лево)
                val side = portIndex / perSide
                val posInSide = portIndex % perSide
                when (side) {
                    0 -> Pair(-halfW + (posInSide + 1) * dynamicWidth / (perSide + 1), -halfH) // верх
                    1 -> Pair(halfW, -halfH + (posInSide + 1) * dynamicHeight / (perSide + 1)) // право
                    2 -> Pair(halfW - (posInSide + 1) * dynamicWidth / (perSide + 1), halfH) // низ
                    else -> Pair(-halfW, halfH - (posInSide + 1) * dynamicHeight / (perSide + 1)) // лево
                }
            }

        var baseX = centerX + pinX
        var baseY = centerY + pinY

        if (node.rotation != 0.0) {
            val rad = Math.toRadians(node.rotation)
            val dx = baseX - centerX
            val dy = baseY - centerY
            baseX = centerX + dx * Math.cos(rad) - dy * Math.sin(rad)
            baseY = centerY + dx * Math.sin(rad) + dy * Math.cos(rad)
        }

        return Pair(baseX, baseY)
    }

    /**
     * Вычисляет координаты порта с учётом поворота компонента.
     * Дубликат getPortCoordinates — для семанической ясности вызова.
     */
    fun getPortCoordinatesWithRotation(
        nodesMap: Map<String, SchematicNode>,
        componentsMap: Map<String, Component>,
        row: BomRow,
        pinNumber: String,
    ): Pair<Double, Double>? {
        return getPortCoordinates(nodesMap, componentsMap, row, pinNumber)
    }
}

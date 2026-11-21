package ru.circuitforge.ui.schematic

import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.project.SchematicNode

/**
 * Утилита для расчёта координат портов компонентов.
 * Координаты согласованы с SVG-символами:
 * - Резистор: 80×40, ножки на x = ±40
 * - Конденсатор: 80×50, ножки на x = ±40
 * - IC: 120×80, ножки на x = ±60
 */
object PortCalculator {

    /** Вычисляет координаты конца ножки порта. */
    fun getPortCoordinates(
        nodesMap: Map<String, SchematicNode>,
        componentsMap: Map<String, Component>,
        row: BomRow,
        pinNumber: String
    ): Pair<Double, Double>? {
        val node = nodesMap[row.bomnetId] ?: return null
        val comp = componentsMap[row.componentId] ?: return null

        val portIndex = comp.ports.indexOfFirst { it.pinNumber == pinNumber }
        if (portIndex == -1) return null

        var baseX: Double
        var baseY: Double

        when (comp.name.lowercase()) {
            "resistor" -> {
                // SVG: 80×40, ножки на x = ±40
                if (portIndex == 0) { baseX = node.x - 40.0; baseY = node.y }
                else if (portIndex == 1) { baseX = node.x + 40.0; baseY = node.y }
                else return null
            }
            "capacitor" -> {
                // SVG: 80×50, ножки на x = ±40
                if (portIndex == 0) { baseX = node.x - 40.0; baseY = node.y }
                else if (portIndex == 1) { baseX = node.x + 40.0; baseY = node.y }
                else return null
            }
            else -> {
                // SVG: 120×80, ножки на x = ±60
                val allPorts = comp.ports
                val mid = (allPorts.size + 1) / 2
                val isLeft = portIndex < mid
                val indexInHalf = if (isLeft) portIndex else portIndex - mid

                val portSpacing = 25.0
                val paddingTop = 20.0
                val paddingBottom = 20.0
                val minHeight = 60.0

                val maxPorts = maxOf(mid, allPorts.size - mid)
                val height = maxOf(minHeight, (paddingTop + maxPorts * portSpacing + paddingBottom))

                val pinY = -height / 2 + paddingTop + indexInHalf * portSpacing
                val pinX = if (isLeft) -60.0 else 60.0

                baseX = node.x + pinX
                baseY = node.y + pinY
            }
        }

        // Применяем поворот относительно центра компонента (node.x, node.y)
        val rotation = node.rotation
        if (rotation != 0.0) {
            val rad = Math.toRadians(rotation)
            val dx = baseX - node.x
            val dy = baseY - node.y
            val cos = Math.cos(rad)
            val sin = Math.sin(rad)
            baseX = node.x + dx * cos - dy * sin
            baseY = node.y + dx * sin + dy * cos
        }

        return Pair(baseX, baseY)
    }
}

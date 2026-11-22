package ru.circuitforge.ui.schematic

import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.project.SchematicNode

/**
 * Утилита для расчёта координат портов компонентов.
 * Координаты согласованы с SVG-символами:
 * - Резистор/Конденсатор/Диод: ножки слева/справа (x = ±halfWidth)
 * - Crystal: ножки сверху/снизу (y = ±halfHeight)
 * - IC: ножки слева/справа (x = ±halfWidth)
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

        val name = comp.name.lowercase()
        val isCrystal = name.contains("crystal") || name.contains("xtal") || name.contains("квц")

        var baseX: Double
        var baseY: Double

        if (isCrystal) {
            // Crystal: ножки сверху/снизу
            val halfHeight = 25.0
            when (portIndex) {
                0 -> { baseX = node.x; baseY = node.y - halfHeight }
                1 -> { baseX = node.x; baseY = node.y + halfHeight }
                else -> return null
            }
        } else {
            // Резистор, конденсатор, диод, IC: ножки слева/справа
            // Определяем halfWidth по типу компонента
            val halfWidth = when {
                name.contains("resist") || name.contains("r\\b") -> 40.0  // резистор 80px
                name.contains("capac") || name.contains("c\\b") -> 40.0   // конденсатор 80px
                name.contains("diode") || name.contains("d\\b") -> 40.0   // диод 80px
                name.contains("induct") || name.contains("l\\b") || name.contains("coil") -> 40.0  // катушка 80px
                name.contains("antenna") || name.contains("j") -> 40.0    // антенна/разъём 80px
                else -> 60.0  // IC 120px по умолчанию
            }

            // Для 2-портовых компонентов: порт 0 = слева, порт 1 = справа
            if (comp.ports.size == 2) {
                when (portIndex) {
                    0 -> { baseX = node.x - halfWidth; baseY = node.y }
                    1 -> { baseX = node.x + halfWidth; baseY = node.y }
                    else -> return null
                }
            } else {
                // Для IC (>2 портов): распределяем по бокам
                val mid = (comp.ports.size + 1) / 2
                val isLeft = portIndex < mid
                val indexInHalf = if (isLeft) portIndex else portIndex - mid

                val svgHeight = 80.0
                val pinSpacing = svgHeight / maxOf(mid, 1)
                val pinY = -svgHeight / 2 + (indexInHalf + 0.5) * pinSpacing
                val pinX = if (isLeft) -halfWidth else halfWidth

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

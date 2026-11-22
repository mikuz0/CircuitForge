package ru.circuitforge.ui.schematic

import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.project.SchematicNode
import ru.circuitforge.ui.schematic.symbols.SymbolRegistry

/**
 * Утилита для расчёта координат портов компонентов.
 * Использует SymbolRegistry для получения позиций портов из конфига.
 */
object PortCalculator {
    private val registry = SymbolRegistry.getInstance()

    /** Вычисляет координаты конца ножки порта. */
    fun getPortCoordinates(
        nodesMap: Map<String, SchematicNode>,
        componentsMap: Map<String, Component>,
        row: BomRow,
        pinNumber: String
    ): Pair<Double, Double>? {
        val node = nodesMap[row.bomnetId] ?: return null
        val comp = componentsMap[row.componentId] ?: return null

        // 1. Пробуем получить точные координаты из symbols.json
        val portPos = registry.getPortPosition(comp.name, pinNumber)

        if (portPos != null) {
            var baseX = node.x + portPos.first
            var baseY = node.y + portPos.second

            // Применяем поворот, если компонент повёрнут
            if (node.rotation != 0.0) {
                val rad = Math.toRadians(node.rotation)
                val dx = baseX - node.x
                val dy = baseY - node.y
                baseX = node.x + dx * Math.cos(rad) - dy * Math.sin(rad)
                baseY = node.y + dx * Math.sin(rad) + dy * Math.cos(rad)
            }
            return Pair(baseX, baseY)
        }

        // 2. Fallback: если порта нет в конфиге, раскидываем по периметру (для многопиновых)
        val portIndex = comp.ports.indexOfFirst { it.pinNumber == pinNumber }
        if (portIndex == -1) return null

        val totalPorts = comp.ports.size
        val (symW, symH) = registry.getSymbolSize(comp.name)
        val halfW = symW / 2.0
        val halfH = symH / 2.0

        val (pinX, pinY) = if (totalPorts <= 2) {
            if (portIndex == 0) Pair(-halfW, 0.0) else Pair(halfW, 0.0)
        } else {
            // Многопиновый: по периметру (верх-право-низ-лево)
            val perSide = (totalPorts + 3) / 4
            val side = portIndex / perSide
            val posInSide = portIndex % perSide
            when (side) {
                0 -> Pair(-halfW + (posInSide + 1) * symW / (perSide + 1), -halfH) // верх
                1 -> Pair(halfW, -halfH + (posInSide + 1) * symH / (perSide + 1)) // право
                2 -> Pair(halfW - (posInSide + 1) * symW / (perSide + 1), halfH)  // низ
                else -> Pair(-halfW, halfH - (posInSide + 1) * symH / (perSide + 1)) // лево
            }
        }

        var baseX = node.x + pinX
        var baseY = node.y + pinY

        if (node.rotation != 0.0) {
            val rad = Math.toRadians(node.rotation)
            val dx = baseX - node.x
            val dy = baseY - node.y
            baseX = node.x + dx * Math.cos(rad) - dy * Math.sin(rad)
            baseY = node.y + dx * Math.sin(rad) + dy * Math.cos(rad)
        }

        return Pair(baseX, baseY)
    }
}
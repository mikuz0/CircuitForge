package ru.circuitforge.ui.schematic

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.Pane
import javafx.scene.shape.*
import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.ui.schematic.symbols.SymbolRegistry

/**
 * Рендерер компонентов для схемы.
 * Использует SVG-символы из SymbolRegistry.
 */
object ComponentRenderer {

    fun drawCustomResistor(group: Group, row: BomRow, comp: Component) {
        drawSvgSymbol(group, row, comp, "resistor")
    }

    fun drawCustomCapacitor(group: Group, row: BomRow, comp: Component) {
        drawSvgSymbol(group, row, comp, "capacitor")
    }

    fun drawDefaultComponent(group: Group, row: BomRow, comp: Component) {
        drawSvgSymbol(group, row, comp, "default")
    }

    /**
     * Общая функция отрисовки SVG-символа с подписями.
     * Клонирование узлов теперь происходит здесь, с сохранением всех размеров.
     */
    private fun drawSvgSymbol(group: Group, row: BomRow, comp: Component, symbolName: String) {
        val registry = SymbolRegistry.getInstance()
        val originalSymbol = registry.getSymbol(symbolName) as Pane

        // Клонируем символ с сохранением всех свойств и вложенных узлов
        val symbolClone = clonePane(originalSymbol)
        group.children.add(symbolClone)

        // Подписи RefDes и Comment
        val refDesLabel = Label(row.refDes).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: black;"
            layoutX = -20.0
            layoutY = -35.0
        }
        group.children.add(refDesLabel)

        val commentLabel = Label(row.comment).apply {
            style = "-fx-font-size: 11px; -fx-text-fill: gray;"
            layoutX = -20.0
            layoutY = 25.0
        }
        group.children.add(commentLabel)

        // Номера пинов
        comp.ports.forEachIndexed { index, port ->
            val pinLabel = Label(port.pinNumber).apply {
                style = "-fx-font-size: 9px; -fx-text-fill: black;"
                layoutX = if (index % 2 == 0) -55.0 else 40.0
                layoutY = -10.0 + index * 15.0
            }
            group.children.add(pinLabel)
        }
    }

    /**
     * Безопасное клонирование Pane со всеми вложенными элементами (Group, Rect, Line, Path и т.д.)
     */
    private fun clonePane(source: Pane): Pane {
        val clone = Pane().apply {
            prefWidth = source.prefWidth
            prefHeight = source.prefHeight
            isManaged = false
            isPickOnBounds = false
        }

        source.children.forEach { child ->
            clone.children.add(cloneNode(child))
        }
        return clone
    }

    /**
     * Рекурсивное клонирование отдельного узла с сохранением размеров и координат.
     */
    private fun cloneNode(node: Node): Node {
        return when (node) {
            is Group -> {
                val group = Group()
                node.children.forEach { child ->
                    group.children.add(cloneNode(child))
                }
                group
            }
            is Rectangle -> {
                Rectangle(node.x, node.y, node.width, node.height).apply {
                    fill = node.fill
                    stroke = node.stroke
                    strokeWidth = node.strokeWidth
                }
            }
            is Line -> {
                Line(node.startX, node.startY, node.endX, node.endY).apply {
                    stroke = node.stroke
                    strokeWidth = node.strokeWidth
                }
            }
            is Circle -> {
                Circle(node.centerX, node.centerY, node.radius).apply {
                    fill = node.fill
                    stroke = node.stroke
                    strokeWidth = node.strokeWidth
                }
            }
            is Ellipse -> {
                Ellipse(node.centerX, node.centerY, node.radiusX, node.radiusY).apply {
                    fill = node.fill
                    stroke = node.stroke
                    strokeWidth = node.strokeWidth
                }
            }
            is Path -> {
                Path().apply {
                    elements.setAll(node.elements)
                    fill = node.fill
                    stroke = node.stroke
                    strokeWidth = node.strokeWidth
                }
            }
            else -> node // Для неизвестных узлов просто возвращаем как есть
        }
    }

    fun getPinNumberForIndex(comp: Component, index: Int): String {
        return if (index < comp.ports.size) comp.ports[index].pinNumber else "${index + 1}"
    }
}
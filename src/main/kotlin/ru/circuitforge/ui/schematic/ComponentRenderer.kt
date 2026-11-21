package ru.circuitforge.ui.schematic

import javafx.scene.Group
import javafx.scene.control.Label
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.ui.schematic.symbols.SymbolRegistry

/**
 * Рендерер компонентов для схемы.
 * Использует SVG-символы из SymbolRegistry.
 */
object ComponentRenderer {

    /**
     * Резистор: SVG-символ 80×40, ножки на x = ±40.
     */
    fun drawCustomResistor(group: Group, row: BomRow, comp: Component) {
        drawSvgSymbol(group, row, comp, "resistor")
    }

    /**
     * Конденсатор: SVG-символ 80×50, ножки на x = ±40.
     */
    fun drawCustomCapacitor(group: Group, row: BomRow, comp: Component) {
        drawSvgSymbol(group, row, comp, "capacitor")
    }

    /**
     * Стандартный компонент (IC): SVG-символ 120×80, ножки на x = ±60.
     */
    fun drawDefaultComponent(group: Group, row: BomRow, comp: Component) {
        drawSvgSymbol(group, row, comp, "default")
    }

    /**
     * Общая функция отрисовки SVG-символа с подписями.
     */
    private fun drawSvgSymbol(group: Group, row: BomRow, comp: Component, symbolName: String) {
        val registry = SymbolRegistry.getInstance()
        val symbol = registry.getSymbol(symbolName) as javafx.scene.layout.Pane
        
        // Клонируем SVG-узел (упрощённо)
        val symbolClone = javafx.scene.layout.Pane().apply {
            symbol.children.forEach { node ->
                this.children.add(node)
            }
            prefWidth = symbol.prefWidth
            prefHeight = symbol.prefHeight
        }
        
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
     * Возвращает номер пина по индексу из ports компонента.
     */
    fun getPinNumberForIndex(comp: Component, index: Int): String {
        return if (index < comp.ports.size) comp.ports[index].pinNumber else "${index + 1}"
    }
}

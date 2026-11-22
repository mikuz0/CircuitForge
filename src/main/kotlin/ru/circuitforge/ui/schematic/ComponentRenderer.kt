package ru.circuitforge.ui.schematic

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas
import javafx.scene.Group
import javafx.scene.control.Label
import javafx.scene.layout.Pane
import org.slf4j.LoggerFactory
import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.ui.schematic.symbols.SymbolRegistry
import ru.circuitforge.ui.schematic.symbols.SvgParser

/**
 * Рендерер компонентов для схемы.
 * Использует SVG-символы из SymbolRegistry с JSVG для рендеринга.
 */

object ComponentRenderer {
    private val logger = LoggerFactory.getLogger(ComponentRenderer::class.java)
    private val svgParser = SvgParser()

    /**
     * Отрисовка компонента с автоматическим определением типа символа.
     * Использует SymbolRegistry для маппинга Component.name → symbolType.
     */
    fun drawComponent(group: Group, row: BomRow, comp: Component) {
        val registry = SymbolRegistry.getInstance()
        val symbolType = registry.getSymbolType(comp.name)
        drawSvgSymbol(group, row, comp, symbolType)
    }

    /**
     * Общая функция отрисовки SVG-символа с подписями.
     * FXSVGCanvas рендерит SVGDocument напрямую в JavaFX.
     */
    private fun drawSvgSymbol(group: Group, row: BomRow, comp: Component, symbolType: String) {
        val registry = SymbolRegistry.getInstance()
        val originalSymbolPane = registry.getSymbol(symbolType) as Pane
        
        // Получаем SVGDocument и оригинальную строку
        val svgData = svgParser.getSvgData(originalSymbolPane)
        
        // Клонируем SVGDocument или создаём fallback
        val canvas = if (svgData != null) {
            try {
                val clonedDoc = svgParser.cloneSvgDocument(svgData.first, svgData.second)
                val canvas = svgParser.createFxSvgCanvas(clonedDoc)
                
                // Устанавливаем размеры
                val symbolWidth = originalSymbolPane.prefWidth.takeIf { it > 0.0 } ?: 100.0
                val symbolHeight = originalSymbolPane.prefHeight.takeIf { it > 0.0 } ?: 80.0
                canvas.prefWidth = symbolWidth
                canvas.prefHeight = symbolHeight
                canvas.minWidth = symbolWidth
                canvas.minHeight = symbolHeight
                canvas.maxWidth = symbolWidth
                canvas.maxHeight = symbolHeight
                
                canvas
            } catch (e: Exception) {
                logger.warn("Failed to clone SVG, using fallback: ${e.message}")
                svgParser.createEmptyCanvas()
            }
        } else {
            svgParser.createEmptyCanvas()
        }
        
        group.children.add(canvas)

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

        // Номера пинов — по реальным координатам из конфига
        val (symW, symH) = registry.getSymbolSize(comp.name)
        comp.ports.forEach { port ->
            val portPos = registry.getPortPosition(comp.name, port.pinNumber) ?: return@forEach
            val (px, py) = portPos
            // px/py — координаты SVG относительно top-left -> координаты внутри canvas
            val canvasX = px
            val canvasY = py
            val pinLabel = Label(port.pinNumber).apply {
                style = "-fx-font-size: 9px; -fx-text-fill: black;"
                layoutX = if (px < symW / 2) canvasX - 12.0 else canvasX + 4.0
                layoutY = canvasY - 4.0
            }
            group.children.add(pinLabel)
        }
    }

    fun getPinNumberForIndex(comp: Component, index: Int): String {
        return if (index < comp.ports.size) comp.ports[index].pinNumber else "${index + 1}"
    }
}

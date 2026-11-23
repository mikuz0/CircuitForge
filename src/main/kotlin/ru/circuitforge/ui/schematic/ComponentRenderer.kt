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
        
        // Проверяем, есть ли SVG-контент
        val svgData = svgParser.getSvgData(originalSymbolPane)
        val hasSvgContent = svgData != null && originalSymbolPane.children.isNotEmpty()
        
        // Вычисляем размеры
        var (symW, symH) = registry.getSymbolSize(comp.name)
        
        // Для fallback-компонентов рассчитываем размеры с контролем шага портов
        if (!hasSvgContent) {
            val portCount = comp.ports.size
            if (portCount > 2) {
                val minPinSpacing = 4 * 12.5
                val perSide = (portCount + 3) / 4
                symW = maxOf(perSide * minPinSpacing, 100.0)
                symH = maxOf(perSide * minPinSpacing, 80.0)
            }
        }
        
        val canvas = if (hasSvgContent) {
            try {
                val clonedDoc = svgParser.cloneSvgDocument(svgData.first, svgData.second)
                val fxCanvas = svgParser.createFxSvgCanvas(clonedDoc)
                fxCanvas.prefWidth = symW
                fxCanvas.prefHeight = symH
                fxCanvas.minWidth = symW
                fxCanvas.minHeight = symH
                fxCanvas.maxWidth = symW
                fxCanvas.maxHeight = symH
                fxCanvas
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
            layoutY = -symH / 2.0 - 20.0
        }
        group.children.add(refDesLabel)
        
        val commentLabel = Label(row.comment).apply {
            style = "-fx-font-size: 11px; -fx-text-fill: gray;"
            layoutX = -20.0
            layoutY = -symH / 2.0 - 5.0
        }
        group.children.add(commentLabel)
    }
    fun getPinNumberForIndex(comp: Component, index: Int): String {
        return if (index < comp.ports.size) comp.ports[index].pinNumber else "${index + 1}"
    }
}

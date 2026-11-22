package ru.circuitforge.ui.schematic

import org.slf4j.LoggerFactory
import ru.circuitforge.project.*
import ru.circuitforge.ui.schematic.symbols.SymbolLibrary
import ru.circuitforge.ui.schematic.symbols.SymbolRegistry
import ru.circuitforge.ui.schematic.symbols.SvgParser
import java.nio.file.Files
import java.nio.file.Path

class SchematicExporter {
    private val logger = LoggerFactory.getLogger(SchematicExporter::class.java)
    private val svgParser = SvgParser()

    fun exportToSvg(
        document: SchematicDocument,
        nets: List<Net>,
        componentsMap: Map<String, Component>,
        bomRows: List<BomRow>,
        symbolLibrary: SymbolLibrary,
        outputDir: Path
    ): Path {
        val (viewX, viewY, width, height) = computeViewBox(document)
        val svg = StringBuilder()
        svg.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        svg.appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"$viewX $viewY $width $height\">")

        svg.appendLine("<defs>")
        val usedSymbols = document.nodes.mapNotNull { node ->
            val row = bomRows.find { it.bomnetId == node.bomnetId } ?: return@mapNotNull null
            val comp = componentsMap[row.componentId] ?: return@mapNotNull null
            comp.name to symbolLibrary.getSymbolType(comp.name)
        }.distinctBy { it.second }.map { it.second }.toSet()

        for (symbolType in usedSymbols) {
            try {
                val symbolNode = symbolLibrary.getSymbolNode(symbolType)
                val svgContent = exportPaneToSvg(symbolNode as javafx.scene.layout.Pane)
                svg.appendLine("  <g id=\"sym-$symbolType\">$svgContent</g>")
            } catch (e: Exception) {
                logger.warn("Failed to export symbol $symbolType: ${e.message}")
            }
        }
        svg.appendLine("</defs>")

        svg.appendLine("<g id=\"components\">")
        for (node in document.nodes) {
            val row = bomRows.find { it.bomnetId == node.bomnetId } ?: continue
            val comp = componentsMap[row.componentId] ?: continue
            val symbolType = symbolLibrary.getSymbolType(comp.name)
            val transform = buildString {
                append("translate(${node.x}, ${node.y})")
                if (node.rotation != 0.0) {
                    append(" rotate(${node.rotation})")
                }
            }
            svg.appendLine("  <use href=\"#sym-$symbolType\" transform=\"$transform\"/>")
            svg.appendLine("  <text x=\"${node.x - 20}\" y=\"${node.y - 35}\" font-weight=\"bold\" font-size=\"12\" fill=\"black\">${row.refDes}</text>")
            svg.appendLine("  <text x=\"${node.x - 20}\" y=\"${node.y + 25}\" font-size=\"11\" fill=\"gray\">${row.comment}</text>")
        }
        svg.appendLine("</g>")

        svg.appendLine("<g id=\"wires\" fill=\"none\" stroke=\"black\" stroke-width=\"1.5\">")
        for (wire in document.wires) {
            val net = nets.find { it.name == wire.netName } ?: continue
            val color = netColorToHex(net.name)

            // Вызов с 5 аргументами (совпадает с сигнатурой ниже)
            val startCoord = getPinCoordinatesFromNode(document, wire.refDesFrom, wire.pinFrom, bomRows, componentsMap)
            val endCoord = getPinCoordinatesFromNode(document, wire.refDesTo, wire.pinTo, bomRows, componentsMap)

            if (startCoord != null && endCoord != null) {
                val path = buildString {
                    append("M ${startCoord.first} ${startCoord.second}")
                    for (bp in wire.bendPoints) {
                        append(" L ${bp.x} ${bp.y}")
                    }
                    append(" L ${endCoord.first} ${endCoord.second}")
                }
                svg.appendLine("  <path d=\"$path\" stroke=\"$color\"/>")
            }
        }
        svg.appendLine("</g>")
        svg.appendLine("</svg>")

        val outputFile = outputDir.resolve("schematic.svg")
        Files.createDirectories(outputDir)
        Files.writeString(outputFile, svg.toString())
        logger.info("Exported schematic to $outputFile")
        return outputFile
    }

    private fun computeViewBox(document: SchematicDocument): Tuple4<Double, Double, Double, Double> {
        if (document.nodes.isEmpty()) return Tuple4(0.0, 0.0, 800.0, 600.0)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (node in document.nodes) {
            minX = minOf(minX, node.x)
            minY = minOf(minY, node.y)
            maxX = maxOf(maxX, node.x)
            maxY = maxOf(maxY, node.y)
        }
        val padding = 100.0
        val width = (maxX - minX) + padding * 2
        val height = (maxY - minY) + padding * 2
        return Tuple4(minX - padding, minY - padding, width, height)
    }

    private fun exportPaneToSvg(pane: javafx.scene.layout.Pane): String {
        val svgData = svgParser.getSvgData(pane)
        return svgData?.second ?: ""
    }

    private fun getPinCoordinatesFromNode(
        document: SchematicDocument,
        refDes: String,
        pinNumber: String,
        bomRows: List<BomRow>,
        componentsMap: Map<String, Component>
    ): Pair<Double, Double>? {
        val row = bomRows.find { it.refDes == refDes } ?: return null
        val node = document.nodes.find { it.bomnetId == row.bomnetId } ?: return null
        val comp = componentsMap[row.componentId] ?: return null

        // Берем координаты из SymbolRegistry (который читает symbols.json)
        val registry = SymbolRegistry.getInstance()
        val portPos = registry.getPortPosition(comp.name, pinNumber)

        return if (portPos != null) {
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
            Pair(baseX, baseY)
        } else {
            // Fallback: центр компонента, если порт не найден в конфиге
            Pair(node.x, node.y)
        }
    }

    private fun netColorToHex(name: String): String {
        val hash = name.hashCode()
        val hue = (hash % 360 + 360) % 360.0 / 360.0
        val color = javafx.scene.paint.Color.hsb(hue * 360, 0.7, 0.8)
        return colorToHex(color)
    }

    private fun colorToHex(color: javafx.scene.paint.Paint?): String {
        if (color == null) return "#000000"
        val c = color as javafx.scene.paint.Color
        val r = ((c.red * 255.0).toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        val g = ((c.green * 255.0).toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        val b = ((c.blue * 255.0).toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        return "#$r$g$b"
    }
}

data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
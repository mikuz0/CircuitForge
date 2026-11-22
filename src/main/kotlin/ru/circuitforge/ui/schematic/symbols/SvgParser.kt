package ru.circuitforge.ui.schematic.symbols

import org.slf4j.LoggerFactory
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.layout.Pane
import javafx.scene.shape.*

/**
 * Парсер SVG-файлов в JavaFX Node.
 * Поддерживает: rect, line, path, circle, ellipse.
 */
class SvgParser {
    private val logger = LoggerFactory.getLogger(SvgParser::class.java)

    /**
     * Парсит SVG-строку в JavaFX Pane с дочерними узлами.
     */
    fun parse(svgString: String): Pane {
        // Возвращаем Pane (для совместимости с SymbolRegistry),
        // но внутри используем Group, чтобы JavaFX не сжимал фигуры
        val root = Pane().apply {
            isManaged = false
            isPickOnBounds = false
        }

        val group = Group()

        try {
            val elements = parseSvgElements(svgString)
            elements.forEach { element ->
                val node = parseElement(element)
                if (node != null) {
                    group.children.add(node)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse SVG: ${e.message}", e)
        }

        root.children.add(group)
        return root
    }

    /**
     * Парсит один SVG-элемент в JavaFX Node.
     */
    private fun parseElement(element: MutableMap<String, String>): Node? {
        return when (element["tag"]) {
            "rect" -> parseRect(element)
            "line" -> parseLine(element)
            "path" -> parsePath(element)
            "circle" -> parseCircle(element)
            "ellipse" -> parseEllipse(element)
            else -> null
        }
    }

    /**
     * Парсит <rect> элемент.
     */
    private fun parseRect(element: MutableMap<String, String>): Rectangle {
        val x = element["x"]?.toDoubleOrNull() ?: 0.0
        val y = element["y"]?.toDoubleOrNull() ?: 0.0
        val width = element["width"]?.toDoubleOrNull() ?: 0.0
        val height = element["height"]?.toDoubleOrNull() ?: 0.0

        return Rectangle(x, y, width, height).apply {
            fill = parseColor(element["fill"])
            stroke = parseColor(element["stroke"])
            strokeWidth = element["stroke-width"]?.toDoubleOrNull() ?: 1.0
        }
    }

    /**
     * Парсит <line> элемент.
     */
    private fun parseLine(element: Map<String, String>): Line {
        val x1 = element["x1"]?.toDoubleOrNull() ?: 0.0
        val y1 = element["y1"]?.toDoubleOrNull() ?: 0.0
        val x2 = element["x2"]?.toDoubleOrNull() ?: 0.0
        val y2 = element["y2"]?.toDoubleOrNull() ?: 0.0

        return Line(x1, y1, x2, y2).apply {
            stroke = parseColor(element["stroke"])
            strokeWidth = element["stroke-width"]?.toDoubleOrNull() ?: 1.0
        }
    }

    /**
     * Парсит <path> элемент (поддерживает basic commands: M, L, A, Z).
     */
    private fun parsePath(element: Map<String, String>): Path {
        val d = element["d"] ?: ""
        val path = Path()

        try {
            val commands = parsePathCommands(d)
            if (commands.isNotEmpty()) {
                path.elements.add(commands[0])
                for (i in 1 until commands.size) {
                    path.elements.add(commands[i])
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse path: $d", e)
        }

        path.stroke = parseColor(element["stroke"])
        path.fill = parseColor(element["fill"])
        path.strokeWidth = element["stroke-width"]?.toDoubleOrNull() ?: 1.0
        path.strokeLineJoin = StrokeLineJoin.ROUND

        return path
    }

    /**
     * Парсит команды пути (M, L, A, Z).
     */
    private fun parsePathCommands(d: String): List<PathElement> {
        val elements = mutableListOf<PathElement>()
        val tokens = d.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]

            when {
                token.uppercase() == "M" -> {
                    val x = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
                    val y = tokens.getOrNull(i + 2)?.toDoubleOrNull() ?: 0.0
                    elements.add(MoveTo(x, y))
                    i += 3
                }
                token.uppercase() == "L" -> {
                    val x = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
                    val y = tokens.getOrNull(i + 2)?.toDoubleOrNull() ?: 0.0
                    elements.add(LineTo(x, y))
                    i += 3
                }
                token.uppercase() == "A" -> {
                    val rx = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
                    val ry = tokens.getOrNull(i + 2)?.toDoubleOrNull() ?: 0.0
                    val xAxisRotation = tokens.getOrNull(i + 3)?.toDoubleOrNull() ?: 0.0
                    val largeArc = tokens.getOrNull(i + 4)?.toIntOrNull() ?: 0
                    val sweep = tokens.getOrNull(i + 5)?.toIntOrNull() ?: 0
                    val x = tokens.getOrNull(i + 6)?.toDoubleOrNull() ?: 0.0
                    val y = tokens.getOrNull(i + 7)?.toDoubleOrNull() ?: 0.0

                    elements.add(ArcTo(rx, ry, xAxisRotation, x, y, sweep == 1, largeArc == 1))
                    i += 8
                }
                token.uppercase() == "Z" -> {
                    elements.add(ClosePath())
                    i += 1
                }
                else -> {
                    i++
                }
            }
        }

        return elements
    }

    /**
     * Парсит <circle> элемент.
     */
    private fun parseCircle(element: Map<String, String>): Circle {
        val cx = element["cx"]?.toDoubleOrNull() ?: 0.0
        val cy = element["cy"]?.toDoubleOrNull() ?: 0.0
        val r = element["r"]?.toDoubleOrNull() ?: 0.0

        return Circle(cx, cy, r).apply {
            fill = parseColor(element["fill"])
            stroke = parseColor(element["stroke"])
            strokeWidth = element["stroke-width"]?.toDoubleOrNull() ?: 1.0
        }
    }

    /**
     * Парсит <ellipse> элемент.
     */
    private fun parseEllipse(element: Map<String, String>): Ellipse {
        val cx = element["cx"]?.toDoubleOrNull() ?: 0.0
        val cy = element["cy"]?.toDoubleOrNull() ?: 0.0
        val rx = element["rx"]?.toDoubleOrNull() ?: 0.0
        val ry = element["ry"]?.toDoubleOrNull() ?: 0.0

        return Ellipse(cx, cy, rx, ry).apply {
            fill = parseColor(element["fill"])
            stroke = parseColor(element["stroke"])
            strokeWidth = element["stroke-width"]?.toDoubleOrNull() ?: 1.0
        }
    }

    /**
     * Парсит цвет из SVG-атрибута.
     */
    private fun parseColor(colorStr: String?): javafx.scene.paint.Paint? {
        return when {
            colorStr == null || colorStr == "none" -> null
            colorStr.startsWith("#") -> javafx.scene.paint.Color.web(colorStr)
            colorStr == "black" -> javafx.scene.paint.Color.BLACK
            colorStr == "white" -> javafx.scene.paint.Color.WHITE
            else -> null
        }
    }

    /**
     * Парсит SVG-строку на элементы (упрощённый парсер).
     */
    private fun parseSvgElements(svgString: String): List<MutableMap<String, String>> {
        val elements = mutableListOf<MutableMap<String, String>>()

        val tagRegex = Regex("""<(\w+)([^>]+)?>""")
        val matches = tagRegex.findAll(svgString)

        matches.forEach { match ->
            val tag = match.groupValues[1]
            val attributesStr = match.groupValues[2]
            val attributes = parseAttributes(attributesStr).toMutableMap()
            attributes["tag"] = tag
            elements.add(attributes)
        }

        return elements
    }

    /**
     * Парсит атрибуты SVG-элемента.
     */
    private fun parseAttributes(attributesStr: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        val attrRegex = Regex("""(\w+)="([^"]*)"""")
        val matches = attrRegex.findAll(attributesStr)

        matches.forEach { match ->
            attributes[match.groupValues[1]] = match.groupValues[2]
        }

        return attributes
    }
}

package ru.circuitforge.ui.schematic.symbols

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas
import org.slf4j.LoggerFactory
import javafx.scene.layout.Pane
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Парсер SVG-файлов в JavaFX Node с использованием JSVG + FXSVGCanvas.
 * Поддерживает полный спектр SVG-элементов.
 */
class SvgParser {
    private val logger = LoggerFactory.getLogger(SvgParser::class.java)
    private val svgLoader = SVGLoader()

    /**
     * Парсит SVG-строку в JavaFX Pane с FXSVGCanvas внутри.
     */
    fun parse(svgString: String, width: Double = 100.0, height: Double = 100.0): Pane {
        return try {
            val svgDocument = loadSvgDocument(svgString)
            val canvas = createFxSvgCanvas(svgDocument)
            
            Pane().apply {
                userData = Pair(svgDocument, svgString)  // Document + original string
                children.add(canvas)
                prefWidth = width
                prefHeight = height
            }
        } catch (e: Exception) {
            logger.error("Failed to parse SVG: ${e.message}", e)
            Pane().apply {
                prefWidth = width
                prefHeight = height
            }
        }
    }

    /**
     * Загружает SVGDocument из строки.
     */
    fun loadSvgDocument(svgString: String): SVGDocument {
        return try {
            val inputStream = ByteArrayInputStream(svgString.toByteArray(StandardCharsets.UTF_8))
            svgLoader.load(inputStream, null, LoaderContext.createDefault())
                ?: throw IllegalStateException("Failed to load SVGDocument")
        } catch (e: Exception) {
            logger.warn("Failed to load SVG from string: ${e.message}")
            throw IllegalStateException("Cannot load SVGDocument", e)
        }
    }

    /**
     * Создаёт FXSVGCanvas для SVGDocument.
     */
    fun createFxSvgCanvas(document: SVGDocument): FXSVGCanvas {
        return FXSVGCanvas().apply {
            renderBackend = FXSVGCanvas.RenderBackend.JavaFX
            this.document = document
        }
    }

    /**
     * Получает SVGDocument и оригинальную строку из Pane.
     */
    fun getSvgData(pane: Pane): Pair<SVGDocument, String>? {
        val data = pane.userData as? Pair<SVGDocument, String> ?: return null
        return data
    }

    /**
     * Создаёт пустой FXSVGCanvas с указанными размерами.
     */
    fun createEmptyCanvas(width: Double = 100.0, height: Double = 100.0): FXSVGCanvas {
        return FXSVGCanvas().apply {
            renderBackend = FXSVGCanvas.RenderBackend.JavaFX
            prefWidth = width
            prefHeight = height
        }
    }

    /**
     * Клонирует SVGDocument через оригинальную строку.
     */
    fun cloneSvgDocument(originalDoc: SVGDocument, originalString: String): SVGDocument {
        return try {
            loadSvgDocument(originalString)
        } catch (e: Exception) {
            logger.warn("Failed to clone SVGDocument: ${e.message}", e)
            throw IllegalStateException("Cannot clone SVGDocument", e)
        }
    }

    /**
     * Сериализует SVGDocument в строку (из кэша).
     */
    fun serializeSvgDocument(document: SVGDocument, cachedString: String): String {
        return cachedString
    }

    /**
     * Создаёт пустой SVGDocument с указанными размерами.
     */
    fun createEmptySVGDocument(width: Float, height: Float): SVGDocument {
        // Создаём минимальную SVG-строку и загружаем её
        val minimalSvg = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
</svg>"""
        return loadSvgDocument(minimalSvg)
    }
}

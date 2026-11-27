package ru.circuitforge.ui.schematic.symbols

import javafx.scene.Node
import javafx.scene.layout.Pane
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Реестр SVG-символов компонентов.
 * Использует SymbolLibrary для загрузки из конфига проекта.
 * Fallback — загрузка из resources.
 */
class SymbolRegistry private constructor(
    private val symbolLibrary: SymbolLibrary? = null,
) {
    private val logger = LoggerFactory.getLogger(SymbolRegistry::class.java)

    companion object {
        @Volatile
        private var instance: SymbolRegistry? = null

        @Synchronized
        fun getInstance(): SymbolRegistry {
            return instance ?: synchronized(this) {
                instance ?: SymbolRegistry().also { instance = it }
            }
        }

        @Synchronized
        fun initialize(projectDir: Path?) {
            instance = projectDir?.let {
                SymbolRegistry(SymbolLibrary.fromProjectDir(it))
            } ?: SymbolRegistry()
        }
    }

    /**
     * Получает SVG-символ по имени компонента.
     * Пытается загрузить из SymbolLibrary, fallback — на resources.
     */
    fun getSymbol(compName: String): Node {
        // Сначала пробуем через SymbolLibrary
        symbolLibrary?.let { lib ->
            try {
                val symbolType = lib.getSymbolType(compName)
                val pane = lib.getSymbolNode(symbolType)

                // Проверяем, не пустой ли Pane (fallback)
                if (pane.children.isNotEmpty() || pane.style.isNotBlank()) {
                    logger.debug("Loaded symbol $compName (type=$symbolType) from SymbolLibrary")
                    return pane
                }

                logger.debug("SymbolLibrary returned empty pane for $compName (type=$symbolType), trying resources")
            } catch (e: Exception) {
                logger.debug("SymbolLibrary failed for $compName: ${e.message}", e)
            }
        }

        // Fallback — загрузка из resources через старый метод
        return loadFromResources(compName)
    }

    /**
     * Получает symbolType по имени компонента.
     * Использует mappings из SymbolLibrary, fallback — lowercase name.
     */
    fun getSymbolType(componentName: String): String {
        return symbolLibrary?.getSymbolType(componentName)
            ?: componentName.lowercase()
    }

    /**
     * Получает размеры символа.
     */
    fun getSymbolSize(compName: String): Pair<Double, Double> {
        symbolLibrary?.let { lib ->
            val symbolType = lib.getSymbolType(compName)
            lib.getSymbolSize(symbolType)?.let { return it }
        }
        return Pair(100.0, 80.0) // fallback
    }

    /**
     * Получает позицию порта (x, y) относительно центра символа.
     */
    fun getPortPosition(
        compName: String,
        pinNumber: String,
    ): Pair<Double, Double>? {
        return symbolLibrary?.let { lib ->
            val symbolType = lib.getSymbolType(compName)
            lib.getPortPosition(symbolType, pinNumber)
        }
    }

    /**
     * Загружает SVG из resources (fallback).
     */
    private fun loadFromResources(compName: String): Node {
        val registry = SymbolRegistry()
        val key = compName.lowercase().trim()
        val resourcePath = "/symbols/$key.svg"

        return try {
            val inputStream = javaClass.getResourceAsStream(resourcePath)
            if (inputStream == null) {
                logger.debug("SVG not found in resources: $resourcePath")
                Pane()
            } else {
                val svgString =
                    inputStream.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                val parser = SvgParser()
                val pane = parser.parse(svgString)
                pane.prefWidth = 100.0
                pane.prefHeight = 80.0
                pane.style = "-fx-background-color: transparent;"
                pane
            }
        } catch (e: Exception) {
            logger.error("Failed to load SVG symbol: $compName", e)
            Pane()
        }
    }
}

package ru.circuitforge.ui.schematic.symbols

import org.slf4j.LoggerFactory
import javafx.scene.Node
import javafx.scene.layout.Pane

/**
 * Реестр SVG-символов компонентов.
 * Загружает SVG из ресурсов по имени компонента, кэширует прототипы.
 */
class SymbolRegistry private constructor() {
    private val logger = LoggerFactory.getLogger(SymbolRegistry::class.java)
    private val parser = SvgParser()
    
    // Кэш: componentName -> Node (прототип)
    private val cache = mutableMapOf<String, Node>()
    
    companion object {
        @Volatile
        private var instance: SymbolRegistry? = null
        
        @Synchronized
        fun getInstance(): SymbolRegistry {
            return instance ?: synchronized(this) {
                instance ?: SymbolRegistry().also { instance = it }
            }
        }
    }

    /**
     * Получает SVG-символ по имени компонента.
     * Если символ не найден — возвращает default.svg.
     */
    fun getSymbol(compName: String): Node {
        val key = compName.lowercase().trim()
        
        return cache.getOrPut(key) {
            loadSymbol(key) ?: loadSymbol("default") ?: Pane()
        }
    }

    /**
     * Получает размеры символа (bounding box).
     */
    fun getSymbolSize(compName: String): Pair<Double, Double> {
        val symbol = getSymbol(compName) as javafx.scene.layout.Pane
        return Pair(symbol.prefWidth, symbol.prefHeight)
    }

    /**
     * Загружает SVG-символ из ресурсов.
     */
    private fun loadSymbol(name: String): Node? {
        try {
            val resourcePath = "/symbols/$name.svg"
            val inputStream = javaClass.getResourceAsStream(resourcePath)
            
            if (inputStream == null) {
                logger.debug("SVG not found: $resourcePath")
                return null
            }
            
            val svgString = inputStream.use { 
                it.readBytes().toString(Charsets.UTF_8) 
            }
            
            val pane = parser.parse(svgString)
            pane.prefWidth = 100.0
            pane.prefHeight = 80.0
            pane.style = "-fx-background-color: transparent;"
            
            logger.debug("Loaded SVG symbol: $name")
            return pane
        } catch (e: Exception) {
            logger.error("Failed to load SVG symbol: $name", e)
            return null
        }
    }

    /**
     * Очищает кэш (для отладки).
     */
    fun clearCache() {
        cache.clear()
    }
}

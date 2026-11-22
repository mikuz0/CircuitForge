package ru.circuitforge.ui.schematic.symbols

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import javafx.scene.layout.Pane
import ru.circuitforge.ui.schematic.symbols.SvgParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Библиотека SVG-символов компонентов.
 * Загружает конфиг из symbols.json, кэширует SVG-узлы и порты.
 */
class SymbolLibrary private constructor(
    private val symbolsDir: Path,
    private val config: SymbolConfig
) {
    private val logger = LoggerFactory.getLogger(SymbolLibrary::class.java)
    private val parser = SvgParser()
    
    private val symbolCache = mutableMapOf<String, Pane>()
    private val sizeCache = mutableMapOf<String, Pair<Double, Double>>()
    
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        
        /**
         * Создаёт SymbolLibrary из директории проекта.
         * Пытается загрузить symbols.json, если нет — использует пустой конфиг.
         */
        fun fromProjectDir(projectDir: Path): SymbolLibrary {
            val symbolsDir = projectDir.resolve("symbols")
            val configFile = symbolsDir.resolve("symbols.json")
            
            val config = try {
                if (Files.exists(configFile)) {
                    val content = Files.readString(configFile)
                    json.decodeFromString(SymbolConfig.serializer(), content)
                } else {
                    SymbolConfig()
                }
            } catch (e: Exception) {
                LoggerFactory.getLogger(SymbolLibrary::class.java)
                    .warn("Failed to load symbol config from $configFile: ${e.message}, using empty config")
                SymbolConfig()
            }
            
            return SymbolLibrary(symbolsDir, config)
        }
    }
    
    /**
     * Получает symbolType по имени компонента.
     * Использует mappings из конфига, fallback — lowercase имя.
     */
    fun getSymbolType(componentName: String): String {
        val mappings = config.mappings
        return mappings[componentName]
            ?: mappings[componentName.lowercase()]
            ?: componentName.lowercase()
    }
    
    /**
     * Получает размер символа (ширина, высота).
     */
    fun getSymbolSize(symbolType: String): Pair<Double, Double>? {
        config.symbols[symbolType]?.let {
            return sizeCache.getOrPut(symbolType) {
                Pair(it.width, it.height)
            }
        }
        return null
    }
    
    /**
     * Получает позицию порта (x, y) относительно центра символа.
     */
    fun getPortPosition(symbolType: String, pinNumber: String): Pair<Double, Double>? {
        return config.symbols[symbolType]?.ports?.get(pinNumber)?.let {
            Pair(it.x, it.y)
        }
    }
    
    /**
     * Получает SVG-узел символа (с кэшированием).
     */
    fun getSymbolNode(symbolType: String): Pane {
        return symbolCache.getOrPut(symbolType) {
            loadSymbol(symbolType)
        }
    }
    
    /**
     * Загружает SVG-символ из файла.
     * Если нет в конфиге, пытается загрузить из resources.
     */
    private fun loadSymbol(symbolType: String): Pane {
        val definition = config.symbols[symbolType]
        
        return if (definition != null) {
            // Загружаем из project symbols
            val svgFile = symbolsDir.resolve(definition.file)
            loadSymbolFromFile(symbolType, svgFile, definition.width, definition.height)
        } else {
            // Fallback: загружаем из resources
            val resourcePane = loadSymbolFromResources(symbolType)
            resourcePane ?: createFallbackPane(symbolType)
        }
    }
    
    /**
     * Загружает SVG-символ из файла.
     */
    private fun loadSymbolFromFile(symbolType: String, filePath: Path, width: Double, height: Double): Pane {
        return try {
            logger.info("Loading symbol $symbolType from $filePath")
            if (Files.exists(filePath)) {
                val svgContent = Files.readString(filePath)
                logger.debug("SVG content length for $symbolType: ${svgContent.length} chars")
                val pane = parser.parse(svgContent)
                pane.prefWidth = width
                pane.prefHeight = height
                logger.info("Successfully loaded symbol $symbolType: ${pane.children.size} children")
                pane
            } else {
                logger.warn("SVG file not found: $filePath")
                createFallbackPane(symbolType)
            }
        } catch (e: Exception) {
            logger.error("Failed to load symbol $symbolType from $filePath", e)
            createFallbackPane(symbolType)
        }
    }
    
    /**
     * Загружает SVG-символ из resources.
     */
    private fun loadSymbolFromResources(symbolType: String): Pane? {
        val resourcePath = "/symbols/${symbolType}.svg"
        val inputStream = javaClass.getResourceAsStream(resourcePath) ?: return null
        
        return try {
            val svgContent = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val pane = parser.parse(svgContent)
            pane.prefWidth = 80.0
            pane.prefHeight = 40.0
            logger.debug("Loaded symbol $symbolType from resources: $resourcePath")
            pane
        } catch (e: Exception) {
            logger.warn("Failed to load symbol from resources: $resourcePath", e)
            null
        }
    }
    
    /**
     * Создаёт fallback Pane с размерами из конфига.
     */
    private fun createFallbackPane(symbolType: String): Pane {
        val definition = config.symbols[symbolType]
        val width = definition?.width ?: 80.0
        val height = definition?.height ?: 40.0
        
        // Создаём пустой SVGDocument для userData
        val emptyDoc = parser.createEmptySVGDocument(width.toFloat(), height.toFloat())
        
        return Pane().apply {
            prefWidth = width
            prefHeight = height
            style = "-fx-background-color: transparent;"
            userData = emptyDoc  // Для совместимости с ComponentRenderer
        }
    }
    
    /**
     * Очищает кэш (для отладки).
     */
    fun clearCache() {
        symbolCache.clear()
        sizeCache.clear()
    }
}

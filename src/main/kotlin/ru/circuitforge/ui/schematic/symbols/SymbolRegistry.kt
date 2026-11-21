package ru.circuitforge.ui.schematic.symbols

import org.slf4j.LoggerFactory
import javafx.scene.Node
import javafx.scene.layout.Pane
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Реестр SVG-символов компонентов.
 * Загружает SVG из ресурсов по имени компонента, кэширует прототипы.
 * Автоматически обновляет кэш при изменении файлов SVG.
 */
class SymbolRegistry private constructor() {
    private val logger = LoggerFactory.getLogger(SymbolRegistry::class.java)
    private val parser = SvgParser()
    
    // Кэш: componentName -> Node (прототип)
    private val cache = mutableMapOf<String, Node>()
    
    // Кэш timestamp файлов: resourcePath -> lastModified
    private val fileTimestamps = mutableMapOf<String, Long>()
    
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
     * Автоматически проверяет timestamp файла и перезагружает при изменении.
     */
    fun getSymbol(compName: String): Node {
        val key = compName.lowercase().trim()
        val resourcePath = "/symbols/$key.svg"
        
        // Проверяем, изменился ли файл
        val currentTimestamp = getFileTimestamp(resourcePath)
        val cachedTimestamp = fileTimestamps[resourcePath]
        
        if (cache.containsKey(key) && cachedTimestamp == currentTimestamp) {
            // Файл не изменился, возвращаем кэшированный символ
            return cache[key]!!
        }
        
        // Файл изменился или отсутствует в кэше — перезагружаем
        val symbol = loadSymbol(key) ?: loadSymbol("default") ?: Pane()
        cache[key] = symbol
        fileTimestamps[resourcePath] = currentTimestamp
        
        return symbol
    }

    /**
     * Получает размеры символа (bounding box).
     */
    fun getSymbolSize(compName: String): Pair<Double, Double> {
        val symbol = getSymbol(compName) as Pane
        return Pair(symbol.prefWidth, symbol.prefHeight)
    }

    /**
     * Получает timestamp файла ресурса.
     */
    private fun getFileTimestamp(resourcePath: String): Long {
        try {
            // Пытаемся найти файл в файловой системе
            val classLoader = javaClass.classLoader
            val url = classLoader.getResource(resourcePath.trimStart('/'))
            url?.let {
                val path = Paths.get(url.toURI())
                return Files.getLastModifiedTime(path).toMillis()
            }
        } catch (e: Exception) {
            logger.debug("Could not get file timestamp for $resourcePath: ${e.message}")
        }
        
        // Если не удалось получить timestamp — используем хэш содержимого
        return getResourceContentHash(resourcePath)
    }

    /**
     * Вычисляет простой хэш содержимого ресурса.
     */
    private fun getResourceContentHash(resourcePath: String): Long {
        try {
            val inputStream = javaClass.getResourceAsStream(resourcePath)
            inputStream?.use { stream ->
                var hash = 0L
                val buffer = ByteArray(1024)
                var bytesRead = 0
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    for (i in 0 until bytesRead) {
                        hash = (hash * 31 + buffer[i].toLong()) and 0xFFFFFFFFL
                    }
                }
                return hash
            }
        } catch (e: Exception) {
            logger.debug("Could not compute hash for $resourcePath: ${e.message}")
        }
        
        // Fallback — возвращаем текущее время
        return System.currentTimeMillis()
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
        fileTimestamps.clear()
    }
}

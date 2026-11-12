package ru.circuitforge.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Репозиторий для управления netlist документом (nets.json).
 * Отвечает за загрузку, сохранение и валидацию связей.
 */
object NetRepository {
    private val logger = LoggerFactory.getLogger(NetRepository::class.java)
    private const val NETS_FILE_NAME = "nets.json"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Загружает netlist документ. Возвращает пустой при отсутствии. */
    fun load(projectPath: Path): NetDocument {
        val netsPath = projectPath.resolve(NETS_FILE_NAME)
        return try {
            val content = Files.readString(netsPath)
            json.decodeFromString(NetDocument.serializer(), content)
        } catch (e: Exception) {
            logger.warn("No nets file found at $netsPath, using empty document", e)
            NetDocument()
        }
    }

    /**
     * Сохраняет netlist документ с валидацией.
     * @throws IllegalArgumentException если валидация не пройдена
     */
    @Throws(IllegalArgumentException::class)
    fun save(projectPath: Path, doc: NetDocument) {
        val errors = validate(doc, projectPath)
        if (errors.isNotEmpty()) {
            val msg = "Validation errors:\n" + errors.joinToString("\n")
            throw IllegalArgumentException(msg)
        }

        try {
            val netsPath = projectPath.resolve(NETS_FILE_NAME)
            Files.createDirectories(netsPath.parent)
            val jsonStr = json.encodeToString(NetDocument.serializer(), doc)
            Files.writeString(netsPath, jsonStr)
            logger.info("Netlist saved to $netsPath")
        } catch (e: Exception) {
            logger.error("Failed to save netlist to ${projectPath.resolve(NETS_FILE_NAME)}", e)
            throw e
        }
    }

    /**
     * Валидирует netlist документ.
     * @return список сообщений об ошибках (пустой если всё ок)
     */
    fun validate(doc: NetDocument, projectPath: Path): List<String> {
        val errors = mutableListOf<String>()

        // 1. Уникальность имён цепей
        val netNames = doc.nets.map { it.name }
        val duplicateNames = netNames.groupingBy { it }.eachCount().filter { it.value > 1 }
        duplicateNames.keys.forEach { name ->
            errors.add("Duplicate net name: '$name'")
        }

        // 2. Загружаем BOM для проверки refDes
        val bomDoc = BomRepository.load(projectPath)
        val bomRefDes = bomDoc.items.map { it.refDes }.toSet()

        // 3. Загружаем компоненты для проверки pinNumber
        val componentsDir = projectPath.resolve("components")
        val componentCache = mutableMapOf<String, Component?>()

        doc.nets.forEach { net ->
            net.connections.forEach { conn ->
                // Проверяем refDes
                if (conn.refDes.isBlank()) {
                    errors.add("Net '${net.name}': empty refDes in connection")
                    return@forEach
                }
                if (!bomRefDes.contains(conn.refDes)) {
                    errors.add("Net '${net.name}': refDes '${conn.refDes}' not found in BOM")
                    return@forEach
                }

                // Проверяем pinNumber
                if (conn.pinNumber.isBlank()) {
                    errors.add("Net '${net.name}': empty pinNumber for refDes '${conn.refDes}'")
                    return@forEach
                }

                val component = componentCache.getOrPut(conn.refDes) {
                    try {
                        // Ищем компонент по slug из BOM
                        val bomItem = bomDoc.items.find { it.refDes == conn.refDes }
                        if (bomItem != null) {
                            ComponentRepository.loadComponent(projectPath, bomItem.componentSlug)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                component?.let { comp ->
                    val pinNumbers = comp.ports.map { it.pinNumber }.toSet()
                    if (!pinNumbers.contains(conn.pinNumber)) {
                        errors.add("Net '${net.name}': pinNumber '${conn.pinNumber}' not found in component '${conn.refDes}'")
                    }
                }
                // Если компонент не найден — пропускаем проверку pinNumber (о refDes уже сообщено)
            }
        }

        return errors
    }

    /**
     * Экспортирует netlist в CSV-матрицу.
     * Строки = цепи, колонки = все уникальные PortRef (refDes.pinNumber),
     * ячейки = "X" если связь существует.
     */
    fun exportToCsv(projectPath: Path, outputPath: Path) {
        val doc = load(projectPath)
        if (doc.nets.isEmpty()) {
            logger.warn("Nothing to export: no nets defined")
            return
        }

        // Собираем все уникальные PortRef
        val portRefs = mutableSetOf<String>()
        doc.nets.forEach { net ->
            net.connections.forEach { conn ->
                portRefs.add("${conn.refDes}.${conn.pinNumber}")
            }
        }
        val sortedPortRefs = portRefs.sorted()

        // Формируем CSV
        val lines = mutableListOf<String>()
        // Заголовок
        lines.add("Net,${sortedPortRefs.joinToString(",")}")

        // Строки
        doc.nets.forEach { net ->
            val values = sortedPortRefs.map { portRef ->
                val (refDes, pinNumber) = portRef.split(".", limit = 2)
                if (net.connections.any { it.refDes == refDes && it.pinNumber == pinNumber }) {
                    "X"
                } else {
                    ""
                }
            }
            lines.add("${net.name},${values.joinToString(",")}")
        }

        try {
            Files.createDirectories(outputPath.parent)
            Files.writeString(outputPath, lines.joinToString("\n"))
            logger.info("Netlist exported to CSV: $outputPath")
        } catch (e: Exception) {
            logger.error("Failed to export netlist to CSV: $outputPath", e)
            throw e
        }
    }
}

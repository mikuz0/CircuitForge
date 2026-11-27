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
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /** Загружает netlist документ. Возвращает пустой при отсутствии. */
    fun load(paths: ProjectPaths): NetDocument {
        val netsPath = paths.nets
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
    fun save(
        paths: ProjectPaths,
        doc: NetDocument,
    ) {
        val errors = validate(doc, paths)
        if (errors.isNotEmpty()) {
            val msg = "Validation errors:\n" + errors.joinToString("\n")
            throw IllegalArgumentException(msg)
        }

        try {
            val netsPath = paths.nets
            Files.createDirectories(netsPath.parent)
            val jsonStr = json.encodeToString(NetDocument.serializer(), doc)
            Files.writeString(netsPath, jsonStr)
            logger.info("Netlist saved to $netsPath")
        } catch (e: Exception) {
            logger.error("Failed to save netlist to ${paths.nets}", e)
            throw e
        }
    }

    /**
     * Валидирует netlist документ.
     * @return список сообщений об ошибках (пустой если всё ок)
     */
    fun validate(
        doc: NetDocument,
        paths: ProjectPaths,
    ): List<String> {
        val errors = mutableListOf<String>()

        val netNames = doc.nets.map { it.name }
        val duplicateNames = netNames.groupingBy { it }.eachCount().filter { it.value > 1 }
        duplicateNames.keys.forEach { name ->
            errors.add("Duplicate net name: '$name'")
        }

        val bomDoc = BomRepository.load(paths)
        val bomRefDes = bomDoc.items.map { it.refDes }.toSet()

        val componentsDir = paths.componentsDir
        val componentCache = mutableMapOf<String, Component?>()

        doc.nets.forEach { net ->
            net.connections.forEach { conn ->
                if (conn.refDes.isBlank()) {
                    errors.add("Net '${net.name}': empty refDes in connection")
                    return@forEach
                }
                if (!bomRefDes.contains(conn.refDes)) {
                    errors.add("Net '${net.name}': refDes '${conn.refDes}' not found in BOM")
                    return@forEach
                }

                if (conn.pinNumber.isBlank()) {
                    errors.add("Net '${net.name}': empty pinNumber for refDes '${conn.refDes}'")
                    return@forEach
                }

                val component =
                    componentCache.getOrPut(conn.refDes) {
                        try {
                            val bomItem = bomDoc.items.find { it.refDes == conn.refDes }
                            if (bomItem != null) {
                                ComponentRepository.loadComponent(paths.projectDir, bomItem.componentSlug)
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
            }
        }

        return errors
    }

    /**
     * Экспортирует netlist в CSV-матрицу.
     * Строки = цепи, колонки = все уникальные PortRef (refDes.pinNumber),
     * ячейки = "X" если связь существует.
     */
    fun exportToCsv(
        paths: ProjectPaths,
        outputPath: Path,
    ) {
        val doc = load(paths)
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
            val values =
                sortedPortRefs.map { portRef ->
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

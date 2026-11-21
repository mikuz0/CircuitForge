package ru.circuitforge.ui.schematic.layout

import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.graph.ElkGraphFactory
import org.eclipse.elk.graph.util.ElkGraphUtil
import ru.circuitforge.project.*
import java.io.File

/**
 * Движок для запуска ELK layout и преобразования результатов в SchematicDocument.
 * Использует ELK API напрямую для выполнения layout.
 */
class ElkLayoutEngine {
    
    /**
     * Запускает ELK layout и преобразует в SchematicDocument.
     */
    fun layout(
        bomRows: List<BomRow>,
        nets: List<Net>,
        componentsMap: Map<String, Component>
    ): SchematicDocument {
        // Строим ELK-граф
        val graphBuilder = ElkGraphBuilder()
        val elkGraph = graphBuilder.build(bomRows, nets, componentsMap)
        
        // Запускаем layout через Reflection API
        runLayout(elkGraph)
        
        // Парсим результаты
        val nodes = parseNodes(elkGraph, bomRows)
        val wires = parseWires(elkGraph, nets, bomRows)
        
        return SchematicDocument(nodes = nodes, wires = wires)
    }
    
    /**
     * Запускает ELK layout engine.
     */
    private fun runLayout(root: org.eclipse.elk.graph.ElkNode) {
        // Используем Reflection для вызова LayoutConfigurator
        try {
            val configuratorClass = Class.forName("org.eclipse.elk.core.LayoutConfigurator")
            
            // Пробуем разные варианты получения экземпляра
            val configurator = try {
                // ELK 0.9.x - используем getInstance()
                val instanceMethod = configuratorClass.getMethod("getInstance")
                instanceMethod.invoke(null)
            } catch (e: NoSuchMethodException) {
                // ELK 0.8.x - используем конструктор
                configuratorClass.getDeclaredConstructor().newInstance()
            }
            
            // Устанавливаем свойства через options map
            try {
                val optionsProperty = configuratorClass.getField("options")
                val options = optionsProperty.get(configurator)
                
                // Получаем LayeredOptions.DIRECTION
                val layeredOptionsClass = Class.forName("org.eclipse.elk.alg.layered.options.LayeredOptions")
                val directionField = layeredOptionsClass.getField("DIRECTION")
                val directionProperty = directionField.get(null)
                
                // Получаем Direction.WEST_TO_EAST
                val directionClass = Class.forName("org.eclipse.elk.core.options.Direction")
                val westToEastField = directionClass.getField("WEST_TO_EAST")
                val westToEast = westToEastField.get(null)
                
                @Suppress("UNCHECKED_CAST")
                val optionsMap = options as MutableMap<Any?, Any?>
                optionsMap[directionProperty] = westToEast
            } catch (e: Exception) {
                // Игнорируем ошибки установки свойств - используются значения по умолчанию
                println("Warning: Could not set ELK options: ${e.message}")
            }
            
            // Вызываем layout
            val layoutMethod = configuratorClass.getMethod("layout", org.eclipse.elk.graph.ElkGraphElement::class.java)
            layoutMethod.invoke(configurator, root)
            
        } catch (e: ClassNotFoundException) {
            throw RuntimeException("ELK classes not found: ${e.message}", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to run ELK layout: ${e.message}", e)
        }
    }
    
    /**
     * Парсит координаты узлов из ELK-графа.
     */
    private fun parseNodes(
        root: org.eclipse.elk.graph.ElkNode,
        bomRows: List<BomRow>
    ): List<SchematicNode> {
        val result = mutableListOf<SchematicNode>()
        
        // Проходим по всем дочерним узлам
        for (child in root.children) {
            val bomnetId = child.identifier
            val bomRow = bomRows.find { it.bomnetId == bomnetId }
            
            if (bomRow != null) {
                // Получаем координаты из ELK-графа
                val x = child.x ?: 0.0
                val y = child.y ?: 0.0
                
                result.add(SchematicNode(
                    bomnetId = bomnetId,
                    x = x,
                    y = y
                ))
            }
        }
        
        return result
    }
    
    /**
     * Парсит провода (wires) из ELK-графа с bend points.
     */
    private fun parseWires(
        root: org.eclipse.elk.graph.ElkNode,
        nets: List<Net>,
        bomRows: List<BomRow>
    ): List<WireConnection> {
        val result = mutableListOf<WireConnection>()
        
        // Map refDes -> bomnetId
        val refDesToBomnetId = bomRows.associateBy { it.refDes }
        
        // Проходим по всем рёбрам в корневом узле
        for (edge in root.containedEdges) {
            val edgeId = edge.identifier ?: continue
            
            // Парсим net name из edge identifier (format: "netName_edge_i")
            val netName = edgeId.substringBefore("_edge_")
            
            // Получаем source и target nodes
            val sourceNode = edge.sources.firstOrNull()
            val targetNode = edge.targets.lastOrNull()
            
            if (sourceNode == null || targetNode == null) continue
            
            val sourceBomnetId = sourceNode.identifier
            val targetBomnetId = targetNode.identifier
            
            // Находим refDes по bomnetId
            val sourceRefDes = bomRows.find { it.bomnetId == sourceBomnetId }?.refDes ?: continue
            val targetRefDes = bomRows.find { it.bomnetId == targetBomnetId }?.refDes ?: continue
            
            // Извлекаем bend points из edge sections
            val bendPoints = parseBendPoints(edge)
            
            // Создаём WireConnection
            result.add(WireConnection(
                id = edgeId,
                netName = netName,
                refDesFrom = sourceRefDes,
                pinFrom = "",
                refDesTo = targetRefDes,
                pinTo = "",
                bendPoints = bendPoints
            ))
        }
        
        return result
    }
    
    /**
     * Извлекает bend points из ELK-рёбра.
     */
    private fun parseBendPoints(edge: org.eclipse.elk.graph.ElkEdge): List<WireBendPoint> {
        val bendPoints = mutableListOf<WireBendPoint>()
        
        // Проходим по всем секциям edge
        for (section in edge.sections) {
            // Извлекаем bend points из секции
            for (bp in section.bendPoints) {
                bendPoints.add(WireBendPoint(
                    x = bp.x ?: 0.0,
                    y = bp.y ?: 0.0
                ))
            }
        }
        
        return bendPoints
    }
    
    /**
     * Сохраняет SchematicDocument в файл.
     */
    fun save(document: SchematicDocument, file: File) {
        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            isLenient = true
        }
        val jsonStr = json.encodeToString(SchematicDocument.serializer(), document)
        file.writeText(jsonStr)
    }
}

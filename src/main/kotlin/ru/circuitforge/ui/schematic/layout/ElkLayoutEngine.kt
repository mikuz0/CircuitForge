package ru.circuitforge.ui.schematic.layout

import org.eclipse.elk.alg.layered.LayeredLayoutProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.util.NullElkProgressMonitor
import ru.circuitforge.project.*
import java.io.File
import java.util.concurrent.Executors

/**
 * Движок для запуска ELK layout и преобразования результатов в SchematicDocument.
 * Использует LayeredLayoutProvider напрямую.
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
        
        // Запускаем layout
        runLayout(elkGraph)
        
        // Парсим результаты
        val nodes = parseNodes(elkGraph, bomRows)
        val wires = parseWires(elkGraph, nets, bomRows)
        
        return SchematicDocument(nodes = nodes, wires = wires)
    }
    
    /**
     * Запускает ELK layout engine через LayeredLayoutProvider.
     */
    private fun runLayout(root: org.eclipse.elk.graph.ElkNode) {
        try {
            // Создаём провайдер layout
            val layoutProvider = LayeredLayoutProvider()
            
            // Устанавливаем направление (RIGHT = слева направо)
            root.properties.put(LayeredOptions.DIRECTION, Direction.RIGHT)
            
            // Создаём progress monitor
            val progressMonitor = NullElkProgressMonitor()
            
            // Запускаем layout
            layoutProvider.layout(root, progressMonitor)
            
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
        for (child in root.children) {
            val bomnetId = child.identifier
            if (bomRows.any { it.bomnetId == bomnetId }) {
                result.add(SchematicNode(
                    bomnetId = bomnetId,
                    x = child.x,
                    y = child.y
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

        for (edge in root.containedEdges) {
            val edgeId = edge.identifier ?: continue
            val netName = edgeId.substringBefore("_edge_")

            val sourcePort = edge.sources.firstOrNull()
            val targetPort = edge.targets.lastOrNull()

            if (sourcePort == null || targetPort == null) continue

            val sourceId = sourcePort.identifier ?: continue
            val targetId = targetPort.identifier ?: continue

            // identifier = "${bomnetId}:port:${pinNumber}"
            val sourceBomnetId = sourceId.substringBefore(":port:")
            val targetBomnetId = targetId.substringBefore(":port:")
            val pinFrom = sourceId.substringAfterLast(":port:")
            val pinTo = targetId.substringAfterLast(":port:")

            val sourceRefDes = bomRows.find { it.bomnetId == sourceBomnetId }?.refDes ?: continue
            val targetRefDes = bomRows.find { it.bomnetId == targetBomnetId }?.refDes ?: continue

            val bendPoints = parseBendPoints(edge)

            result.add(WireConnection(
                id = edgeId,
                netName = netName,
                refDesFrom = sourceRefDes,
                pinFrom = pinFrom,
                refDesTo = targetRefDes,
                pinTo = pinTo,
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
        for (section in edge.sections) {
            for (bp in section.bendPoints) {
                bendPoints.add(WireBendPoint(x = bp.x, y = bp.y))
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

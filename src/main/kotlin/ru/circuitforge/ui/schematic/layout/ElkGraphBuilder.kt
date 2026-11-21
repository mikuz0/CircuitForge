package ru.circuitforge.ui.schematic.layout

import org.eclipse.elk.graph.ElkGraphFactory
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.eclipse.elk.alg.layered.options.LayeredOptions
import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.project.Net

/**
 * Строит ELK-граф из BOM + Nets для автоматической раскладки схемы.
 * 
 * Узлы = компоненты BOM
 * Порты = Component.ports
 * Рёбра = цепи (цепочкой: conn[0] → conn[1] → ...)
 */
class ElkGraphBuilder {
    
    private val nodeMap = mutableMapOf<String, org.eclipse.elk.graph.ElkNode>() // bomnetId -> ElkNode
    private val refDesMap = mutableMapOf<String, String>() // refDes -> bomnetId
    private val portMap = mutableMapOf<String, MutableList<org.eclipse.elk.graph.ElkPort>>() // bomnetId -> [ports]
    
    /**
     * Строит ELK-граф из BOM и цепей.
     * Возвращает корневой ElkNode (выступает как ElkGraph).
     */
    fun build(
        bomRows: List<BomRow>,
        nets: List<Net>,
        componentsMap: Map<String, Component>
    ): org.eclipse.elk.graph.ElkNode {
        // Создаём корневой узел (выступает как граф)
        val rootGraph = ElkGraphUtil.createGraph()
        rootGraph.identifier = "schematic_root"
        
        // Устанавливаем свойства layout для layered algorithm
        rootGraph.properties.put(LayeredOptions.DIRECTION, "WEST_TO_EAST")
        
        // Создаём узлы для каждого компонента
        bomRows.forEach { row ->
            val component = componentsMap[row.componentId]
            val node = createNode(rootGraph, row, component)
            nodeMap[row.bomnetId] = node
            refDesMap[row.refDes] = row.bomnetId
            portMap[row.bomnetId] = mutableListOf()
            
            // Создаём порты
            component?.ports?.forEach { port ->
                createPort(node, row, port, component)
            }
        }
        
        // Создаём рёбра для каждой цепи (цепочкой)
        nets.forEach { net ->
            if (net.connections.size >= 2) {
                createChainEdges(net, rootGraph)
            }
        }
        
        return rootGraph
    }
    
    /**
     * Создаёт ELK-узел для компонента.
     */
    private fun createNode(
        parent: org.eclipse.elk.graph.ElkNode,
        row: BomRow,
        component: Component?
    ): org.eclipse.elk.graph.ElkNode {
        val factory = ElkGraphFactory.eINSTANCE
        val node = factory.createElkNode()
        node.identifier = row.bomnetId
        
        // Устанавливаем размеры узла (на основе SVG-символа)
        val (width, height) = getComponentSize(component)
        node.width = width
        node.height = height
        
        // Добавляем в родительский узел
        parent.children.add(node)
        
        return node
    }
    
    /**
     * Создаёт ELK-порт для пина компонента.
     */
    private fun createPort(
        node: org.eclipse.elk.graph.ElkNode,
        row: BomRow,
        port: ru.circuitforge.project.Port,
        component: Component
    ): org.eclipse.elk.graph.ElkPort {
        val factory = ElkGraphFactory.eINSTANCE
        val portNode = factory.createElkPort()
        portNode.identifier = "${row.bomnetId}:port:${port.pinNumber}"
        
        // Определяем сторону порта
        val portIndex = component.ports.indexOfFirst { it.pinNumber == port.pinNumber }
        val mid = (component.ports.size + 1) / 2
        val isLeft = portIndex < mid
        
        // Устанавливаем сторону порта (LEFT/RIGHT)
        val portSide = if (isLeft) "LEFT" else "RIGHT"
        portNode.properties.put(LayeredOptions.PORT_SIDE, portSide)
        
        // Добавляем порт в узел
        node.ports.add(portNode)
        
        // Сохраняем для создания рёбер
        portMap[row.bomnetId]?.add(portNode)
        
        return portNode
    }
    
    /**
     * Создаёт рёбра для цепи цепочкой: conn[0] → conn[1] → ...
     */
    private fun createChainEdges(net: Net, rootGraph: org.eclipse.elk.graph.ElkNode) {
        val factory = ElkGraphFactory.eINSTANCE
        
        for (i in 0 until net.connections.size - 1) {
            val connFrom = net.connections[i]
            val connTo = net.connections[i + 1]
            
            val bomnetIdFrom = refDesMap[connFrom.refDes]
            val bomnetIdTo = refDesMap[connTo.refDes]
            
            val nodeFrom = bomnetIdFrom?.let { nodeMap[it] }
            val nodeTo = bomnetIdTo?.let { nodeMap[it] }
            
            if (nodeFrom != null && nodeTo != null) {
                val edge = factory.createElkEdge()
                edge.identifier = "${net.name}_edge_$i"
                
                // Устанавливаем source и target через списки
                edge.sources.add(nodeFrom)
                edge.targets.add(nodeTo)
                
                // Устанавливаем ортогональную трассировку
                edge.properties.put(LayeredOptions.EDGE_ROUTING, "ORTHOGONAL")
                
                // Добавляем в корневой узел
                rootGraph.containedEdges.add(edge)
            }
        }
    }
    
    /**
     * Получает размеры компонента на основе типа.
     */
    private fun getComponentSize(component: Component?): Pair<Double, Double> {
        return when (component?.name?.lowercase()) {
            "resistor" -> Pair(80.0, 40.0)
            "capacitor" -> Pair(80.0, 50.0)
            else -> Pair(120.0, 80.0)
        }
    }
}

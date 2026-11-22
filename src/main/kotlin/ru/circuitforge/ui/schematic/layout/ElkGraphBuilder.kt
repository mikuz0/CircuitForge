package ru.circuitforge.ui.schematic.layout

import org.eclipse.elk.graph.ElkGraphFactory
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.PortSide
import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component
import ru.circuitforge.project.Net
import ru.circuitforge.ui.schematic.symbols.SymbolLibrary

/**
 * Строит ELK-граф из BOM + Nets для автоматической раскладки схемы.
 * Использует SymbolLibrary для размеров и позиций портов.
 */
class ElkGraphBuilder(
    private val symbolLibrary: SymbolLibrary? = null
) {

    private val nodeMap = mutableMapOf<String, org.eclipse.elk.graph.ElkNode>()
    private val refDesMap = mutableMapOf<String, String>()
    private val portMap = mutableMapOf<String, MutableList<org.eclipse.elk.graph.ElkPort>>()

    fun build(
        bomRows: List<BomRow>,
        nets: List<Net>,
        componentsMap: Map<String, Component>
    ): org.eclipse.elk.graph.ElkNode {
        val rootGraph = ElkGraphUtil.createGraph()
        rootGraph.identifier = "schematic_root"

        rootGraph.properties.put(LayeredOptions.DIRECTION, Direction.RIGHT)

        bomRows.forEach { row ->
            val component = componentsMap[row.componentId]
            val node = createNode(rootGraph, row, component)
            nodeMap[row.bomnetId] = node
            refDesMap[row.refDes] = row.bomnetId
            portMap[row.bomnetId] = mutableListOf()

            component?.ports?.forEach { port ->
                createPort(node, row, port, component)
            }
        }

        nets.forEach { net ->
            if (net.connections.size >= 2) {
                createChainEdges(net, rootGraph)
            }
        }

        return rootGraph
    }

    private fun createNode(
        parent: org.eclipse.elk.graph.ElkNode,
        row: BomRow,
        component: Component?
    ): org.eclipse.elk.graph.ElkNode {
        val factory = ElkGraphFactory.eINSTANCE
        val node = factory.createElkNode()
        node.identifier = row.bomnetId
        node.x = 0.0
        node.y = 0.0

        val (width, height) = getComponentSize(component)
        node.width = width
        node.height = height

        parent.children.add(node)
        return node
    }

    private fun createPort(
        node: org.eclipse.elk.graph.ElkNode,
        row: BomRow,
        port: ru.circuitforge.project.Port,
        component: Component
    ): org.eclipse.elk.graph.ElkPort {
        val factory = ElkGraphFactory.eINSTANCE
        val portNode = factory.createElkPort()
        portNode.identifier = "${row.bomnetId}:port:${port.pinNumber}"

        // Определяем сторону порта из SymbolLibrary
        val symbolType = symbolLibrary?.getSymbolType(component.name)
        val portPosition = symbolType?.let { symbolLibrary.getPortPosition(it, port.pinNumber) }
        
        val portSide = if (portPosition != null) {
            // Если порт сверху/снизу — используем PortSide.NORTH/SOUTH
            when {
                portPosition.second < 0 -> PortSide.NORTH
                portPosition.second > 0 -> PortSide.SOUTH
                portPosition.first < 0 -> PortSide.WEST
                else -> PortSide.EAST
            }
        } else {
            // Fallback — распределяем по бокам
            val portIndex = component.ports.indexOfFirst { it.pinNumber == port.pinNumber }
            val mid = (component.ports.size + 1) / 2
            if (portIndex < mid) PortSide.WEST else PortSide.EAST
        }
        
        portNode.properties.put(LayeredOptions.PORT_SIDE, portSide)

        node.ports.add(portNode)
        portMap[row.bomnetId]?.add(portNode)

        return portNode
    }

    private fun createChainEdges(net: Net, rootGraph: org.eclipse.elk.graph.ElkNode) {
        val factory = ElkGraphFactory.eINSTANCE

        for (i in 0 until net.connections.size - 1) {
            val connFrom = net.connections[i]
            val connTo = net.connections[i + 1]

            val bomnetIdFrom = refDesMap[connFrom.refDes]
            val bomnetIdTo = refDesMap[connTo.refDes]

            if (bomnetIdFrom == null || bomnetIdTo == null) continue

            val portFrom = portMap[bomnetIdFrom]?.find { it.identifier.endsWith(":port:${connFrom.pinNumber}") }
            val portTo = portMap[bomnetIdTo]?.find { it.identifier.endsWith(":port:${connTo.pinNumber}") }

            if (portFrom == null || portTo == null) continue

            val edge = factory.createElkEdge()
            edge.identifier = "${net.name}_edge_$i"

            edge.sources.add(portFrom)
            edge.targets.add(portTo)

            edge.properties.put(LayeredOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)

            rootGraph.containedEdges.add(edge)
        }
    }

    private fun getComponentSize(component: Component?): Pair<Double, Double> {
        // Сначала пробуем через SymbolLibrary
        symbolLibrary?.let { lib ->
            val symbolType = lib.getSymbolType(component?.name ?: "")
            lib.getSymbolSize(symbolType)?.let { return it }
        }
        
        // Fallback — дефолтные размеры
        return when (component?.name?.lowercase()) {
            "resistor" -> Pair(80.0, 40.0)
            "capacitor" -> Pair(80.0, 50.0)
            "crystal" -> Pair(60.0, 60.0)
            else -> Pair(120.0, 80.0)
        }
    }
}

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
        
        return SchematicDocument(nodes = nodes)
    }
    
    /**
     * Запускает ELK layout engine.
     */
    private fun runLayout(root: org.eclipse.elk.graph.ElkNode) {
        // Используем Reflection для вызова LayoutConfigurator
        try {
            val configuratorClass = Class.forName("org.eclipse.elk.core.LayoutConfigurator")
            val instanceMethod = configuratorClass.getMethod("getInstance")
            val configurator = instanceMethod.invoke(null)
            
            // Устанавливаем направление
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
            
            // Вызываем layout
            @Suppress("UNCHECKED_CAST")
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

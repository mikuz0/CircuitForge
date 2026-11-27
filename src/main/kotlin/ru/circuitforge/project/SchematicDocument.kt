package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Узел схемы — привязка BOM-элемента к координатам на холсте.
 */
@Serializable
data class SchematicNode(
    val bomnetId: String,
    var x: Double,
    var y: Double,
    var rotation: Double = 0.0
)

/**
 * Документ схемы — координаты размещения компонентов.
 */
@Serializable
data class SchematicDocument(
    val schemaVersion: Int = 1,
    val nodes: List<SchematicNode> = emptyList()
)

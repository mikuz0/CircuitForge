package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Точка на пути линии связи.
 */
@Serializable
data class WirePoint(
    val x: Double,
    val y: Double
)

/**
 * Путь линии связи — полилиния из точек для конкретной цепи.
 */
@Serializable
data class NetPath(
    val netName: String,
    val waypoints: List<WirePoint> = emptyList()
)

/**
 * Узел схемы — привязка BOM-элемента к координатам на холсте.
 */
@Serializable
data class SchematicNode(
    val bomnetId: String,
    var x: Double,
    var y: Double
)

/**
 * Документ схемы — координаты размещения компонентов и пути линий связи.
 */
@Serializable
data class SchematicDocument(
    val schemaVersion: Int = 1,
    val nodes: List<SchematicNode> = emptyList(),
    val paths: List<NetPath> = emptyList()
)

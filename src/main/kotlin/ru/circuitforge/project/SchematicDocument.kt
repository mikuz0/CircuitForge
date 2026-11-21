package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Точка изгиба провода (bend point) для ортогональной трассировки.
 */
@Serializable
data class WireBendPoint(
    val x: Double,
    val y: Double
)

/**
 * Связь (провод) между двумя портами компонентов.
 */
@Serializable
data class WireConnection(
    val id: String = java.util.UUID.randomUUID().toString(),
    val netName: String = "",
    val refDesFrom: String = "",
    val pinFrom: String = "",
    val refDesTo: String = "",
    val pinTo: String = "",
    val bendPoints: List<WireBendPoint> = emptyList()
)

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
 * Документ схемы — координаты размещения компонентов и соединения.
 */
@Serializable
data class SchematicDocument(
    val schemaVersion: Int = 1,
    val nodes: List<SchematicNode> = emptyList(),
    val wires: List<WireConnection> = emptyList()
)

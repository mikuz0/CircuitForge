package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Документ спецификации списка компонентов (BOM / ПЭ3).
 * Содержит плоский список элементов — каждый элемент = один установленный компонент на плате.
 */
@Serializable
data class BomDocument(
    val schemaVersion: Int = 1,
    val items: List<BomItem> = emptyList()
)

/**
 * Элемент BOM — одна строка перечня элементов.
 * number — порядковый номер (1, 2, 3...), refDes — позиционное обозначение (R1, C2, DD1...).
 */
@Serializable
data class BomItem(
    val number: Int = 0,
    val refDes: String = "",
    val componentSlug: String = "",
    val comment: String = ""
)

/**
 * Строка BOM с подтянутым именем компонента.
 */
data class BomRow(
    val number: Int,
    val refDes: String,
    val componentSlug: String,
    val componentName: String,
    val comment: String
)

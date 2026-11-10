package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Документ спецификации списка компонентов (BOM).
 * Содержит группы компонентов по категориям для заказа/сборки.
 */
@Serializable
data class BomDocument(
    val items: List<BomItem> = emptyList()
) {
    /** Добавляет элемент в BOM документ. */
    fun withItem(item: BomItem): BomDocument {
        return copy(items = items + item)
    }

    /** Удаляет элемент из BOM документа по индексу. */
    fun withoutItem(index: Int): BomDocument {
        return copy(items = items.toMutableList().apply { removeAt(index) })
    }
}

/**
 * Элемент BOM — группа компонентов одного типа.
 */
@Serializable
data class BomItem(
    val refPrefix: String = "",
    val name: String = "",
    val value: String = "",
    val packageType: String = "",
    val quantity: Int = 0,
    val remarks: String = "",
    val rows: List<BomRow> = emptyList()
)

/**
 * Строка BOM — отдельная запись о компоненте.
 */
@Serializable
data class BomRow(
    val reference: String = "",
    val value: String = "",
    val packageType: String = "",
    val manufacturer: String = "",
    val quantity: Int = 1,
    val remarks: String = ""
)

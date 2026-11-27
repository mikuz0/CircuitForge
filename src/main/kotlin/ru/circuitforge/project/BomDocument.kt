package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Документ спецификации списка компонентов (BOM / ПЭ3).
 */
@Serializable
data class BomDocument(
    val schemaVersion: Int = 1,
    val items: List<BomItem> = emptyList()
)

/**
 * Элемент BOM — одна строка перечня элементов.
 * bomnetId — уникальный UUID экземпляра (связывает BOM и Nets).
 * componentId — UUID компонента-описания (ссылка на .cfcomp файл).
 */
@Serializable
data class BomItem(
    val bomnetId: String = "",
    val number: Int = 0,
    val refDes: String = "",
    val componentId: String = "",
    val componentSlug: String = "",
    val description: String = "",
    val comment: String = ""
)

/**
 * Строка BOM с подтянутыми данными компонента для отображения в UI.
 * refPrefix берется из компонента и используется для генерации RefDes.
 */
data class BomRow(
    val bomnetId: String,
    val number: Int,
    val refDes: String,
    val componentId: String,
    val componentSlug: String = "",
    val componentName: String,
    val description: String = "",
    val comment: String,
    val refPrefix: String = ""
)

package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Компонент схемы с портами и префиксом ссылки.
 * slug — имя файла без .cfcomp, генерируется из name.
 */
@Serializable
data class Component(
    val schemaVersion: Int = 1,
    val id: String = java.util.UUID.randomUUID().toString(),
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val model3dPath: String? = null,
    val refPrefix: String = "",
    val ports: List<Port> = emptyList()
)

/**
 * Порт (вывод) компонента для соединений.
 * pinNumber может быть числовым ("1") или буквенным ("A1", "EP", "GND").
 */
@Serializable
data class Port(
    val number: Int = 0,
    val pinNumber: String = "",
    val name: String = "",
    val description: String = ""
)

/**
 * Сокращённая информация о компоненте для отображения в списках.
 */
@Serializable
data class ComponentSummary(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    val refPrefix: String,
    val portsCount: Int = 0
)

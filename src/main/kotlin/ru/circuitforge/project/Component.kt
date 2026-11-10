package ru.circuitforge.project

import kotlinx.serialization.Serializable

/**
 * Компонент схемы с портами и префиксом ссылки.
 * refPrefix используется для обозначения типа компонента (R, C, L, U и т.д.).
 */
@Serializable
data class Component(
    val id: String = java.util.UUID.randomUUID().toString(),
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val portsCount: Int = 0,
    val refPrefix: String = "",
    val value: String = "",
    val packageType: String = "",
    val manufacturer: String = "",
    val ports: List<Port> = emptyList()
)

/**
 * Порт компонента для соединений.
 */
@Serializable
data class Port(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val pinNumber: Int = 0
)

/**
 * Сокращённая информация о компоненте для отображения в списках.
 */
@Serializable
data class ComponentSummary(
    val id: String,
    val slug: String,
    val name: String,
    val portsCount: Int
)

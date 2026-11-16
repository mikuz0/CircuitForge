package ru.circuitforge.project

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.ALWAYS
import kotlinx.serialization.Serializable

/**
 * Подключение: привязка refDes к номеру пина.
 */
@Serializable
data class NetConnection(
    val refDes: String = "",
    val pinNumber: String = ""
)

/**
 * Цепь (Net) — набор подключений с уникальным именем.
 */
@Serializable
data class Net(
    val name: String = "",
    @EncodeDefault(ALWAYS)
    val connections: List<NetConnection> = emptyList()
)

/**
 * Документ netlist — список цепей проекта.
 */
@Serializable
data class NetDocument(
    val schemaVersion: Int = 1,
    val nets: List<Net> = emptyList()
)

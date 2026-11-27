package ru.circuitforge.ui.schematic.symbols

import kotlinx.serialization.Serializable

/**
 * Конфиг библиотеки символов.
 * Содержит определения SVG-символов и маппинг Component.name → symbolType.
 */
@Serializable
data class SymbolConfig(
    val symbols: Map<String, SymbolDefinition> = emptyMap(),
    val mappings: Map<String, String> = emptyMap(),
)

/**
 * Определение одного символа (компонента).
 */
@Serializable
data class SymbolDefinition(
    val file: String, // имя SVG файла (относительно symbolsDir)
    val width: Double = 80.0, // ширина символа
    val height: Double = 40.0, // высота символа
    val ports: Map<String, PortDefinition> = emptyMap(), // pinNumber → позиция
)

/**
 * Позиция порта на символе (относительно центра 0,0).
 */
@Serializable
data class PortDefinition(
    val x: Double = 0.0,
    val y: Double = 0.0,
)

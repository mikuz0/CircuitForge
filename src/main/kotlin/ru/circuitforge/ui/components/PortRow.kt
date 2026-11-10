package ru.circuitforge.ui.components

import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import java.nio.file.Path

/**
 * Обёртка для свойства порта компонента.
 * Позволяет использовать JavaFX Property API для привязки к UI.
 */
class PortRow {
    val nameProperty: StringProperty = SimpleStringProperty("")
    var name: String
        get() = nameProperty.value
        set(value) { nameProperty.value = value }

    val pinProperty = SimpleStringProperty("")
    var pin: String
        get() = pinProperty.value
        set(value) { pinProperty.value = value }
}

package ru.circuitforge.ui.schematic

import javafx.scene.Group
import javafx.scene.control.Label
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import ru.circuitforge.project.BomRow
import ru.circuitforge.project.Component

/**
 * Рендерер компонентов для схемы.
 * Отвечает за отрисовку трёх типов: резистор, конденсатор, стандартный.
 */
object ComponentRenderer {

    /**
     * Резистор: размеры кратны 12.5px для идеального попадания в сетку.
     * Корпус 50x25, ножки по 25px с каждой стороны.
     */
    fun drawCustomResistor(group: Group, row: BomRow, comp: Component) {
        val rectW = 50.0
        val rectH = 25.0
        val leadLen = 25.0

        val rect = Rectangle(rectW, rectH).apply {
            fill = Color.WHITE
            stroke = Color.BLACK
            strokeWidth = 1.5
            x = -rectW / 2
            y = -rectH / 2
        }
        group.children.add(rect)

        val leftLead = Line(-rectW / 2, 0.0, -rectW / 2 - leadLen, 0.0).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(leftLead)

        val rightLead = Line(rectW / 2, 0.0, rectW / 2 + leadLen, 0.0).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(rightLead)

        val refDesLabel = Label(row.refDes).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: black;"
            layoutX = -15.0
            layoutY = -rectH / 2 - leadLen - 15.0
        }
        group.children.add(refDesLabel)

        val commentLabel = Label(row.comment).apply {
            style = "-fx-font-size: 11px; -fx-text-fill: black;"
            layoutX = -15.0
            layoutY = rectH / 2 + leadLen + 5.0
        }
        group.children.add(commentLabel)

        val pin1Label = Label(getPinNumberForIndex(comp, 0)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = -rectW / 2 - leadLen - 15.0
            layoutY = -10.0
        }
        group.children.add(pin1Label)

        val pin2Label = Label(getPinNumberForIndex(comp, 1)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = rectW / 2 + leadLen + 5.0
            layoutY = -10.0
        }
        group.children.add(pin2Label)
    }

    /**
     * Конденсатор: размеры кратны 12.5px.
     * Пластины 25x2, зазор 12.5, ножки по 25px.
     */
    fun drawCustomCapacitor(group: Group, row: BomRow, comp: Component) {
        val plateW = 25.0
        val plateGap = 12.5
        val leadLen = 25.0

        // 1. Прозрачный прямоугольник для перетаскивания — ТОЛЬКО В ОБЛАСТИ ПЛАСТИН
        val clickAreaWidth = plateW + 10.0 // Чуть шире пластин
        val clickAreaHeight = plateGap + 10.0 // Чуть выше зазора
        val clickArea = Rectangle(clickAreaWidth, clickAreaHeight).apply {
            fill = Color.TRANSPARENT
            stroke = null
            x = -clickAreaWidth / 2
            y = -clickAreaHeight / 2
        }
        group.children.add(clickArea)

        // 2. Две вертикальные пластины (в центре)
        val leftPlate = Line(-plateGap / 2, -plateW / 2, -plateGap / 2, plateW / 2).apply {
            stroke = Color.BLACK
            strokeWidth = 2.0
        }
        group.children.add(leftPlate)

        val rightPlate = Line(plateGap / 2, -plateW / 2, plateGap / 2, plateW / 2).apply {
            stroke = Color.BLACK
            strokeWidth = 2.0
        }
        group.children.add(rightPlate)

        // 3. Ножки (слева и справа)
        val leftLead = Line(-plateGap / 2 - leadLen, 0.0, -plateGap / 2, 0.0).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(leftLead)

        val rightLead = Line(plateGap / 2, 0.0, plateGap / 2 + leadLen, 0.0).apply {
            stroke = Color.BLACK
            strokeWidth = 1.5
        }
        group.children.add(rightLead)

        // 4. Подписи RefDes и Comment (справа)
        val refDesLabel = Label(row.refDes).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: black;"
            layoutX = plateGap / 2 + leadLen + 5.0
            layoutY = -15.0
        }
        group.children.add(refDesLabel)

        val commentLabel = Label(row.comment).apply {
            style = "-fx-font-size: 11px; -fx-text-fill: black;"
            layoutX = plateGap / 2 + leadLen + 5.0
            layoutY = 0.0
        }
        group.children.add(commentLabel)

        // 5. Подписи pinNumber
        val pin1Label = Label(getPinNumberForIndex(comp, 0)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = -plateGap / 2 - leadLen - 15.0
            layoutY = -10.0
        }
        group.children.add(pin1Label)

        val pin2Label = Label(getPinNumberForIndex(comp, 1)).apply {
            style = "-fx-font-size: 9px; -fx-text-fill: black;"
            layoutX = plateGap / 2 + leadLen + 5.0
            layoutY = -10.0
        }
        group.children.add(pin2Label)
    }

    /**
     * Стандартный компонент (IC): все размеры кратны 12.5px.
     */
    fun drawDefaultComponent(group: Group, row: BomRow, comp: Component) {
        val allPorts = comp.ports
        val mid = (allPorts.size + 1) / 2
        val leftPorts = allPorts.take(mid)
        val rightPorts = allPorts.drop(mid)

        val portSpacing = 25.0
        val paddingTop = 50.0
        val paddingBottom = 50.0
        val minHeight = 100.0
        val leadLen = 25.0

        val maxPorts = maxOf(leftPorts.size, rightPorts.size)
        val calculatedHeight = maxOf(minHeight, (paddingTop + maxPorts * portSpacing + paddingBottom))

        val width = 150.0
        val height = calculatedHeight

        val rect = Rectangle(width, height).apply {
            fill = Color.WHITE
            stroke = Color.BLACK
            strokeWidth = 1.5
            x = -width / 2
            y = -height / 2
        }
        group.children.add(rect)

        val refDesLabel = Label(row.refDes).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: black;"
            layoutX = -width / 2 + 10.0
            layoutY = -height / 2 + 10.0
        }
        group.children.add(refDesLabel)

        val nameLabel = Label(comp.name).apply {
            style = "-fx-font-size: 10px; -fx-text-fill: gray;"
            layoutX = -width / 2 + 10.0
            layoutY = -height / 2 + 28.0
        }
        group.children.add(nameLabel)

        // Левые порты
        leftPorts.forEachIndexed { index, port ->
            val pinY = -height / 2 + paddingTop + index * portSpacing
            val line = Line(-width / 2, pinY, -width / 2 - leadLen, pinY).apply {
                stroke = Color.BLACK
            }
            group.children.add(line)

            val pinNumLabel = Label(port.pinNumber).apply {
                style = "-fx-font-size: 9px; -fx-text-fill: black;"
                layoutX = -width / 2 - leadLen - 15.0
                layoutY = pinY - 10.0
            }
            group.children.add(pinNumLabel)

            val portNameLabel = Label(port.name).apply {
                style = "-fx-font-size: 10px; -fx-text-fill: black;"
                layoutX = -width / 2 + 5.0
                layoutY = pinY - 8.0
            }
            group.children.add(portNameLabel)
        }

        // Правые порты
        rightPorts.forEachIndexed { index, port ->
            val pinY = -height / 2 + paddingTop + index * portSpacing
            val line = Line(width / 2, pinY, width / 2 + leadLen, pinY).apply {
                stroke = Color.BLACK
            }
            group.children.add(line)

            val pinNumLabel = Label(port.pinNumber).apply {
                style = "-fx-font-size: 9px; -fx-text-fill: black;"
                layoutX = width / 2 + leadLen + 5.0
                layoutY = pinY - 10.0
            }
            group.children.add(pinNumLabel)

            val portNameLabel = Label(port.name).apply {
                style = "-fx-font-size: 10px; -fx-text-fill: black;"
                layoutX = width / 2 - 45.0
                layoutY = pinY - 8.0
            }
            group.children.add(portNameLabel)
        }
    }

    /**
     * Возвращает номер пина по индексу из ports компонента.
     */
    fun getPinNumberForIndex(comp: Component, index: Int): String {
        return if (index < comp.ports.size) comp.ports[index].pinNumber else "${index + 1}"
    }
}

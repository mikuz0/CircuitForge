package ru.circuitforge.ui.common

import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TextField
import javafx.util.Callback

/**
 * Создаёт редактируемую ячейку с TextField для TableColumn<T, String>.
 * Автокоммит при Enter или потере фокуса.
 */
fun <T> TableColumn<T, String>.setupEditableCell() {
    cellFactory =
        Callback { _: TableColumn<T, String> ->
            object : TableCell<T, String>() {
                private val tf = TextField()

                init {
                    tf.setOnAction { commitEdit(tf.text) }
                    tf.focusedProperty().addListener { _, _, focused ->
                        if (!focused) commitEdit(tf.text)
                    }
                }

                override fun startEdit() {
                    super.startEdit()
                    tf.text = item ?: ""
                    graphic = tf
                    text = null
                    tf.requestFocus()
                    tf.selectAll()
                }

                override fun cancelEdit() {
                    super.cancelEdit()
                    text = item
                    graphic = null
                }

                override fun updateItem(
                    item: String?,
                    empty: Boolean,
                ) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                    } else if (isEditing) {
                        tf.text = item
                        graphic = tf
                        text = null
                    } else {
                        text = item
                        graphic = null
                    }
                }
            }
        }
}

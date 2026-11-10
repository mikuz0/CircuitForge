package ru.circuitforge.ui.common

import javafx.application.Application
import javafx.stage.Stage

/**
 * Базовый класс для standalone запуска модулей.
 * Использует Application.launch для инициализации JavaFX.
 */
abstract class ModuleLauncher : Application() {
    abstract fun createWindow(): Stage

    override fun start(stage: Stage) {
        val window = createWindow()
        window.show()
    }

    companion object {
        /** Запускает приложение модуля с аргументами командной строки. */
        @JvmStatic
        fun run(appClass: Class<out ModuleLauncher>, projectPath: String? = null) {
            val args = if (projectPath != null) arrayOf(projectPath) else emptyArray<String>()
            Application.launch(appClass.java, *args)
        }
    }
}

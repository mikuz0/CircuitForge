package ru.circuitforge.ui.components

import ru.circuitforge.ui.common.ModuleLauncher

/**
 * Standalone launch для редактора компонентов.
 */
object ComponentsApp {
    @JvmStatic
    fun main(args: Array<String>) {
        ModuleLauncher.run<ComponentsWindow>({ ComponentsWindow() }, args)
    }
}

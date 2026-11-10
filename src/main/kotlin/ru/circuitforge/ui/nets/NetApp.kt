package ru.circuitforge.ui.nets

import ru.circuitforge.ui.common.ModuleLauncher

/**
 * Standalone launch для редактора сетей.
 */
object NetApp {
    @JvmStatic
    fun main(args: Array<String>) {
        ModuleLauncher.run<NetWindow>({ NetWindow() }, args)
    }
}

package ru.circuitforge.ui.schematic

import ru.circuitforge.ui.common.ModuleLauncher

/**
 * Standalone launch для модуля схемы.
 * Поддерживает аргумент командной строки с путём к проекту.
 */
object SchematicApp {
    @JvmStatic
    fun main(args: Array<String>) {
        ModuleLauncher.run<SchematicWindow>({ SchematicWindow() }, args)
    }
}

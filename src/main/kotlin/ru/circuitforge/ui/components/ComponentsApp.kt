package ru.circuitforge.ui.components

import ru.circuitforge.ui.common.ModuleLauncher

/**
 * Standalone launch для редактора компонентов.
 */
class ComponentsApp : ModuleLauncher() {
    override fun createWindow() = ComponentsWindow().apply { showWindow() }.getStage()
}

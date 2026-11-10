package ru.circuitforge.ui.nets

import ru.circuitforge.ui.common.ModuleLauncher

/**
 * Standalone launch для редактора сетей.
 */
class NetApp : ModuleLauncher() {
    override fun createWindow() = NetWindow().apply { showWindow() }.getStage()
}

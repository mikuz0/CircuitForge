package ru.circuitforge.ui.editor

import org.slf4j.LoggerFactory
import ru.circuitforge.ui.common.ModuleLauncher
import ru.circuitforge.ui.common.ProjectAware
import ru.circuitforge.ui.common.UiUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Standalone launch для редактора BOM.
 * Поддерживает аргумент командной строки с путём к проекту (.cfproj).
 */
class BomApp {
    companion object {
        private val logger = LoggerFactory.getLogger(BomApp::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            ModuleLauncher.run<BomWindow>({ BomWindow() }, args)
        }
    }
}

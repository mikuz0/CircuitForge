package ru.circuitforge.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Утилиты для работы с путями конфигурации.
 * Использует XDG_CONFIG_HOME для хранения настроек.
 */
object AppPaths {
    /** Базовая директория приложения ~/.config/circuitforge */
    const val CONFIG_DIR: String = "circuitforge"

    val configDir: Path by lazy {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".config")
        xdg.resolve(CONFIG_DIR)
    }

    /** Файл настроек settings.json */
    val settingsFile: Path by lazy { configDir.resolve("settings.json") }

    /** Инициализирует конфигурационную директорию. */
    fun initConfigDir() {
        Files.createDirectories(configDir)
    }
}

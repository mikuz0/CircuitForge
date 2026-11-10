package ru.circuitforge.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Настройки приложения с сериализацией в JSON.
 * Сохраняется в ~/.config/circuitforge/settings.json.
 */
@Serializable
data class Settings(
    val workspace: String? = null
) {
    companion object {
        private val logger = LoggerFactory.getLogger(Settings::class.java)
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * Репозиторий настроек.
 * Отвечает за загрузку и сохранение settings.json.
 */
object SettingsStore {
    private val logger = LoggerFactory.getLogger(SettingsStore::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Загружает настройки из файла. Возвращает дефолтные при ошибке. */
    fun load(): Settings {
        val file = AppPaths.settingsFile
        if (!Files.exists(file)) return Settings()
        return try {
            val content = Files.readString(file)
            json.decodeFromString<Settings>(content)
        } catch (e: Exception) {
            logger.error("Failed to load settings from $file", e)
            Settings()
        }
    }

    /** Сериализует настройки в JSON и сохраняет в файл. */
    fun save(settings: Settings) {
        AppPaths.initConfigDir()
        val file = AppPaths.settingsFile
        try {
            val content = json.encodeToString(settings)
            Files.writeString(file, content)
            logger.info("Settings saved to $file")
        } catch (e: Exception) {
            logger.error("Failed to save settings to $file", e)
        }
    }
}

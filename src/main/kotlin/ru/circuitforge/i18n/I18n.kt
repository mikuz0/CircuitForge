package ru.circuitforge.i18n

import java.util.*

/**
 * Система локализации приложения.
 * Поддерживает EN и RU языки с сохранением в настройках.
 */
object I18n {
    enum class LocaleType(val code: String, val displayName: String) {
        EN("EN", "English"),
        RU("RU", "Русский"),
    }

    @Volatile private var currentLocale: LocaleType = LocaleType.EN
    private var bundle: ResourceBundle? = null

    fun load(locale: LocaleType) {
        currentLocale = locale
        val bundleLocale = if (locale == LocaleType.RU) Locale.forLanguageTag("ru") else Locale.ROOT
        bundle = ResourceBundle.getBundle("messages", bundleLocale)
    }

    fun getCurrentLocale(): LocaleType = currentLocale

    /**
     * Перевод строки по ключу с поддержкой аргументов.
     * @param key ключ в .properties файле
     * @param args аргументы для форматирования ({0}, {1}, ...)
     */
    fun tr(
        key: String,
        vararg args: Any,
    ): String {
        val pattern = bundle?.getString(key) ?: key
        return if (args.isEmpty()) {
            pattern
        } else {
            pattern.format(*args)
        }
    }

    /** Получить displayName текущего языка. */
    fun getCurrentDisplayName(): String = currentLocale.displayName

    /** Принудительно перезагружает Bundle текущего языка. */
    fun reload() {
        bundle = null
        load(currentLocale)
    }

    /** Инициализация по умолчанию (EN). */
    init {
        load(LocaleType.EN)
    }
}

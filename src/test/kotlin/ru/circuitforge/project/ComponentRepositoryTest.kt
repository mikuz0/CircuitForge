package ru.circuitforge.project

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files

class ComponentRepositoryTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun setupProjectDir(): java.nio.file.Path {
        val projectDir = tempDir.resolve("TestProject")
        Files.createDirectories(projectDir.resolve("components"))
        return projectDir
    }

    @Test
    fun `testCreateComponent`() {
        val projectDir = setupProjectDir()
        val slug = ComponentRepository.createComponent(
            projectDir = projectDir,
            name = "Resistor 0603",
            description = "Generic 0603 resistor",
            refPrefix = "R",
            ports = listOf(
                Port(1, "1", "Pin1", "Terminal 1"),
                Port(2, "2", "Pin2", "Terminal 2")
            )
        )

        assertEquals("resistor-0603", slug)
        val compPath = projectDir.resolve("components").resolve("resistor-0603.cfcomp")
        assertTrue(Files.exists(compPath), "Файл компонента должен быть создан")
    }

    @Test
    fun `testLoadComponent`() {
        val projectDir = setupProjectDir()
        val slug = ComponentRepository.createComponent(
            projectDir = projectDir,
            name = "ATmega328P",
            description = "8-bit AVR MCU",
            refPrefix = "U",
            ports = listOf(
                Port(1, "1", "PC6/RESET", "Reset"),
                Port(2, "2", "PD0/RXD", "UART RX"),
                Port(7, "7", "VCC", "Power supply"),
                Port(8, "8", "GND", "Ground")
            )
        )

        val loaded = ComponentRepository.loadComponent(projectDir, slug)

        assertEquals("ATmega328P", loaded.name)
        assertEquals("U", loaded.refPrefix)
        assertEquals("8-bit AVR MCU", loaded.description)
        assertEquals(4, loaded.ports.size)
        assertEquals("PC6/RESET", loaded.ports[0].name)
        assertEquals("7", loaded.ports[2].pinNumber)
    }

    @Test
    fun `testUniqueNameValidation`() {
        val projectDir = setupProjectDir()

        // Создаём первый компонент с именем "test-comp"
        val slug1 = ComponentRepository.createComponent(
            projectDir = projectDir,
            name = "test-comp",
            description = "First component",
            refPrefix = "U"
        )
        assertEquals("test-comp", slug1)

        // Создаём второй компонент с другим именем
        ComponentRepository.createComponent(
            projectDir = projectDir,
            name = "other-comp",
            description = "Second component",
            refPrefix = "R"
        )

        // Пытаемся создать компонент с тем же name — должен получить уникальный slug
        val slug2 = ComponentRepository.createComponent(
            projectDir = projectDir,
            name = "test-comp",
            description = "Third component with same name",
            refPrefix = "C"
        )
        // Проверяем, что имя файла стало уникальным (например, "test-comp-1")
        assertNotEquals(slug1, slug2)
        assertTrue(slug2.startsWith("test-comp-"), "Ожидалось уникальное имя с суффиксом, получено: $slug2")

        // Проверяем, что saveComponent для существующего компонента с тем же именем
        // НЕ выбрасывает исключение, а сохраняет под уникальным именем (или перезаписывает)
        val comp = ComponentRepository.loadComponent(projectDir, slug2)

        // Мы ожидаем, что сохранение пройдёт без ошибок (новая логика)
        val newSlug = ComponentRepository.saveComponent(
            projectDir = projectDir,
            originalSlug = slug2,
            name = "test-comp", // имя совпадает с первым компонентом
            description = comp.description,
            refPrefix = comp.refPrefix,
            ports = comp.ports
        )

        // Программа должна либо вернуть уникальное имя (если создаст новый файл),
        // либо сохранить под старым, но без исключения.
        assertNotNull(newSlug)
        // Проверим, что файл с этим именем существует
        val newPath = projectDir.resolve("components").resolve("${newSlug}.cfcomp")
        assertTrue(Files.exists(newPath), "Файл должен существовать после сохранения")
    }

    @Test
    fun `testRenameComponent`() {
        val projectDir = setupProjectDir()

        // Создаём компонент
        val originalSlug = ComponentRepository.createComponent(
            projectDir = projectDir,
            name = "ATmega328P",
            description = "Original description",
            refPrefix = "U",
            ports = listOf(Port(1, "1", "VCC", "Power"))
        )

        val oldPath = projectDir.resolve("components").resolve("${originalSlug}.cfcomp")
        assertTrue(Files.exists(oldPath))

        // Переименовываем
        val newSlug = ComponentRepository.saveComponent(
            projectDir = projectDir,
            originalSlug = originalSlug,
            name = "ATmega328P-variant",
            description = "Updated description",
            refPrefix = "U",
            ports = listOf(
                Port(1, "1", "VCC", "Power"),
                Port(2, "2", "GND", "Ground")
            )
        )

        assertEquals("atmega328p-variant", newSlug)

        // Старый файл удалён
        val newPath = projectDir.resolve("components").resolve("${newSlug}.cfcomp")
        assertFalse(Files.exists(oldPath), "Старый файл должен быть удалён")
        assertTrue(Files.exists(newPath), "Новый файл должен быть создан")

        // Содержимое корректно
        val loaded = ComponentRepository.load(newPath)
        assertEquals("ATmega328P-variant", loaded.name)
        assertEquals("Updated description", loaded.description)
        assertEquals(2, loaded.ports.size)
    }

    @Test
    fun `testListComponents`() {
        val projectDir = setupProjectDir()

        // Создаём 3 компонента
        ComponentRepository.createComponent(projectDir, "Capacitor 10uF", "Electrolytic", "C")
        ComponentRepository.createComponent(projectDir, "ATmega328P", "MCU", "U")
        ComponentRepository.createComponent(projectDir, "Resistor 0603", "Generic", "R")

        val components = ComponentRepository.listComponents(projectDir)

        assertEquals(3, components.size)

        // Проверка: все 3 компонента на месте (порядок может быть любым)
        val names = components.map { it.name }.toSet()
        assertEquals(setOf("ATmega328P", "Capacitor 10uF", "Resistor 0603"), names)

        // Проверка portsCount
        val atmega = components.find { it.name == "ATmega328P" }
        assertNotNull(atmega)
        assertEquals(0, atmega!!.portsCount)
    }
}

package ru.circuitforge.project

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files

class BomRepositoryTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun setupProjectDir(): java.nio.file.Path {
        val projectDir = tempDir.resolve("TestProject")
        Files.createDirectories(projectDir.resolve("components"))
        return projectDir
    }

    @Test
    fun `testLoadEmpty`() {
        val projectDir = setupProjectDir()

        val bom = BomRepository.load(projectDir)

        // schemaVersion по умолчанию = 1, items должен быть пустым
        assertEquals(1, bom.schemaVersion)
        assertTrue(bom.items.isEmpty())
    }

    @Test
    fun `testSaveAndLoad`() {
        val projectDir = setupProjectDir()

        val bomDoc = BomDocument(
            schemaVersion = 1,
            items = listOf(
                BomItem(number = 1, refDes = "R1", componentSlug = "resistor-0603", comment = "10k"),
                BomItem(number = 2, refDes = "C1", componentSlug = "capacitor-10uf", comment = "10uF electrolytic")
            )
        )

        BomRepository.save(projectDir, bomDoc)

        val loaded = BomRepository.load(projectDir)

        assertEquals(2, loaded.items.size)
        assertEquals(1, loaded.items[0].number)
        assertEquals("R1", loaded.items[0].refDes)
        assertEquals("resistor-0603", loaded.items[0].componentSlug)
        assertEquals("10k", loaded.items[0].comment)
        assertEquals(2, loaded.items[1].number)
        assertEquals("C1", loaded.items[1].refDes)
        assertEquals("10uF electrolytic", loaded.items[1].comment)
    }

    @Test
    fun `testInitFromComponents`() {
        val projectDir = setupProjectDir()

        // Создаём 2 компонента
        val comp1Path = projectDir.resolve("components").resolve("resistor-0603.cfcomp")
        val comp1 = Component(
            schemaVersion = 1,
            slug = "resistor-0603",
            name = "Resistor 0603",
            description = "Generic 0603 resistor",
            refPrefix = "R",
            ports = listOf(Port(1, "1", "Pin1", ""), Port(2, "2", "Pin2", ""))
        )
        ComponentRepository.save(comp1Path, comp1)

        val comp2Path = projectDir.resolve("components").resolve("capacitor-10uf.cfcomp")
        val comp2 = Component(
            schemaVersion = 1,
            slug = "capacitor-10uf",
            name = "Capacitor 10uF",
            description = "Electrolytic capacitor",
            refPrefix = "C",
            ports = listOf(Port(1, "1", "Pin1", ""), Port(2, "2", "Pin2", ""))
        )
        ComponentRepository.save(comp2Path, comp2)

        val bomDoc = BomRepository.initFromComponents(projectDir)

        assertEquals(2, bomDoc.items.size)
        // Проверяем, что есть элементы с правильными refPrefix и componentSlug
        val rItem = bomDoc.items.find { it.componentSlug == "resistor-0603" }
        val cItem = bomDoc.items.find { it.componentSlug == "capacitor-10uf" }
        assertNotNull(rItem, "Должен быть элемент с slug resistor-0603")
        assertNotNull(cItem, "Должен быть элемент с slug capacitor-10uf")
        assertTrue(rItem!!.refDes.startsWith("R"), "RefDes резистора должен начинаться с R")
        assertTrue(cItem!!.refDes.startsWith("C"), "RefDes конденсатора должен начинаться с C")
    }

    @Test
    fun `testSerializationFormat`() {
        val projectDir = setupProjectDir()

        val bomDoc = BomDocument(
            schemaVersion = 1,
            items = listOf(
                BomItem(number = 1, refDes = "R1", componentSlug = "resistor-0603", comment = "10k")
            )
        )

        BomRepository.save(projectDir, bomDoc)

        // Читаем JSON напрямую и проверяем формат
        val bomPath = projectDir.resolve("bom.json")
        val jsonContent = Files.readString(bomPath)
        val json = Json { ignoreUnknownKeys = true }

        // Проверяем, что schemaVersion = 1
        val parsed = json.decodeFromString(BomDocument.serializer(), jsonContent)
        assertEquals(1, parsed.schemaVersion)
        assertEquals(1, parsed.items.size)

        // Проверяем, что все ключи на месте
        val item = parsed.items[0]
        assertEquals(1, item.number)
        assertEquals("R1", item.refDes)
        assertEquals("resistor-0603", item.componentSlug)
        assertEquals("10k", item.comment)

        // Проверяем, что JSON prettyPrint (содержит переносы строк и отступы)
        assertTrue(jsonContent.contains("\n"), "JSON должен быть отформатирован с переносами")
        // Ключи, которые точно есть (не default values)
        assertTrue(jsonContent.contains("\"items\""), "Ключ items должен быть в JSON")
        assertTrue(jsonContent.contains("\"number\""), "Ключ number должен быть в JSON")
        assertTrue(jsonContent.contains("\"refDes\""), "Ключ refDes должен быть в JSON")
        assertTrue(jsonContent.contains("\"componentSlug\""), "Ключ componentSlug должен быть в JSON")
        assertTrue(jsonContent.contains("\"comment\""), "Ключ comment должен быть в JSON")
    }
}

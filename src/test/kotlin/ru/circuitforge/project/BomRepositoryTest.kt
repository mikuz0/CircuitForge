package ru.circuitforge.project

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files

class BomRepositoryTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun setupProjectPaths(): ProjectPaths {
        val projectDir = tempDir.resolve("testproject")
        Files.createDirectories(projectDir.resolve("components"))
        return ProjectPaths(projectDir, "testproject")
    }

    @Test
    fun `testLoadEmpty`() {
        val paths = setupProjectPaths()

        val bom = BomRepository.load(paths)

        assertEquals(1, bom.schemaVersion)
        assertTrue(bom.items.isEmpty())
    }

    @Test
    fun `testSaveAndLoad`() {
        val paths = setupProjectPaths()

        val bomDoc =
            BomDocument(
                schemaVersion = 1,
                items =
                    listOf(
                        BomItem(number = 1, refDes = "R1", componentId = "uuid-1", componentSlug = "resistor-0603", comment = "10k"),
                        BomItem(number = 2, refDes = "C1", componentId = "uuid-2", componentSlug = "capacitor-10uf", comment = "10uF electrolytic"),
                    ),
            )

        BomRepository.save(paths, bomDoc)

        val loaded = BomRepository.load(paths)

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
        val paths = setupProjectPaths()

        // Создаём 2 компонента
        val comp1Path = paths.projectDir.resolve("components").resolve("resistor-0603.cfcomp")
        val comp1 =
            Component(
                schemaVersion = 1,
                name = "Resistor 0603",
                description = "Generic 0603 resistor",
                refPrefix = "R",
                ports = listOf(Port(1, "1", "Pin1", ""), Port(2, "2", "Pin2", "")),
            )
        ComponentRepository.save(comp1Path, comp1)

        val comp2Path = paths.projectDir.resolve("components").resolve("capacitor-10uf.cfcomp")
        val comp2 =
            Component(
                schemaVersion = 1,
                name = "Capacitor 10uF",
                description = "Electrolytic capacitor",
                refPrefix = "C",
                ports = listOf(Port(1, "1", "Pin1", ""), Port(2, "2", "Pin2", "")),
            )
        ComponentRepository.save(comp2Path, comp2)

        val bomDoc = BomRepository.initFromComponents(paths)

        assertEquals(2, bomDoc.items.size)
        // Проверяем, что есть элементы с правильными componentSlug (именами файлов)
        val rItem = bomDoc.items.find { it.componentSlug == "resistor-0603" }
        val cItem = bomDoc.items.find { it.componentSlug == "capacitor-10uf" }
        assertNotNull(rItem, "Должен быть элемент с slug resistor-0603")
        assertNotNull(cItem, "Должен быть элемент с slug capacitor-10uf")
        assertTrue(rItem!!.refDes.startsWith("R"), "RefDes резистора должен начинаться с R")
        assertTrue(cItem!!.refDes.startsWith("C"), "RefDes конденсатора должен начинаться с C")
    }

    @Test
    fun `testSerializationFormat`() {
        val paths = setupProjectPaths()

        val bomDoc =
            BomDocument(
                schemaVersion = 1,
                items =
                    listOf(
                        BomItem(number = 1, refDes = "R1", componentId = "uuid-1", componentSlug = "resistor-0603", comment = "10k"),
                    ),
            )

        BomRepository.save(paths, bomDoc)

        // Файл должен быть named <machineName>.bom.json
        val bomPath = paths.bom
        val jsonContent = Files.readString(bomPath)
        val json = Json { ignoreUnknownKeys = true }

        val parsed = json.decodeFromString(BomDocument.serializer(), jsonContent)
        assertEquals(1, parsed.schemaVersion)
        assertEquals(1, parsed.items.size)

        val item = parsed.items[0]
        assertEquals(1, item.number)
        assertEquals("R1", item.refDes)
        assertEquals("resistor-0603", item.componentSlug)
        assertEquals("10k", item.comment)

        assertTrue(jsonContent.contains("\n"), "JSON должен быть отформатирован с переносами")
        assertTrue(jsonContent.contains("\"items\""), "Ключ items должен быть в JSON")
        assertTrue(jsonContent.contains("\"number\""), "Ключ number должен быть в JSON")
        assertTrue(jsonContent.contains("\"refDes\""), "Ключ refDes должен быть в JSON")
        assertTrue(jsonContent.contains("\"componentSlug\""), "Ключ componentSlug должен быть в JSON")
        assertTrue(jsonContent.contains("\"comment\""), "Ключ comment должен быть в JSON")
    }

    @Test
    fun `testFilePathUsesMachineNamePrefix`() {
        val paths = setupProjectPaths()

        // Проверяем, что файл BOM называется правильно
        assertEquals("testproject.bom.json", paths.bom.fileName.toString())
        assertEquals("testproject.nets.json", paths.nets.fileName.toString())
        assertEquals("testproject.schematic.json", paths.schematic.fileName.toString())
    }
}

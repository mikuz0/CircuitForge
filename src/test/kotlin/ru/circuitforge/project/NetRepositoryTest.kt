package ru.circuitforge.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files

class NetRepositoryTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun setupProjectPaths(): ProjectPaths {
        val projectDir = tempDir.resolve("testproject")
        Files.createDirectories(projectDir.resolve("components"))
        // Создаём BOM с R1 и C1 для валидации
        val bomDoc =
            BomDocument(
                schemaVersion = 1,
                items =
                    listOf(
                        BomItem(number = 1, refDes = "R1", componentId = "uuid-1", componentSlug = "resistor-0603", comment = ""),
                        BomItem(number = 2, refDes = "C1", componentId = "uuid-2", componentSlug = "capacitor-10uf", comment = ""),
                    ),
            )
        BomRepository.save(ProjectPaths(projectDir, "testproject"), bomDoc)
        return ProjectPaths(projectDir, "testproject")
    }

    @Test
    fun `testLoadEmpty`() {
        val paths = setupProjectPaths()

        val doc = NetRepository.load(paths)

        assertEquals(1, doc.schemaVersion)
        assertTrue(doc.nets.isEmpty())
    }

    @Test
    fun `testSaveAndLoad`() {
        val paths = setupProjectPaths()

        val doc =
            NetDocument(
                schemaVersion = 1,
                nets =
                    listOf(
                        Net(
                            name = "VCC",
                            connections =
                                listOf(
                                    NetConnection(refDes = "R1", pinNumber = "1"),
                                    NetConnection(refDes = "C1", pinNumber = "1"),
                                ),
                        ),
                        Net(
                            name = "GND",
                            connections =
                                listOf(
                                    NetConnection(refDes = "C1", pinNumber = "2"),
                                ),
                        ),
                    ),
            )

        NetRepository.save(paths, doc)

        val loaded = NetRepository.load(paths)
        assertEquals(2, loaded.nets.size)
        assertEquals("VCC", loaded.nets[0].name)
        assertEquals(2, loaded.nets[0].connections.size)
        assertEquals("GND", loaded.nets[1].name)
        assertEquals(1, loaded.nets[1].connections.size)
    }

    @Test
    fun `testValidateDuplicateNetNames`() {
        val paths = setupProjectPaths()

        val doc =
            NetDocument(
                schemaVersion = 1,
                nets =
                    listOf(
                        Net(name = "NET1", connections = emptyList()),
                        Net(name = "NET1", connections = emptyList()),
                    ),
            )

        val errors = NetRepository.validate(doc, paths)
        assertTrue(errors.any { it.contains("Duplicate net name") })
    }

    @Test
    fun `testValidateEmptyRefDes`() {
        val paths = setupProjectPaths()

        val doc =
            NetDocument(
                schemaVersion = 1,
                nets =
                    listOf(
                        Net(
                            name = "NET1",
                            connections =
                                listOf(
                                    NetConnection(refDes = "", pinNumber = "1"),
                                ),
                        ),
                    ),
            )

        val errors = NetRepository.validate(doc, paths)
        assertTrue(errors.any { it.contains("empty refDes") })
    }

    @Test
    fun `testValidateRefDesNotInBom`() {
        val paths = setupProjectPaths()

        val doc =
            NetDocument(
                schemaVersion = 1,
                nets =
                    listOf(
                        Net(
                            name = "NET1",
                            connections =
                                listOf(
                                    NetConnection(refDes = "X99", pinNumber = "1"),
                                ),
                        ),
                    ),
            )

        val errors = NetRepository.validate(doc, paths)
        assertTrue(errors.any { it.contains("not found in BOM") })
    }

    @Test
    fun `testValidateEmptyPinNumber`() {
        val paths = setupProjectPaths()

        val doc =
            NetDocument(
                schemaVersion = 1,
                nets =
                    listOf(
                        Net(
                            name = "NET1",
                            connections =
                                listOf(
                                    NetConnection(refDes = "R1", pinNumber = ""),
                                ),
                        ),
                    ),
            )

        val errors = NetRepository.validate(doc, paths)
        assertTrue(errors.any { it.contains("empty pinNumber") })
    }

    @Test
    fun `testValidateValidNets`() {
        val paths = setupProjectPaths()

        // Сначала создаём компоненты с правильными пинами
        val comp1Path = paths.projectDir.resolve("components").resolve("resistor-0603.cfcomp")
        ComponentRepository.save(
            comp1Path,
            Component(
                schemaVersion = 1, name = "Resistor 0603", description = "", refPrefix = "R",
                ports = listOf(Port(1, "1", "A", ""), Port(2, "2", "B", "")),
            ),
        )

        val comp2Path = paths.projectDir.resolve("components").resolve("capacitor-10uf.cfcomp")
        ComponentRepository.save(
            comp2Path,
            Component(
                schemaVersion = 1, name = "Capacitor 10uF", description = "", refPrefix = "C",
                ports = listOf(Port(1, "1", "A", ""), Port(2, "2", "B", "")),
            ),
        )

        val doc =
            NetDocument(
                schemaVersion = 1,
                nets =
                    listOf(
                        Net(
                            name = "VCC",
                            connections =
                                listOf(
                                    NetConnection(refDes = "R1", pinNumber = "1"),
                                    NetConnection(refDes = "C1", pinNumber = "1"),
                                ),
                        ),
                    ),
            )

        val errors = NetRepository.validate(doc, paths)
        assertTrue(errors.isEmpty(), "Ожидаем валидный netlist: $errors")
    }

    @Test
    fun `testFilePathUsesMachineNamePrefix`() {
        val paths = setupProjectPaths()

        val doc = NetDocument(schemaVersion = 1, nets = emptyList())
        NetRepository.save(paths, doc)

        assertTrue(Files.exists(paths.nets), "Файл nets должен существовать")
        assertEquals("testproject.nets.json", paths.nets.fileName.toString())
    }
}

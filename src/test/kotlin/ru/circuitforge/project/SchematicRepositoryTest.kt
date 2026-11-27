package ru.circuitforge.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files

class SchematicRepositoryTest {
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

        val doc = SchematicRepository.load(paths)

        assertEquals(1, doc.schemaVersion)
        assertTrue(doc.nodes.isEmpty())
    }

    @Test
    fun `testSaveAndLoad`() {
        val paths = setupProjectPaths()

        val nodes =
            listOf(
                SchematicNode(bomnetId = "node-1", x = 100.0, y = 200.0, rotation = 0.0),
                SchematicNode(bomnetId = "node-2", x = 350.0, y = 200.0, rotation = 90.0),
            )
        val doc = SchematicDocument(schemaVersion = 1, nodes = nodes)

        SchematicRepository.save(paths, doc)

        val loaded = SchematicRepository.load(paths)
        assertEquals(2, loaded.nodes.size)
        assertEquals("node-1", loaded.nodes[0].bomnetId)
        assertEquals(100.0, loaded.nodes[0].x)
        assertEquals(200.0, loaded.nodes[0].y)
        assertEquals(0.0, loaded.nodes[0].rotation)
        assertEquals(90.0, loaded.nodes[1].rotation)
    }

    @Test
    fun `testFilePathUsesMachineNamePrefix`() {
        val paths = setupProjectPaths()

        val doc = SchematicDocument(schemaVersion = 1, nodes = emptyList())
        SchematicRepository.save(paths, doc)

        assertTrue(Files.exists(paths.schematic), "Файл schematic должен существовать")
        assertEquals("testproject.schematic.json", paths.schematic.fileName.toString())
    }

    @Test
    fun `testSaveAndLoadPreservesRotation`() {
        val paths = setupProjectPaths()

        val nodes =
            listOf(
                SchematicNode(bomnetId = "n1", x = 0.0, y = 0.0, rotation = 180.0),
                SchematicNode(bomnetId = "n2", x = 50.0, y = 50.0, rotation = 270.0),
            )
        SchematicRepository.save(paths, SchematicDocument(schemaVersion = 1, nodes = nodes))

        val loaded = SchematicRepository.load(paths)
        assertEquals(180.0, loaded.nodes[0].rotation)
        assertEquals(270.0, loaded.nodes[1].rotation)
    }
}

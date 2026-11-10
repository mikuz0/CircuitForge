package ru.circuitforge.project

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files

class ProjectRepositoryTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `testCreateProject`() {
        val projectDir = ProjectRepository.create("TestProject", tempDir)

        // Проверка структуры папок
        assertTrue(Files.exists(tempDir.resolve("TestProject")), "Папка проекта должна быть создана")
        assertTrue(Files.isDirectory(tempDir.resolve("TestProject/components")), "Папка components должна быть создана")
        assertTrue(Files.isDirectory(tempDir.resolve("TestProject/nets")), "Папка nets должна быть создана")
        assertTrue(Files.isDirectory(tempDir.resolve("TestProject/exports")), "Папка exports должна быть создана")

        // Проверка файла проекта
        val projectFile = tempDir.resolve("TestProject/project.cfproj")
        assertTrue(Files.exists(projectFile), "Файл project.cfproj должен быть создан")

        // Проверка содержимого
        val project = ProjectRepository.load(projectFile)
        assertEquals("TestProject", project.name)
        assertEquals(1, project.version)
        assertNotNull(project.created)
    }

    @Test
    fun `testInvalidName`() {
        // Пустое имя
        val emptyException = assertThrows(IllegalArgumentException::class.java) {
            ProjectRepository.create("", tempDir)
        }
        assertTrue(emptyException.message!!.contains("blank"))

        // Имя с '/'
        val slashException = assertThrows(IllegalArgumentException::class.java) {
            ProjectRepository.create("invalid/name", tempDir)
        }
        assertTrue(slashException.message!!.contains("/"))

        // Имя с '\'
        val backslashException = assertThrows(IllegalArgumentException::class.java) {
            ProjectRepository.create("invalid\\name", tempDir)
        }
        assertTrue(backslashException.message!!.contains("\\"))
    }

    @Test
    fun `testScanProjects`() {
        // Создаём 2 проекта
        ProjectRepository.create("AlphaProject", tempDir)
        ProjectRepository.create("BetaProject", tempDir)

        val projects = ProjectRepository.scan(tempDir)

        assertEquals(2, projects.size)

        // Проверка: отсортировано по имени (alphabetically)
        assertEquals("AlphaProject", projects[0].name)
        assertEquals("BetaProject", projects[1].name)

        // Проверка путей
        assertTrue(projects[0].path.toString().contains("AlphaProject"))
        assertTrue(projects[1].path.toString().contains("BetaProject"))

        // Проверка, что project.cfproj существует
        assertTrue(
            Files.exists(projects[0].path.resolve("project.cfproj")),
            "project.cfproj должен существовать для первого проекта"
        )
        assertTrue(
            Files.exists(projects[1].path.resolve("project.cfproj")),
            "project.cfproj должен существовать для второго проекта"
        )
    }

    @Test
    fun `testDuplicateProjectThrows`() {
        ProjectRepository.create("TestProject", tempDir)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            ProjectRepository.create("TestProject", tempDir)
        }
        assertTrue(exception.message!!.contains("already exists"))
    }

    @Test
    fun `testSaveAndLoadProject`() {
        val projectFile = ProjectRepository.create("TestProject", tempDir)

        val loaded = ProjectRepository.load(projectFile)

        assertEquals("TestProject", loaded.name)
        assertEquals(1, loaded.version)
        assertNotNull(loaded.created)
    }
}

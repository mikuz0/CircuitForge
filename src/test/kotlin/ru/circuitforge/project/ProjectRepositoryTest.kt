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
        val projectFile = ProjectRepository.create("TestProject", tempDir)

        // machineName для "TestProject" = "testproject"
        val projectDir = tempDir.resolve("testproject")
        assertTrue(Files.exists(projectDir), "Папка проекта должна быть создана")
        assertTrue(Files.isDirectory(projectDir.resolve("components")), "Папка components должна быть создана")
        assertTrue(Files.isDirectory(projectDir.resolve("nets")), "Папка nets должна быть создана")
        assertTrue(Files.isDirectory(projectDir.resolve("exports")), "Папка exports должна быть создана")

        // Проверка файла проекта
        assertTrue(Files.exists(projectFile), "Файл project.cfproj должен быть создан")

        // Проверка содержимого
        val project = ProjectRepository.load(projectFile)
        assertEquals("TestProject", project.name)
        assertEquals("testproject", project.machineName)
        assertEquals(1, project.version)
        assertNotNull(project.created)
    }

    @Test
    fun `testInvalidName`() {
        // Пустое имя
        val emptyException =
            assertThrows(IllegalArgumentException::class.java) {
                ProjectRepository.create("", tempDir)
            }
        assertTrue(emptyException.message!!.contains("blank"))

        // Имя с '/'
        val slashException =
            assertThrows(IllegalArgumentException::class.java) {
                ProjectRepository.create("invalid/name", tempDir)
            }
        assertTrue(slashException.message!!.contains("/"))

        // Имя с '\'
        val backslashException =
            assertThrows(IllegalArgumentException::class.java) {
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

        // Проверка путей (machineName = slug)
        assertTrue(projects[0].path.toString().contains("alphaproject"))
        assertTrue(projects[1].path.toString().contains("betaproject"))

        // Проверка machineName
        assertEquals("alphaproject", projects[0].machineName)
        assertEquals("betaproject", projects[1].machineName)

        // Проверка, что project.cfproj существует
        assertTrue(
            Files.exists(projects[0].path.resolve("project.cfproj")),
            "project.cfproj должен существовать для первого проекта",
        )
        assertTrue(
            Files.exists(projects[1].path.resolve("project.cfproj")),
            "project.cfproj должен существовать для второго проекта",
        )
    }

    @Test
    fun `testDuplicateProjectThrows`() {
        ProjectRepository.create("TestProject", tempDir)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                ProjectRepository.create("TestProject", tempDir)
            }
        assertTrue(exception.message!!.contains("already exists"))
    }

    @Test
    fun `testSaveAndLoadProject`() {
        val projectFile = ProjectRepository.create("TestProject", tempDir)

        val loaded = ProjectRepository.load(projectFile)

        assertEquals("TestProject", loaded.name)
        assertEquals("testproject", loaded.machineName)
        assertEquals(1, loaded.version)
        assertNotNull(loaded.created)
    }

    @Test
    fun `testGenerateMachineName`() {
        assertEquals("testproject", ProjectRepository.generateMachineName("TestProject"))
        assertEquals("my-project", ProjectRepository.generateMachineName("My Project"))
        // Кириллица удаляется алгоритмом slug, остаются только латиница/цифры
        assertEquals("stm32", ProjectRepository.generateMachineName("STM32 плата"))
        assertEquals("a", ProjectRepository.generateMachineName("A"))
    }

    @Test
    fun `testGenerateMachineNameThrowsOnSpecialCharsOnly`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectRepository.generateMachineName("!!!")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectRepository.generateMachineName("   ")
        }
    }
}

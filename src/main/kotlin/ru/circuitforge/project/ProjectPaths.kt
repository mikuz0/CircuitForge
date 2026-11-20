package ru.circuitforge.project

import java.nio.file.Path

/**
 * Пути файлов проекта. Префикс файлов данных — machineName.
 */
class ProjectPaths(val projectDir: Path, val machineName: String) {
    val projectFile: Path = projectDir.resolve("project.cfproj")
    val bom: Path = projectDir.resolve("$machineName.bom.json")
    val nets: Path = projectDir.resolve("$machineName.nets.json")
    val schematic: Path = projectDir.resolve("$machineName.schematic.json")
    val componentsDir: Path = projectDir.resolve("components")
    val exportsDir: Path = projectDir.resolve("exports")
}

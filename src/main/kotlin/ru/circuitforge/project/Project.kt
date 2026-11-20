package ru.circuitforge.project

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Serializable project metadata stored in project.cfproj.
 */
@Serializable
data class Project(
    val name: String,
    val machineName: String,
    val version: Int = 1,
    @Serializable(with = InstantSerializer::class)
    val created: Instant = Instant.now()
)

data class ProjectInfo(
    val name: String,
    val machineName: String,
    val path: java.nio.file.Path
) : Comparable<ProjectInfo> {
    override fun compareTo(other: ProjectInfo): Int {
        return name.compareTo(other.name, ignoreCase = true)
    }
}


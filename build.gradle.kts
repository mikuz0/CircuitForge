plugins {
    kotlin("jvm") version "2.0.21"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("application")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.3.0"
}

group = "ru.circuitforge"
version = "0.1.0"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // JSVG — нативная поддержка SVG в JavaFX
    implementation("com.github.weisj:jsvg:2.2.0")
    implementation("com.github.weisj:jsvg-javafx:2.2.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

javafx {
    version = "21.0.9"
    modules = listOf(
        "javafx.controls",
        "javafx.fxml",
        "javafx.swing",
        "javafx.web"
    )
}

application {
    mainClass.set("ru.circuitforge.app.MainApp")
    applicationName = "CircuitForge"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// --- Additional JavaExec tasks for module launchers ---

val bomMainClass = "ru.circuitforge.ui.editor.BomApp"
val netMainClass = "ru.circuitforge.ui.nets.NetApp"
val componentsMainClass = "ru.circuitforge.ui.components.ComponentsApp"

val runBom by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run Bom module"
    mainClass.set(bomMainClass)
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

val runNet by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run Net module"
    mainClass.set(netMainClass)
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

val runComponents by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run Components module"
    mainClass.set(componentsMainClass)
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// --- Extra launcher scripts generated from the main start script ---

tasks.register("generateExtraScripts") {
    description = "Generates extra launcher scripts (Bom, Net, Components) in build/scripts"
    group = "build"

    dependsOn(tasks.named("startScripts"))
    inputs.dir(layout.buildDirectory.dir("scripts"))
    val scriptsDir = layout.buildDirectory.dir("scripts").get().asFile
    outputs.file(File(scriptsDir, "CircuitForgeBom"))
    outputs.file(File(scriptsDir, "CircuitForgeBom.bat"))
    outputs.file(File(scriptsDir, "CircuitForgeNet"))
    outputs.file(File(scriptsDir, "CircuitForgeNet.bat"))
    outputs.file(File(scriptsDir, "CircuitForgeComponents"))
    outputs.file(File(scriptsDir, "CircuitForgeComponents.bat"))

    doLast {
        val srcDirFile = layout.buildDirectory.dir("scripts").get().asFile
        val mainScript = File(srcDirFile, "CircuitForge")
        val mainBat = File(srcDirFile, "CircuitForge.bat")
        val scriptsDirFile = scriptsDir

        if (!mainScript.exists()) return@doLast

        val content = mainScript.readText()
        val oldLine = "        ru.circuitforge.app.MainApp \\"

        val modules = listOf(
            "Bom" to bomMainClass,
            "Net" to netMainClass,
            "Components" to componentsMainClass
        )

        for ((name, mc) in modules) {
            val mainClassLine = "        ru.circuitforge.app.MainApp \\".replace("ru.circuitforge.app.MainApp", mc)


            // Copy and replace main class in shell script
            val shellContent = content.replace(oldLine, mainClassLine)
            val outFile = File(scriptsDirFile, "CircuitForge$name")
            outFile.parentFile.mkdirs()
            outFile.writeText(shellContent)
            outFile.setExecutable(true, false)


            // Copy and replace main class in batch script
            if (mainBat.exists()) {
                val batContent = mainBat.readText()
                    .replace("ru.circuitforge.app.MainApp", mc)
                val batFile = File(scriptsDirFile, "CircuitForge$name.bat")
                batFile.parentFile.mkdirs()
                batFile.writeText(batContent)

            }
        }
    }
}

// --- installDist depends on extra scripts ---

tasks.named("installDist") {
    dependsOn("generateExtraScripts")
}

tasks.named("distTar") {
    dependsOn("generateExtraScripts")
}

tasks.named("distZip") {
    dependsOn("generateExtraScripts")
}

tasks.test {
    useJUnitPlatform()
}

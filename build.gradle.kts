import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    java
    `maven-publish`
    kotlin("jvm")
    alias(libs.plugins.loom)
    id("dev.deftu.gradle.multiversion")
}

version = project.property("mod.version").toString()
group = "net.sbo"

private val mcProject: String = project.name
private val mcVersion: String = mcProject.replace("-fabric", "")

private fun versionedProperty(name: String): String =
    project.property("$name.$mcVersion")?.toString() ?: throw AssertionError("build.gradle.kts needs updating for $mcProject")

loom {
    // Identical for all MC versions, so the root copy is used directly.
    accessWidenerPath = rootProject.file("src/main/resources/guilib.classtweaker")

    runs.configureEach {
        generateRunConfig.set(true)
        preferGradleTask = true
    }

    // Visual checks: ./gradlew :26.2-fabric:runClient -Pguilib.dev.shots=all (see DevAutomation).
    runs.named("client") {
        project.findProperty("guilib.dev.shots")?.let { vmArg("-Dguilib.dev.shots=$it") }
        project.properties.filterKeys { it.startsWith("guilib.dev.") && it != "guilib.dev.shots" }.forEach { (k, v) -> vmArg("-D$k=$v") }
    }
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.release = Integer.parseInt(versionedProperty("java.version"))
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(versionedProperty("java.version")))
        freeCompilerArgs.addAll("-Xbackend-threads=0")
        moduleName.set("guilib-$mcVersion")
    }
}

val runDirectory = rootProject.file("run")
runDirectory.mkdirs()

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir(runDirectory)
    // Used by CoreIsolationTest to verify that net.sbo.guilib.core stays free of Minecraft imports.
    systemProperty("guilib.srcDir", rootProject.file("src/main/kotlin").absolutePath)
}

val archiveName = "guilib-$mcProject"

afterEvaluate {
    tasks.named<Jar>("jar") {
        destinationDirectory.set(rootProject.layout.buildDirectory.asFile.get().resolve("versions"))
        archiveBaseName.set(archiveName)
    }
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf(
        "mod_name" to project.property("mod.name"),
        "mod_description" to project.property("mod.description"),
        "mod_id" to project.property("mod.id"),
        "mod_version" to project.property("mod.version"),
        "mod_group" to project.property("mod.group"),
        "mc_version_constraint" to (project.findProperty("mc$mcVersion.constraint")?.toString() ?: "~$mcVersion"),
        "fabric_loader_version" to project.property("fabricloader.version"),
        "fabric_api_version" to versionedProperty("fabricapi.version"),
        "fabric_language_kotlin_version" to project.property("fabriclanguagekotlin.version"),
        "java_version_major" to versionedProperty("java.version"),
    )
    props.forEach { (k, v) -> inputs.property(k, v) }
    filesMatching("fabric.mod.json") { expand(props) }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")

    implementation("net.fabricmc:fabric-loader:${property("fabricloader.version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${versionedProperty("fabricapi.version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabriclanguagekotlin.version")}")

    implementation(include(libs.jsvg.get())!!)

    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
}

publishing {
    publications {
        create<MavenPublication>("mod") {
            artifactId = archiveName
            from(components["java"])
        }
    }
}

// The preprocessor of the 26.2 node reads the 26.1.2 classpath; order the tasks so parallel builds don't race (same as SBO).
tasks.findByName("preprocessCode")?.dependsOn(":26.1.2-fabric:compileKotlin")
tasks.findByName("preprocessTestCode")?.dependsOn(":26.1.2-fabric:compileTestKotlin")

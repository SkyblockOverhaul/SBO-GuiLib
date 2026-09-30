package net.sbo.guilib

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** net.sbo.guilib.core must stay pure Kotlin so it can be unit-tested and reused without Minecraft. */
class CoreIsolationTest {
    @Test
    fun coreHasNoMinecraftImports() {
        val srcDir = System.getProperty("guilib.srcDir") ?: return
        val core = File(srcDir, "net/sbo/guilib/core")
        val forbidden = listOf("import net.minecraft", "import com.mojang", "import net.fabricmc", "import net.sbo.guilib.fabric")
        val offenders = core.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filter { line -> forbidden.any { line.trimStart().startsWith(it) } }.map { "${file.name}: $it" }
        }.toList()
        assertTrue(offenders.isEmpty(), "core must not depend on Minecraft/Fabric:\n" + offenders.joinToString("\n"))
    }
}

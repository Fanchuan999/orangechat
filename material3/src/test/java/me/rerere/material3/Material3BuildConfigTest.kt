/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.material3

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Material3BuildConfigTest {
    @Test
    fun mainSourceSetRequiresKotlinSubmoduleSourcesWithoutAddingDuplicateJavaSources() {
        val script = Files.readString(findBuildScript())

        assertTrue(
            "material3 should fail early when the material-color-utilities submodule is missing",
            script.contains("Missing material3/material-color-utilities sources."),
        )
        assertTrue(
            "material3 main sourceSet should compile the material-color-utilities Kotlin sources",
            script.contains("""kotlin.srcDir(materialColorUtilitiesKotlinDir)"""),
        )
        assertFalse(
            "material3 should not compile duplicate material-color-utilities Java sources",
            script.contains("""java.srcDir("material-color-utilities/java")"""),
        )
    }

    private fun findBuildScript(): Path {
        val candidates = listOf(
            Path.of("material3", "build.gradle.kts"),
            Path.of("build.gradle.kts"),
        )
        return candidates.firstOrNull(Files::exists)
            ?: error("Could not locate material3/build.gradle.kts from test runtime")
    }
}

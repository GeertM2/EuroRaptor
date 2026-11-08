package nl.euroraptor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Real screenshot regression test of the production red-ring detector.
 * ML Kit OCR is not exercised by these JVM tests.
 */
class SpeedBadgeScreenshotTest {
    @Test fun detectRedSpeedLimitRingInUploadedScreenshots() {
        val roots = listOf(
            File("../tests/screenshots"),
            File("tests/screenshots"),
            File("src/test/resources/screenshots"),
            File("app/src/test/resources/screenshots")
        ).filter { it.isDirectory }
        val pngs = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension.equals("png", true) }.toList()
        }.distinctBy { it.canonicalPath }

        val requiredLimits = listOf(30, 70, 100, 130)
        val byLimit = requiredLimits.associateWith { limit ->
            val code = limit.toString().padStart(3, '0')
            val regex = Regex("^limit[_-]?$code(?:[_-].*)?\\.png$", RegexOption.IGNORE_CASE)
            pngs.filter { regex.matches(it.name) }
        }
        val missing = requiredLimits.filter { byLimit.getValue(it).isEmpty() }
        assertTrue(
            "Missing real PNG screenshots for limits: ${missing.joinToString()}. " +
                "Expected limit_030_*.png, limit_070_*.png, limit_100_*.png, limit_130_*.png " +
                "in tests/screenshots/ (or app/src/test/resources/screenshots/). " +
                "Found PNGs: ${pngs.map { it.name }}",
            missing.isEmpty()
        )

        val failures = mutableListOf<String>()
        byLimit.forEach { (limit, files) ->
            files.forEach { file ->
                val img = PngFixture.read(file)
                if (img == null) {
                    failures.add("${file.name}: cannot decode PNG")
                } else {
                    val badge = SpeedBadgeDetector.find(img.width, img.height) { x, y -> img.getRGB(x, y) }
                    if (badge == null) failures.add("${file.name}: red-ring detector missed ${limit} km/h sign")
                    else println("${file.name}: expected=$limit badge x=${badge.x} y=${badge.y} r=${badge.radius} score=${badge.score} (OCR not tested)")
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

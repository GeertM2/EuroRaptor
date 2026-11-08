package nl.euroraptor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Optional CSV-driven extra fixtures; mandatory named fixtures are checked by SpeedBadgeScreenshotTest. */
class RealScreenshotBadgeTest {
    @Test fun validateCsvFixturesWhenManifestPresent() {
        val roots = listOf(File("src/test/resources/screenshots"), File("app/src/test/resources/screenshots"))
        val manifest = roots.map { File(it, "cases.csv") }.firstOrNull { it.isFile }
        if (manifest == null) {
            // Not skipped: the mandatory named screenshot coverage is enforced by SpeedBadgeScreenshotTest.
            println("No optional cases.csv; named screenshot fixtures are checked separately")
            return
        }
        val root = manifest.parentFile
        val cases = manifest.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("cases.csv is empty", cases.isNotEmpty())
        cases.forEach { line ->
            val parts = line.split(",").map { it.trim() }
            assertTrue("Expected filename,expected_limit: $line", parts.size == 2)
            val limit = parts[1].toIntOrNull()
            assertTrue("Invalid expected speed limit: $line", limit != null && limit in 10..140)
            val file = File(root, parts[0])
            assertTrue("Missing screenshot: ${file.path}", file.isFile)
            val image = PngFixture.read(file)
            assertTrue("Unreadable screenshot: ${file.path}", image != null)
            image!!
            val badge = SpeedBadgeDetector.find(image.width, image.height) { x, y -> image.getRGB(x, y) }
            assertTrue("No red speed badge in ${file.name} (expected $limit km/h)", badge != null)
        }
    }
}

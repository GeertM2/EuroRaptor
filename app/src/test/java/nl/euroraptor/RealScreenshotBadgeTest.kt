package nl.euroraptor

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Real Google Maps screenshot regression tests.
 *
 * Put PNG files in app/src/test/resources/screenshots/ and add their
 * filename plus expected speed limit to cases.csv.
 *
 * A missing fixture directory is reported as SKIPPED, not PASSED.
 * This test checks badge location only; OCR needs a separate instrumented test.
 */
class RealScreenshotBadgeTest {
    @Test fun detectsBadgeInRealScreenshots() {
        val root = File("src/test/resources/screenshots")
        val manifest = File(root, "cases.csv")
        assumeTrue("SKIPPED: real screenshot fixtures not installed", manifest.isFile)
        val cases = manifest.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(",").map { it.trim() }
                require(parts.size == 2) { "Expected filename,expected_limit: $line" }
                require(parts[1].toInt() in 10..140) { "Invalid limit: $line" }
                parts[0] to parts[1].toInt()
            }
        assertTrue("cases.csv has no screenshots", cases.isNotEmpty())
        cases.forEach { (filename, expectedLimit) ->
            val file = File(root, filename)
            assertTrue("Missing screenshot: $filename", file.isFile)
            val image = ImageIO.read(file)
            assertNotNull("Unreadable screenshot: $filename", image)
            val badge = SpeedBadgeDetector.find(image.width, image.height) { x, y ->
                image.getRGB(x, y)
            }
            assertNotNull("No red speed badge detected in $filename (expected $expectedLimit)", badge)
            println("FOUND badge in $filename: x=${badge!!.x} y=${badge.y} r=${badge.radius}; expected limit=$expectedLimit (OCR not tested)")
        }
    }
}

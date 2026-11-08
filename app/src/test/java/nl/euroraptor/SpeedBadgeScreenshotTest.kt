package nl.euroraptor

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs the real production pixel detector on screenshot PNGs in GitHub Actions.
 * ML Kit OCR itself requires Android instrumentation and is NOT exercised here.
 */
class SpeedBadgeScreenshotTest {
 @Test fun detectRedSpeedLimitRingInUploadedScreenshots() {
  val folder=File("../tests/screenshots")
  val files=folder.walkTopDown().filter { it.isFile && it.extension.equals("png",true) }.toList()
  // Only conventionally named fixtures have an unambiguous expected limit.
  val named=files.filter { Regex("limit[_-]?(030|070|100|130)",RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
  assumeTrue("No named screenshot PNG fixtures yet; see tests/screenshots/README.md",named.isNotEmpty())
  val failures=mutableListOf<String>()
  for(file in named) {
   val img=PngFixture.read(file)
   if(img==null) {failures.add("${file.name}: cannot decode PNG");continue}
   val found=SpeedBadgeDetector.find(img.width,img.height) { x,y -> img.getRGB(x,y) }
   if(found==null) failures.add("${file.name}: red-ring detector found no sign")
   else println("${file.name}: circle x=${found.x} y=${found.y} r=${found.radius} score=${found.score}")
  }
  assertTrue(failures.joinToString("\n"),failures.isEmpty())
 }
}

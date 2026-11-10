package nl.euroraptor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real ML Kit OCR on all six screenshots, not a simulated JVM OCR test. */
@RunWith(AndroidJUnit4::class)
class SpeedBadgeOcrInstrumentedTest {
    @Test fun recognizesEveryScreenshotLimit() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixtures = linkedMapOf(
            "30_kmh_real.png" to 30,
            "70 kmh test.png" to 70,
            "80 kmh test.png" to 80,
            "100_kmh_real_108.png" to 100,
            "100_kmh_real_114.png" to 100,
            "130_kmh_real.png" to 130
        )
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            for ((filename, expected) in fixtures) {
                val bitmap = context.assets.open("screenshots/$filename").use { BitmapFactory.decodeStream(it) }
                assertNotNull("$filename could not be decoded", bitmap)
                val badge = SpeedBadgeDetector.find(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
                assertNotNull("$filename: red speed badge not found", badge)
                val circle = badge!!
                // Identical crop/inset and upscale as ScreenProbeService.
                val diameter = circle.radius * 2
                val inset = (diameter * 0.16).toInt().coerceAtLeast(1)
                val x = (circle.x - circle.radius + inset).coerceIn(0, bitmap.width - 1)
                val y = (circle.y - circle.radius + inset).coerceIn(0, bitmap.height - 1)
                val w = (diameter - 2 * inset).coerceIn(1, bitmap.width - x)
                val h = (diameter - 2 * inset).coerceIn(1, bitmap.height - y)
                val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
                val scaled = Bitmap.createScaledBitmap(crop, w * 5, h * 5, true)
                val latch = CountDownLatch(1)
                var raw: String? = null
                var failure: Exception? = null
                recognizer.process(InputImage.fromBitmap(scaled, 0))
                    .addOnSuccessListener { raw = it.text.trim(); latch.countDown() }
                    .addOnFailureListener { failure = it; latch.countDown() }
                try {
                    check(latch.await(45, TimeUnit.SECONDS)) { "$filename: ML Kit timed out" }
                    if (failure != null) throw AssertionError("$filename: ML Kit error", failure)
                    val detected = Regex("""\b(10|20|30|40|50|60|70|80|90|100|110|120|130)\b""")
                        .find(raw.orEmpty())?.value?.toIntOrNull()
                    println("OCR FIXTURE $filename expected=$expected detected=$detected raw=${raw.orEmpty()}")
                    assertEquals("$filename: incorrect OCR (raw=${raw.orEmpty()})", expected, detected)
                } finally {
                    scaled.recycle(); crop.recycle(); bitmap.recycle()
                }
            }
        } finally {
            recognizer.close()
        }
    }
}

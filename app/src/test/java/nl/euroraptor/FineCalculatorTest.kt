package nl.euroraptor

import org.junit.Assert.*
import org.junit.Test

class FineCalculatorTest {
 @Test fun stationaryShowsZero() {
  assertEquals(0, (FineCalculator.calculateWorstCase(0.0, 30) as FineCalculator.Result.Amount).euros)
 }
 @Test fun simulated50In30ShowsFine() {
  // 50 - 3 correction - 30 = 17 km/h excess.
  assertEquals(216, (FineCalculator.calculateWorstCase(50.0, 30) as FineCalculator.Result.Amount).euros)
 }
 @Test fun noLimitIsUnknown() {
  assertTrue(FineCalculator.calculateWorstCase(80.0, null) is FineCalculator.Result.Unknown)
 }
 @Test fun belowFineThresholdShowsZero() {
  assertEquals(0, (FineCalculator.calculateWorstCase(36.0, 30) as FineCalculator.Result.Amount).euros)
 }
 @Test fun higherSpeedUsesThreePercentCorrection() {
  assertEquals(0, (FineCalculator.calculateWorstCase(103.0, 100) as FineCalculator.Result.Amount).euros)
 }
}

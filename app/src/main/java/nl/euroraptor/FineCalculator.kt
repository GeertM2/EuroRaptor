package nl.euroraptor

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Indicative Dutch 2026 passenger-car tariffs; EUR 9 administration fee added.
 * GPS speed is not a certified enforcement measurement.
 */
object FineCalculator {
    enum class RoadType { URBAN, RURAL, MOTORWAY }
    sealed class Result {
        data class Amount(val euros: Int) : Result()
        data class Warning(val label: String) : Result()
        object Unknown : Result()
    }
    private val urban = intArrayOf(37,46,56,65,73,84,95,129,140,155,166,179,192,207,223,237,255,272,289,308,324,345,363,387,405,426,446)
    private val rural = intArrayOf(33,42,50,59,68,79,89,121,134,147,159,172,184,197,210,227,243,258,273,289,308,326,345,362,381,404,424)
    private val motorway = intArrayOf(28,34,49,49,56,64,84,115,126,136,147,159,171,185,200,213,229,244,258,273,289,304,321,337,350,369,389,408,427,446,468,488,508,520,520,520,520)
    fun calculate(measuredKmh: Double, limitKmh: Int?, roadType: RoadType?, specialZone: Boolean = false): Result {
        if (!measuredKmh.isFinite() || measuredKmh < 0 || limitKmh == null || limitKmh <= 0) return Result.Unknown
        val measured = measuredKmh.roundToInt()
        val correction = if (measured <= 100) 3 else ceil(measured * 0.03).toInt()
        val excess = measured - correction - limitKmh
        if (excess < 4) return Result.Amount(0)
        if (roadType == null || specialZone) return Result.Unknown
        if (excess >= 50) return Result.Warning("RIJBEWIJS")
        if (excess >= 41) return Result.Warning("RECHTER")
        if (roadType != RoadType.MOTORWAY && excess >= 31) return Result.Warning("OM")
        val base = when (roadType) {
            RoadType.URBAN -> urban.getOrNull(excess - 4)
            RoadType.RURAL -> rural.getOrNull(excess - 4)
            RoadType.MOTORWAY -> motorway.getOrNull(excess - 4)
        } ?: return Result.Unknown
        return Result.Amount(base + 9)
    }
}

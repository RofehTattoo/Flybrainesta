package com.example.flybrain

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Environmental odor field and bilateral antenna sampling in normalized scene units.
 * This is a sensor-interface model only: it returns concentrations and never
 * produces a turn, heading, motor command, or body displacement.
 */
internal object OlfactorySensorModel {
    // Scene spans roughly one unit. A local field avoids the previous near-uniform
    // concentration across the arena while retaining a measurable plume at range.
    const val ODOR_SIGMA = 0.30f
    const val ANTENNA_FORWARD = 0.018f
    const val ANTENNA_HALF_SPACING = 0.035f

    fun sampleAntenna(
        flyX: Float,
        flyY: Float,
        heading: Float,
        foodX: Float,
        foodY: Float,
        side: Int,
        sigma: Float = ODOR_SIGMA
    ): Float {
        require(side == -1 || side == 1) { "Antenna side must be -1 (left) or +1 (right)" }
        require(sigma.isFinite() && sigma > 0f) { "Odor sigma must be finite and positive" }
        val ca = cos(heading)
        val sa = sin(heading)
        val lateral = ANTENNA_HALF_SPACING * side.toFloat()
        // Preserve the existing body-relative coordinate convention.
        val ax = flyX + ca * ANTENNA_FORWARD - sa * lateral
        val ay = flyY + sa * ANTENNA_FORWARD + ca * lateral
        val distance = hypot(foodX - ax, foodY - ay)
        return exp((-(distance * distance) / (2f * sigma * sigma)).toDouble())
            .toFloat().coerceIn(0f, 1f)
    }

    fun bilateralPresence(left: Float, right: Float): Float =
        ((left.coerceIn(0f, 1f) + right.coerceIn(0f, 1f)) * 0.5f)

    /** Signed, normalized sensor contrast; diagnostic only, never a motor command. */
    fun normalizedContrast(left: Float, right: Float): Float =
        ((left - right) / (left + right + 0.001f)).coerceIn(-1f, 1f)
}

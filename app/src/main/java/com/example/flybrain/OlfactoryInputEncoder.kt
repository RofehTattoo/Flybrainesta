package com.example.flybrain

/**
 * Converts bilateral environmental odor concentrations into bounded ORN input.
 *
 * A shared hard clamp applied independently to each antenna destroys small
 * left/right differences whenever both raw currents exceed the cap. This encoder
 * compresses the common-mode component into the available range while preserving
 * the signed bilateral contrast (up to the physically representable limit).
 * It does not produce a motor command or alter the connectome.
 */
internal object OlfactoryInputEncoder {
    data class Encoded(
        val left: Float,
        val center: Float,
        val right: Float
    )

    fun encode(left: Float, right: Float, gain: Float, limit: Float): Encoded {
        require(left.isFinite() && right.isFinite() && gain.isFinite() && limit.isFinite()) {
            "Olfactory inputs, gain, and limit must be finite"
        }
        require(left >= 0f && right >= 0f) { "Odor concentrations must be non-negative" }
        require(gain >= 0f) { "Olfactory gain must be non-negative" }
        require(limit > 0f) { "Sensory current limit must be positive" }

        val rawLeft = left * gain
        val rawRight = right * gain
        val rawMean = (rawLeft + rawRight) * 0.5f
        val maxContrast = limit * 0.5f
        val contrast = ((rawLeft - rawRight) * 0.5f).coerceIn(-maxContrast, maxContrast)
        val absContrast = kotlin.math.abs(contrast)

        // Keep both channels in [0, limit]. If the common component would
        // saturate, reserve enough headroom for the measured signed contrast.
        val common = rawMean.coerceIn(absContrast, limit - absContrast)
        val encodedLeft = (common + contrast).coerceIn(0f, limit)
        val encodedRight = (common - contrast).coerceIn(0f, limit)
        // Unknown-side ORNs receive the same common-mode component used by the
        // bilateral channels, not an independently hard-clamped value that can
        // saturate earlier and erase the calibrated presence/contrast relationship.
        val center = common.coerceIn(0f, limit)
        return Encoded(encodedLeft, center, encodedRight)
    }
}

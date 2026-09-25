package com.example.flybrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlfactoryInputEncoderTest {
    private val limit = 0.55f

    @Test fun saturatedCommonModePreservesBilateralContrast() {
        // Reproduces the app's starting geometry: both raw channels exceed the
        // old 0.55 cap, but the concentrations are not identical.
        val left = 0.8881f
        val right = 0.8849f
        val result = OlfactoryInputEncoder.encode(left, right, gain = 6f, limit = limit)

        assertTrue("left should remain brighter than right", result.left > result.right)
        assertTrue(result.left <= limit)
        assertTrue(result.right <= limit)
        assertTrue(result.left >= 0f)
        assertTrue(result.right >= 0f)
        assertEquals((left - right) * 6f, result.left - result.right, 0.00002f)
    }

    @Test fun swappingAntennasSwapsOutputAndReversesContrast() {
        val lr = OlfactoryInputEncoder.encode(0.7f, 0.3f, gain = 6f, limit = limit)
        val rl = OlfactoryInputEncoder.encode(0.3f, 0.7f, gain = 6f, limit = limit)
        assertEquals(lr.left, rl.right, 0.000001f)
        assertEquals(lr.right, rl.left, 0.000001f)
        assertTrue(lr.left > lr.right)
        assertTrue(rl.left < rl.right)
    }

    @Test fun equalInputsRemainSymmetricAndBounded() {
        val result = OlfactoryInputEncoder.encode(0.9f, 0.9f, gain = 6f, limit = limit)
        assertEquals(result.left, result.right, 0f)
        assertEquals(limit, result.left, 0f)
        assertEquals(limit, result.center, 0f)
    }

    @Test fun odorFieldHasLocalFalloffInsteadOfArenaWideSaturation() {
        val near = OlfactorySensorModel.bilateralPresence(
            OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .55f, .5f, -1),
            OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .55f, .5f, 1)
        )
        val far = OlfactorySensorModel.bilateralPresence(
            OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .95f, .5f, -1),
            OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .95f, .5f, 1)
        )
        assertTrue("near food must produce stronger odor than distant food", near > far)
        assertTrue("distant odor must not saturate presence", far < 0.5f)
    }

    @Test fun symmetricFoodAheadProducesNoFalseBilateralBias() {
        val left = OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .8f, .5f, -1)
        val right = OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .8f, .5f, 1)
        assertEquals(left, right, 0.000001f)
        assertEquals(0f, OlfactorySensorModel.normalizedContrast(left, right), 0.000001f)
    }

    @Test fun reversingFoodSideReversesOnlySensoryContrast() {
        val leftFoodLeft = OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .65f, .42f, -1)
        val leftFoodRight = OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .65f, .42f, 1)
        val rightFoodLeft = OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .65f, .58f, -1)
        val rightFoodRight = OlfactorySensorModel.sampleAntenna(.5f, .5f, 0f, .65f, .58f, 1)
        assertTrue(OlfactorySensorModel.normalizedContrast(leftFoodLeft, leftFoodRight) != 0f)
        assertEquals(
            -OlfactorySensorModel.normalizedContrast(leftFoodLeft, leftFoodRight),
            OlfactorySensorModel.normalizedContrast(rightFoodLeft, rightFoodRight),
            0.015f
        )
    }

    @Test fun zeroInputProducesZeroCurrent() {
        val result = OlfactoryInputEncoder.encode(0f, 0f, gain = 6f, limit = limit)
        assertEquals(0f, result.left, 0f)
        assertEquals(0f, result.center, 0f)
        assertEquals(0f, result.right, 0f)
    }
}

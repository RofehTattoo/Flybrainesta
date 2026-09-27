package com.example.flybrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LeggedSensorimotorActuatorTest {
    @Test
    fun noMotorOutput_doesNotAdvanceLegPhasesOrBody() {
        val a = LeggedSensorimotorActuator()
        repeat(50) { a.step(FloatArray(6), 0f, .02f) }
        assertTrue(a.forwardVelocity < 1e-5f)
        assertTrue(a.lateralVelocity < 1e-5f)
        assertTrue(a.yawRate < 1e-5f)
        assertEquals(0f, a.phase.sum(), 1e-5f)
    }

    @Test
    fun bilateralMotorOutput_drivesSixLegMechanicsAndFeedback() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .35f }
        repeat(80) { a.step(motor, 0f, .02f) }
        assertTrue(a.forwardVelocity > .03f)
        assertTrue(a.supportMean > .05f)
        assertTrue(a.proprioceptionGlobal > .02f)
        assertTrue(a.phase.any { it > .1f })
    }

    @Test
    fun rightSupportImbalance_producesPositiveYaw() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { i -> if (i < 3) .10f else .60f }
        repeat(80) { a.step(motor, 0f, .02f) }
        assertTrue(a.yawRate > 0f)
        assertTrue(a.supportBalance > 0f)
    }

    @Test
    fun walkOff_reducesMechanicalDrive() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .55f }
        repeat(100) { a.step(motor, 0f, .02f) }
        val walking = a.forwardVelocity
        repeat(100) { a.step(motor, 1f, .02f) }
        assertTrue(a.forwardVelocity < walking)
        assertTrue(a.forwardVelocity < .03f)
    }
}

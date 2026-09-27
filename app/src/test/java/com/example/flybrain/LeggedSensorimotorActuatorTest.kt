package com.example.flybrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeggedSensorimotorActuatorTest {
    @Test
    fun noMotorOutput_doesNotInventGaitOrBodyMotion() {
        val a = LeggedSensorimotorActuator()
        repeat(100) { a.step(FloatArray(6), 0f, .02f) }
        assertTrue(a.forwardVelocity < 1e-5f)
        assertTrue(a.lateralVelocity < 1e-5f)
        assertTrue(a.yawRate < 1e-5f)
        assertEquals(0f, a.motorActivation.sum(), 1e-5f)
        assertEquals(0f, a.jointState.sum(), 1e-5f)
    }

    @Test
    fun motorEnvelope_movesJointAndProducesOnlyTransientContactForce() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { 12f }
        // Neural motor output rises from zero, causing a mechanically coupled stroke.
        repeat(5) { a.step(motor, 0f, .02f) }
        assertTrue(a.motorActivation.any { it > .1f })
        assertTrue(a.jointState.any { it > .1f })
        assertTrue(a.forwardVelocity > 0f)
        assertTrue(a.contact.all { it in 0f..1f })
        // A constant rate does not create an independent periodic gait clock.
        val heldVelocity = a.forwardVelocity
        repeat(100) { a.step(motor, 0f, .02f) }
        assertTrue(a.forwardVelocity < heldVelocity)
    }

    @Test
    fun bilateralMotorEnvelope_hasNoSyntheticYaw() {
        val a = LeggedSensorimotorActuator()
        repeat(8) { a.step(FloatArray(6) { 12f }, 0f, .02f) }
        assertTrue(kotlin.math.abs(a.yawRate) < .03f)
    }

    @Test
    fun rightLeftTemporalAsymmetry_canProduceYaw() {
        val a = LeggedSensorimotorActuator()
        repeat(8) { a.step(FloatArray(6) { i -> if (i < 3) 0f else 12f }, 0f, .02f) }
        assertTrue(a.yawRate > 0f)
    }

    @Test
    fun walkOffSuppressesNeuralToMechanicalDrive() {
        val a = LeggedSensorimotorActuator()
        repeat(10) { a.step(FloatArray(6) { 12f }, 0f, .02f) }
        val active = a.forwardVelocity
        repeat(30) { a.step(FloatArray(6) { 12f }, 1f, .02f) }
        assertTrue(a.forwardVelocity < active)
    }

    @Test
    fun wallContactRemovesInwardVelocityAndCreatesSensoryPressure() {
        val a = LeggedSensorimotorActuator()
        repeat(5) { a.step(FloatArray(6) { 12f }, 0f, .02f) }
        a.applyWallConstraint(0f, 1f, 0f, .02f)
        assertTrue(a.wallPressure >= .18f)
        assertTrue(a.forwardVelocity >= 0f)
    }
}

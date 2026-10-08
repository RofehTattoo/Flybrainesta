package com.example.flybrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LeggedSensorimotorActuatorTest {
    @Test
    fun noMotorOutput_doesNotAdvanceLegPhasesOrBody() {
        val a = LeggedSensorimotorActuator()
        repeat(50) { a.step(FloatArray(6), 0f, 0f, .02f) }
        assertTrue(a.forwardVelocity < 1e-5f)
        assertTrue(a.lateralVelocity < 1e-5f)
        assertTrue(a.yawRate < 1e-5f)
        val initialPhaseSum = (.50f + .08f + .66f + 0f + .58f + .16f) * (Math.PI.toFloat() * 2f)
        assertEquals(initialPhaseSum, a.phase.sum(), 1e-5f)
    }

    @Test
    fun phasicMotorOutput_drivesSixLegMechanicsAndFeedback() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .35f }
        repeat(80) { step ->
            // The actuator receives the already-decoded phasic neural drive.
            // Alternate short bursts with silence so the test represents an
            // actual motor pattern rather than tonic/background firing.
            val burst = if ((step / 5) % 2 == 0) motor else FloatArray(6)
            a.step(burst, 0f, 0f, .02f)
        }
        assertTrue(a.forwardVelocity > .03f)
        assertTrue(a.supportMean > .05f)
        assertTrue(a.proprioceptionGlobal > .02f)
        assertTrue(a.phase.any { it > .1f })
    }

    @Test
    fun rightSupportImbalance_producesPositiveYaw() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { i -> if (i < 3) .10f else .60f }
        repeat(80) { a.step(motor, 0f, 0f, .02f) }
        assertTrue(a.yawRate > 0f)
        assertTrue(a.supportBalance > 0f)
    }


    @Test
    fun symmetricPhasicMotorOutput_hasNoPersistentYawBias() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .45f }
        var yawSum = 0f
        var supportSum = 0f
        var forceSum = 0f
        var samples = 0
        repeat(2500) { step ->
            val burst = if ((step / 5) % 2 == 0) motor else FloatArray(6)
            a.step(burst, 0f, 0f, .02f)
            if (step >= 500) {
                yawSum += a.yawRate
                supportSum += a.supportCoverage
                forceSum += a.forwardForceProxy
                samples++
            }
        }
        val averageYaw = yawSum / samples.toFloat()
        val averageSupport = supportSum / samples.toFloat()
        val averageForce = forceSum / samples.toFloat()
        assertTrue(abs(averageYaw) < .03f)
        assertTrue(averageSupport > .10f)
        assertTrue(averageForce > .20f)
        assertTrue(a.bilateralMechanicalSymmetry > .90f)
        assertTrue(a.legPhaseSpread > .10f)
    }

    @Test
    fun legPhasesFreezeWhenMotorOutputStops() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .42f }
        repeat(60) { a.step(motor, 0f, 0f, .02f) }
        val phaseBefore = a.phase.clone()
        repeat(50) { a.step(FloatArray(6), 0f, 0f, .02f) }
        for (i in phaseBefore.indices) {
            assertEquals(phaseBefore[i], a.phase[i], 1e-5f)
        }
        assertTrue(a.forwardVelocity < .05f)
        assertTrue(a.yawRate < .08f)
    }


    @Test
    fun unequalNeuralLegDrive_changesInterLegTiming() {
        val a = LeggedSensorimotorActuator()
        val before = a.phase.clone()
        val motor = FloatArray(6) { i -> if (i == 0) .12f else .55f }
        repeat(180) { a.step(motor, 0f, 0f, .02f) }
        val phaseDeltaBefore = (before[0] - before[1])
        val phaseDeltaAfter = (a.phase[0] - a.phase[1])
        assertTrue(abs(phaseDeltaAfter - phaseDeltaBefore) > .05f)
        assertTrue(a.legPhaseSpread > .05f)
    }

    @Test
    fun walkOff_reducesMechanicalDrive() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .55f }
        repeat(100) { a.step(motor, 0f, 0f, .02f) }
        val walking = a.forwardVelocity
        repeat(100) { a.step(motor, 1f, 0f, .02f) }
        assertTrue(a.forwardVelocity < walking)
        assertTrue(a.forwardVelocity < .03f)
    }

    @Test
    fun brkBrake_isIndependentFromWalkOffAndStopsMechanicalDrive() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .55f }
        repeat(100) { a.step(motor, 0f, 0f, .02f) }
        val walking = a.forwardVelocity
        repeat(100) { a.step(motor, 0f, 1f, .02f) }
        assertTrue(a.forwardVelocity < walking)
        assertTrue(a.forwardVelocity < .03f)
    }

    @Test
    fun sustainedBackgroundMotorInput_isNotAClockThatForcesWalking() {
        val a = LeggedSensorimotorActuator()
        val tonic = FloatArray(6) { .015f }
        repeat(600) { a.step(tonic, 0f, 0f, .02f) }
        assertTrue(a.forwardVelocity < 1e-5f)
        assertTrue(a.yawRate < 1e-5f)
    }

    @Test
    fun retainedTurnDnOutput_canReorientBodyWithoutLegPropulsion() {
        val a = LeggedSensorimotorActuator()
        repeat(80) {
            a.step(
                FloatArray(6),
                0f,
                0f,
                .02f,
                turnDnLeftActivation = 0f,
                turnDnRightActivation = .70f
            )
        }
        assertTrue(a.yawRate > .20f)
        assertTrue(abs(a.forwardVelocity) < 1e-5f)
    }

    @Test
    fun symmetricTurnDnOutput_hasNoIntrinsicYawBias() {
        val a = LeggedSensorimotorActuator()
        var yawSum = 0f
        var samples = 0
        repeat(180) { step ->
            val burst = if ((step / 5) % 2 == 0) .55f else 0f
            a.step(
                FloatArray(6),
                0f,
                0f,
                .02f,
                turnDnLeftActivation = burst,
                turnDnRightActivation = burst
            )
            if (step >= 30) {
                yawSum += a.yawRate
                samples++
            }
        }
        assertTrue(abs(yawSum / samples.toFloat()) < .01f)
    }

    @Test
    fun bilateralTurnDnSignals_competeBySideInsteadOfUsingAOneShotHeadingEdit() {
        val a = LeggedSensorimotorActuator()
        repeat(30) {
            a.step(FloatArray(6), 0f, 0f, .02f, turnDnLeftActivation = .60f, turnDnRightActivation = 0f)
        }
        val leftYaw = a.yawRate
        repeat(60) {
            a.step(FloatArray(6), 0f, 0f, .02f, turnDnLeftActivation = 0f, turnDnRightActivation = .60f)
        }
        assertTrue(leftYaw < -.15f)
        assertTrue(a.yawRate > .15f)
    }

    @Test
    fun wallContact_isCollisionAndSensoryFeedback_only() {
        val a = LeggedSensorimotorActuator()
        val motor = FloatArray(6) { .45f }
        repeat(120) { a.step(motor, 0f, 0f, .02f) }
        val yawBefore = a.yawRate
        val phaseBefore = a.phase.clone()
        val speedBefore = a.forwardVelocity

        // Left wall normal points into the arena; heading=0 means the fly is
        // moving into that wall. Contact must remove penetration and expose
        // pressure, but it must not synthesize a turn or phase kick.
        a.applyWallConstraint(0f, -1f, 0f, .02f, true)

        assertEquals(yawBefore, a.yawRate, 1e-6f)
        for (i in phaseBefore.indices) {
            assertEquals(phaseBefore[i], a.phase[i], 1e-6f)
        }
        assertTrue(a.wallPressure > .05f)
        assertTrue(a.forwardVelocity <= speedBefore + 1e-6f)
    }

}

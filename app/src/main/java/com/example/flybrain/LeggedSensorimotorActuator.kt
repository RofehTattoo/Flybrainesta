package com.example.flybrain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * V1.20.0 — phase-free neuromuscular embodiment.
 *
 * Inputs are per-frame population firing rates for the six anatomically annotated
 * leg groups (LF, LM, LH, RF, RM, RH), plus the measured Walk-OFF output.
 * There is deliberately no gait oscillator, tripod phase table, or generated
 * stance/swing clock. Motor activity drives persistent leg-joint state; foot
 * motion and its ground-contact proxy produce body forces. If the neural output
 * does not contain temporally varying motor drive, this actuator will not invent
 * a walking rhythm or spontaneous pauses.
 *
 * Important model boundary: current VNC semantics identify the leg and side, but
 * do not provide a validated one-to-one mapping from every MN to a specific
 * muscle/joint. This is therefore a transparent per-leg actuator, not a claim of
 * full muscle-level Drosophila biomechanics.
 */
class LeggedSensorimotorActuator {
    companion object {
        const val LEG_COUNT = 6
        private const val MOTOR_RATE_REFERENCE_HZ = 12f
        private const val MOTOR_ATTACK_TAU = .035f
        private const val MOTOR_RELEASE_TAU = .11f
        private const val JOINT_TAU = .045f
        private const val MAX_FORWARD_SPEED = .24f
        private const val MAX_LATERAL_SPEED = .025f
        private const val FORWARD_ACCEL = 1.25f
        private const val LATERAL_ACCEL = .10f
        private const val FORWARD_DAMPING = 5.4f
        private const val LATERAL_DAMPING = 6.5f
        private const val MAX_YAW_RATE = 1.35f
        private const val YAW_RESPONSE_TAU = .16f
        private const val MIN_DT = .0005f
        private const val FOOT_STROKE = .034f
        private const val WALL_TANGENTIAL_FRICTION = .94f
        private const val WALL_CONTACT_FLOOR = .18f
    }

    /** Mechanical states, one entry per leg; no hidden gait phase. */
    val contact = FloatArray(LEG_COUNT)
    val load = FloatArray(LEG_COUNT)
    val stance = FloatArray(LEG_COUNT)
    val swing = FloatArray(LEG_COUNT)
    val stride = FloatArray(LEG_COUNT)
    val lift = FloatArray(LEG_COUNT)
    val extension = FloatArray(LEG_COUNT)
    val footForward = FloatArray(LEG_COUNT)
    val footLateral = FloatArray(LEG_COUNT)
    val footLift = FloatArray(LEG_COUNT)
    val motorActivation = FloatArray(LEG_COUNT)
    val jointState = FloatArray(LEG_COUNT)
    val jointVelocityState = FloatArray(LEG_COUNT)

    var forwardVelocity = 0f; private set
    var lateralVelocity = 0f; private set
    var yawRate = 0f; private set
    var forwardAcceleration = 0f; private set
    var lateralAcceleration = 0f; private set
    var yawAcceleration = 0f; private set
    var proprioceptionLeft = 0f; private set
    var proprioceptionRight = 0f; private set
    var proprioceptionGlobal = 0f; private set
    var supportMean = 0f; private set
    var leftSupport = 0f; private set
    var rightSupport = 0f; private set
    var supportBalance = 0f; private set
    var mechanicalActivity = 0f; private set
    var wallPressure = 0f; private set

    private val previousStride = FloatArray(LEG_COUNT)
    private val baseForward = floatArrayOf(.050f, .000f, -.050f, .050f, .000f, -.050f)
    private val baseLateral = floatArrayOf(-.058f, -.065f, -.058f, .058f, .065f, .058f)

    fun reset() {
        contact.fill(0f); load.fill(0f); stance.fill(0f); swing.fill(0f)
        stride.fill(0f); lift.fill(0f); extension.fill(0f)
        footForward.fill(0f); footLateral.fill(0f); footLift.fill(0f)
        motorActivation.fill(0f); jointState.fill(0f); jointVelocityState.fill(0f); previousStride.fill(0f)
        forwardVelocity = 0f; lateralVelocity = 0f; yawRate = 0f
        forwardAcceleration = 0f; lateralAcceleration = 0f; yawAcceleration = 0f
        proprioceptionLeft = 0f; proprioceptionRight = 0f; proprioceptionGlobal = 0f
        supportMean = 0f; leftSupport = 0f; rightSupport = 0f; supportBalance = 0f
        mechanicalActivity = 0f; wallPressure = 0f
    }

    /**
     * Advance one mechanical frame. motorRateHz contains six measured firing
     * rates (Hz), not a desired gait frequency or a behavior/action command.
     */
    fun step(motorRateHz: FloatArray, walkOffActivation: Float, dtRaw: Float) {
        require(motorRateHz.size >= LEG_COUNT)
        val dt = dtRaw.coerceAtLeast(MIN_DT)
        val walkGate = (1f - .97f * walkOffActivation.coerceIn(0f, 1f)).coerceIn(0f, 1f)

        var leftLoad = 0f; var rightLoad = 0f
        var leftForce = 0f; var rightForce = 0f
        var totalLoad = 0f; var totalContact = 0f; var totalForce = 0f

        for (g in 0 until LEG_COUNT) {
            val target = (motorRateHz[g].coerceAtLeast(0f) / MOTOR_RATE_REFERENCE_HZ)
                .coerceIn(0f, 1f) * walkGate
            val tau = if (target > motorActivation[g]) MOTOR_ATTACK_TAU else MOTOR_RELEASE_TAU
            val alpha = (1f - exp((-dt / tau).toDouble()).toFloat()).coerceIn(0f, 1f)
            motorActivation[g] += (target - motorActivation[g]) * alpha

            // A generic leg-joint actuator follows the actual neural envelope.
            // It does not run a separate phase clock or impose tripod timing.
            val oldJoint = jointState[g]
            val jointAlpha = (1f - exp((-dt / JOINT_TAU).toDouble()).toFloat()).coerceIn(0f, 1f)
            jointState[g] += (motorActivation[g] - jointState[g]) * jointAlpha
            val jointVelocity = (jointState[g] - oldJoint) / dt
            jointVelocityState[g] = jointVelocity

            // Positive neural drive retracts the foot relative to the body. This
            // sign is an explicit actuator convention, not a muscle identity claim.
            stride[g] = -jointState[g]
            val strideRate = (stride[g] - previousStride[g]) / dt
            previousStride[g] = stride[g]
            extension[g] = jointState[g].coerceIn(0f, 1f)
            lift[g] = (jointState[g] * .22f).coerceIn(0f, 1f)
            footLift[g] = lift[g] * .020f

            // Simplified ground plane: the foot is loaded while it remains near
            // the ground. Contact/load are mechanical outputs, not neural inputs.
            contact[g] = (1f - lift[g]).coerceIn(0f, 1f)
            stance[g] = contact[g]
            swing[g] = (1f - contact[g]).coerceIn(0f, 1f)
            load[g] = (contact[g] * motorActivation[g]).coerceIn(0f, 1f)
            footForward[g] = baseForward[g] + stride[g] * FOOT_STROKE
            footLateral[g] = baseLateral[g] + (if (g < 3) -1f else 1f) * .007f * extension[g]

            // Only actual foot motion under ground contact can propel the body.
            val force = ((-strideRate).coerceAtLeast(0f) * contact[g] * .018f)
                .coerceIn(0f, .18f)
            totalForce += force
            totalLoad += load[g]
            totalContact += contact[g]
            if (g < 3) { leftLoad += load[g]; leftForce += force }
            else { rightLoad += load[g]; rightForce += force }

            if (!jointVelocity.isFinite()) {
                jointState[g] = oldJoint
                jointVelocityState[g] = 0f
            }
        }

        supportMean = (totalLoad / LEG_COUNT).coerceIn(0f, 1f)
        leftSupport = (leftLoad / 3f).coerceIn(0f, 1f)
        rightSupport = (rightLoad / 3f).coerceIn(0f, 1f)
        supportBalance = ((rightSupport - leftSupport) / (leftSupport + rightSupport + .001f)).coerceIn(-1f, 1f)

        forwardAcceleration = FORWARD_ACCEL * totalForce.coerceIn(0f, .66f) - FORWARD_DAMPING * forwardVelocity
        forwardVelocity = (forwardVelocity + forwardAcceleration * dt).coerceIn(0f, MAX_FORWARD_SPEED)
        lateralAcceleration = LATERAL_ACCEL * supportBalance - LATERAL_DAMPING * lateralVelocity
        lateralVelocity = (lateralVelocity + lateralAcceleration * dt).coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)

        val turnBalance = ((rightForce - leftForce) / (rightForce + leftForce + .0005f)).coerceIn(-1f, 1f)
        val yawTarget = turnBalance * MAX_YAW_RATE
        val yawAlpha = (1f - exp((-dt / YAW_RESPONSE_TAU).toDouble()).toFloat()).coerceIn(0f, 1f)
        val oldYaw = yawRate
        yawRate += (yawTarget - yawRate) * yawAlpha
        yawAcceleration = (yawRate - oldYaw) / dt

        proprioceptionLeft = mechanicalSide(0, 3, leftForce)
        proprioceptionRight = mechanicalSide(3, 6, rightForce)
        proprioceptionGlobal = ((proprioceptionLeft + proprioceptionRight) * .5f +
            abs(forwardAcceleration) * .07f + wallPressure * .25f).coerceIn(0f, 1f)
        mechanicalActivity = ((totalContact / LEG_COUNT) * .35f +
            (totalLoad / LEG_COUNT) * .25f + (forwardVelocity / MAX_FORWARD_SPEED) * .40f).coerceIn(0f, 1f)
        wallPressure = (wallPressure * exp((-dt / .14f).toDouble()).toFloat()).coerceIn(0f, 1f)
    }

    /** Wall normal points from the boundary into the arena, in world coordinates. */
    fun applyWallConstraint(heading: Float, normalX: Float, normalY: Float, dtRaw: Float) {
        val dt = dtRaw.coerceAtLeast(MIN_DT)
        val c = kotlin.math.cos(heading); val s = kotlin.math.sin(heading)
        var vx = c * forwardVelocity - s * lateralVelocity
        var vy = s * forwardVelocity + c * lateralVelocity
        val nLen = sqrt(normalX * normalX + normalY * normalY)
        if (nLen <= .00001f) return
        val nx = normalX / nLen; val ny = normalY / nLen
        val inward = vx * nx + vy * ny
        // A detected boundary contact is sensory even when speed has already
        // fallen to zero; pressure is not conditional on a new collision impulse.
        val pressure = (-inward / MAX_FORWARD_SPEED).coerceIn(0f, 1f)
        wallPressure = max(wallPressure, max(WALL_CONTACT_FLOOR, pressure))
        if (inward < 0f) {
            vx -= inward * nx
            vy -= inward * ny
            vx *= WALL_TANGENTIAL_FRICTION
            vy *= WALL_TANGENTIAL_FRICTION
        }
        forwardVelocity = (vx * c + vy * s).coerceIn(0f, MAX_FORWARD_SPEED)
        lateralVelocity = (-vx * s + vy * c).coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)
        // Do not synthesize a turn or reflect heading at contact.
        yawRate *= exp((-dt / .12f).toDouble()).toFloat()
    }

    private fun mechanicalSide(start: Int, end: Int, force: Float): Float {
        var sum = 0f
        for (i in start until end) {
            sum += contact[i] * .12f + load[i] * .48f +
                abs(stride[i]) * .12f + abs(jointState[i]) * .08f +
                (abs(jointVelocityState[i]) * .02f).coerceIn(0f, .20f)
        }
        return (sum / (end - start) + force * .10f).coerceIn(0f, 1f)
    }
}

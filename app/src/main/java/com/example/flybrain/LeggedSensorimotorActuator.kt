package com.example.flybrain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * V1.19 sensorimotor closed-loop mechanical decoder.
 *
 * This class contains no sensory stimulus logic, no food/light/danger state and
 * no action/goal selection. It receives only the six anatomically decoded LEG
 * motor-neuron activation streams plus the measured walk-OFF output. It turns
 * those neural outputs into a low-dimensional leg/body mechanical state and
 * exposes mechanical/proprioceptive evidence for the next neural frame.
 *
 * FBC103/FBD105 connectivity is therefore untouched. The fixed tripod phase
 * offsets are a mechanical kinematic constraint required because the connectome
 * does not contain a rendered muscle/joint/contact solver. Phase progression is
 * still motor-driven: with no retained leg motor output, the gait clock does not
 * advance.
 */
class LeggedSensorimotorActuator {
    companion object {
        const val LEG_COUNT = 6
        private const val TWO_PI = (PI * 2.0).toFloat()
        // LF + LH + RM versus LM + RF + RH.
        private val TRIPOD_OFFSETS = floatArrayOf(0f, .5f, 0f, .5f, 0f, .5f)

        private const val STANCE_DUTY = .62f
        private const val MIN_PHASE_HZ = .85f
        private const val MAX_PHASE_HZ = 6.0f
        private const val MOTOR_THRESHOLD = .015f

        // Normalised arena units per second / second. These are actuator-scale
        // parameters, not behavioural selectors.
        private const val MAX_FORWARD_SPEED = .28f
        private const val MAX_LATERAL_SPEED = .065f
        private const val FORWARD_ACCEL = 1.55f
        private const val LATERAL_ACCEL = .55f
        private const val FORWARD_DAMPING = 4.2f
        private const val LATERAL_DAMPING = 4.6f
        private const val MAX_YAW_RATE = 1.65f
        private const val YAW_RESPONSE_TAU = .105f
        private const val LINEAR_RESPONSE_MIN_DT = .0005f
    }

    /** Motor-derived state, one value per anatomical LEG subgroup. */
    val phase = FloatArray(LEG_COUNT)
    val contact = FloatArray(LEG_COUNT)
    val load = FloatArray(LEG_COUNT)
    val stance = FloatArray(LEG_COUNT)
    val swing = FloatArray(LEG_COUNT)
    /** Signed stride position in a normalised actuator coordinate, -1..+1. */
    val stride = FloatArray(LEG_COUNT)
    /** 0..1 visual/mechanical lift fraction during swing. */
    val lift = FloatArray(LEG_COUNT)
    /** 0..1 joint extension proxy derived from motor activation + phase. */
    val extension = FloatArray(LEG_COUNT)

    var forwardVelocity = 0f
        private set
    var lateralVelocity = 0f
        private set
    var yawRate = 0f
        private set

    var forwardAcceleration = 0f
        private set
    var lateralAcceleration = 0f
        private set
    var yawAcceleration = 0f
        private set

    /** Mechanical/proprioceptive evidence returned to MECH sensors next frame. */
    var proprioceptionLeft = 0f
        private set
    var proprioceptionRight = 0f
        private set
    var proprioceptionGlobal = 0f
        private set

    var supportMean = 0f
        private set
    var leftSupport = 0f
        private set
    var rightSupport = 0f
        private set
    var supportBalance = 0f
        private set
    var mechanicalActivity = 0f
        private set

    fun reset() {
        phase.fill(0f)
        contact.fill(0f)
        load.fill(0f)
        stance.fill(0f)
        swing.fill(0f)
        stride.fill(0f)
        lift.fill(0f)
        extension.fill(0f)
        forwardVelocity = 0f
        lateralVelocity = 0f
        yawRate = 0f
        forwardAcceleration = 0f
        lateralAcceleration = 0f
        yawAcceleration = 0f
        proprioceptionLeft = 0f
        proprioceptionRight = 0f
        proprioceptionGlobal = 0f
        supportMean = 0f
        leftSupport = 0f
        rightSupport = 0f
        supportBalance = 0f
        mechanicalActivity = 0f
    }

    /**
     * Consume the six measured leg-MN activations for one 20 ms public frame.
     * No environment or goal variable is accepted by this method.
     */
    fun step(legActivation: FloatArray, walkOffActivation: Float, dtRaw: Float) {
        require(legActivation.size >= LEG_COUNT) { "LEG actuator requires six motor groups" }
        val dt = dtRaw.coerceAtLeast(LINEAR_RESPONSE_MIN_DT)
        val walkGate = (1f - .94f * walkOffActivation.coerceIn(0f, 1f)).coerceIn(0f, 1f)

        var totalSupport = 0f
        var totalLoad = 0f
        var left = 0f
        var right = 0f
        var leftMotor = 0f
        var rightMotor = 0f

        for (g in 0 until LEG_COUNT) {
            val a = (legActivation[g].coerceIn(0f, 1f) * walkGate).coerceIn(0f, 1f)
            val phaseHz = if (a > MOTOR_THRESHOLD) {
                MIN_PHASE_HZ + (MAX_PHASE_HZ - MIN_PHASE_HZ) * sqrt(a)
            } else 0f
            phase[g] += TWO_PI * phaseHz * dt
            while (phase[g] >= TWO_PI) phase[g] -= TWO_PI
            while (phase[g] < 0f) phase[g] += TWO_PI

            var cycle = phase[g] / TWO_PI + TRIPOD_OFFSETS[g]
            cycle %= 1f
            if (cycle < 0f) cycle += 1f

            val stanceFraction = if (cycle < STANCE_DUTY) {
                // Smooth contact transfer rather than a hard on/off switch.
                val x = (cycle / STANCE_DUTY).coerceIn(0f, 1f)
                1f - (x * x * (3f - 2f * x))
            } else 0f
            val swingFraction = (1f - stanceFraction).coerceIn(0f, 1f)

            stance[g] = stanceFraction
            swing[g] = swingFraction
            contact[g] = if (a <= MOTOR_THRESHOLD) .04f else {
                (0.12f * stanceFraction + 0.88f * a * stanceFraction).coerceIn(0f, 1f)
            }
            load[g] = (a * stanceFraction).coerceIn(0f, 1f)
            extension[g] = (0.30f + 0.70f * a * (0.55f + 0.45f * stanceFraction)).coerceIn(0f, 1f)
            stride[g] = if (cycle < STANCE_DUTY) {
                1f - 2f * (cycle / STANCE_DUTY)
            } else {
                -1f + 2f * ((cycle - STANCE_DUTY) / (1f - STANCE_DUTY))
            }.coerceIn(-1f, 1f)
            lift[g] = if (a <= MOTOR_THRESHOLD) 0f else {
                (sinPi(swingFraction) * swingFraction * a).coerceIn(0f, 1f)
            }

            totalSupport += load[g]
            totalLoad += contact[g]
            if (g < 3) {
                left += load[g]
                leftMotor += a
            } else {
                right += load[g]
                rightMotor += a
            }
        }

        supportMean = (totalSupport / LEG_COUNT.toFloat()).coerceIn(0f, 1f)
        leftSupport = (left / 3f).coerceIn(0f, 1f)
        rightSupport = (right / 3f).coerceIn(0f, 1f)
        supportBalance = ((rightSupport - leftSupport) / (leftSupport + rightSupport + .001f)).coerceIn(-1f, 1f)
        val motorBalance = ((rightMotor - leftMotor) / ((rightMotor + leftMotor) * .5f + .001f)).coerceIn(-1f, 1f)

        // Propulsive force arises from the mean supported leg load; inertia and
        // damping provide continuity across public neural frames.
        val propulsive = (supportMean * 1.75f).coerceIn(0f, 1f)
        forwardAcceleration = FORWARD_ACCEL * propulsive - FORWARD_DAMPING * forwardVelocity
        forwardVelocity = (forwardVelocity + forwardAcceleration * dt).coerceIn(0f, MAX_FORWARD_SPEED)

        // Lateral translation follows persistent left/right motor asymmetry,
        // not the alternating tripod support pattern. This prevents the normal
        // gait itself from generating a false steering oscillation.
        lateralAcceleration = LATERAL_ACCEL * motorBalance - LATERAL_DAMPING * lateralVelocity
        lateralVelocity = (lateralVelocity + lateralAcceleration * dt)
            .coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)

        // Yaw is a torque-like consequence of persistent left/right LEG motor
        // imbalance. Normal tripod alternation therefore does not create a false
        // turn command; the sign remains the same causal side convention used by
        // the previous release.
        val yawTarget = (motorBalance * MAX_YAW_RATE).coerceIn(-MAX_YAW_RATE, MAX_YAW_RATE)
        val yawAlpha = (1f - kotlin.math.exp((-dt / YAW_RESPONSE_TAU).toDouble()).toFloat()).coerceIn(0f, 1f)
        val previousYaw = yawRate
        yawRate += (yawTarget - yawRate) * yawAlpha
        yawAcceleration = (yawRate - previousYaw) / dt

        // Mechanical state is sampled back into the retained MECH pathway on the
        // following neural frame. This introduces a real closed loop without
        // creating any new neuron, edge or behavioural shortcut.
        val leftMechanical = bilateralMechanicalMean(0, 3)
        val rightMechanical = bilateralMechanicalMean(3, 6)
        proprioceptionLeft = (leftMechanical + abs(lateralAcceleration) * .08f + abs(yawRate) * .06f)
            .coerceIn(0f, 1f)
        proprioceptionRight = (rightMechanical + abs(lateralAcceleration) * .08f + abs(yawRate) * .06f)
            .coerceIn(0f, 1f)
        proprioceptionGlobal = ((proprioceptionLeft + proprioceptionRight) * .5f + abs(forwardAcceleration) * .05f)
            .coerceIn(0f, 1f)
        mechanicalActivity = ((totalLoad / LEG_COUNT.toFloat()) * .70f +
            (abs(forwardVelocity) / MAX_FORWARD_SPEED) * .30f).coerceIn(0f, 1f)
    }

    private fun bilateralMechanicalMean(start: Int, end: Int): Float {
        var sum = 0f
        for (i in start until end) {
            sum += contact[i] * .15f + load[i] * .70f + swing[i] * .15f
        }
        return (sum / (end - start).toFloat()).coerceIn(0f, 1f)
    }

    private fun sinPi(x: Float): Float {
        val y = x.coerceIn(0f, 1f)
        return kotlin.math.sin(PI.toFloat() * y).coerceIn(0f, 1f)
    }
}

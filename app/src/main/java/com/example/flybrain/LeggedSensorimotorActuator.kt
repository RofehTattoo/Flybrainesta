package com.example.flybrain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * V1.19.7 embodied sensorimotor actuator.
 *
 * The neural substrate remains upstream and immutable. This class is the
 * mechanical interface: six decoded LEG motor streams drive six independent
 * leg phases, stance/swing and foot trajectories; the resulting stance motion
 * produces body force and yaw. No food/light/danger/action variable is accepted.
 *
 * Tonic baseline motor firing is not propulsion. Only phasic excess above the
 * per-leg measured baseline advances the gait strongly enough to generate force.
 * This prevents a stable ~5 Hz background from becoming endless walking.
 */
class LeggedSensorimotorActuator {
    companion object {
        const val LEG_COUNT = 6
        private const val TWO_PI = (PI * 2.0).toFloat()
        // Modified tripod: RF→LM→RH and LF→RM→LH, with ~0.5-cycle opposition.
        // Group order: LF, LM, LH, RF, RM, RH.
        private val TRIPOD_OFFSETS = floatArrayOf(.50f, .08f, .66f, 0f, .58f, .16f)
        private const val STANCE_DUTY = .62f
        private const val MIN_PHASE_HZ = 1.20f
        private const val MAX_PHASE_HZ = 16.0f
        private const val MOTOR_THRESHOLD = .035f
        private const val MAX_FORWARD_SPEED = 5.00f
        private const val MAX_LATERAL_SPEED = .025f
        private const val FORWARD_ACCEL = 45.0f
        private const val LATERAL_ACCEL = .10f
        private const val FORWARD_DAMPING = 6.0f
        private const val WALK_OFF_BRAKE_ACCEL = 7.0f
        private const val LATERAL_DAMPING = 6.5f
        private const val MAX_YAW_RATE = 1.35f
        private const val WALL_ESCAPE_MAX_YAW_RATE = 3.10f
        private const val WALL_ESCAPE_RESPONSE_TAU = .085f
        private const val WALL_ESCAPE_DECAY_TAU = .34f
        private const val YAW_RESPONSE_TAU = .16f
        private const val LINEAR_RESPONSE_MIN_DT = .0005f
        private const val FOOT_STROKE = .065f
        private const val WALL_TANGENTIAL_FRICTION = .94f
        private const val WALL_ESCAPE_MIN_PRESSURE = .055f
    }

    val phase = FloatArray(LEG_COUNT)
    val contact = FloatArray(LEG_COUNT)
    val load = FloatArray(LEG_COUNT)
    val stance = FloatArray(LEG_COUNT)
    val swing = FloatArray(LEG_COUNT)
    val stride = FloatArray(LEG_COUNT)
    val lift = FloatArray(LEG_COUNT)
    val extension = FloatArray(LEG_COUNT)

    /** Body-frame foot positions in normalized arena coordinates. */
    val footForward = FloatArray(LEG_COUNT)
    val footLateral = FloatArray(LEG_COUNT)
    val footLift = FloatArray(LEG_COUNT)

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
    var wallPressure = 0f
        private set

    /** Signed low-level reorientation drive produced by physical wall contact.
     *  +1 turns left, -1 turns right. It is deterministic and decays; no RNG.
     */
    var wallEscapeBias = 0f
        private set
    private var wallEscapeDirection = 1f
    private var wallContactLatched = false
    private var lastWallNx = 0f
    private var lastWallNy = 0f

    private val previousStride = FloatArray(LEG_COUNT)
    private var initializedStride = false

    // Anatomy in body coordinates: positive forward, negative/positive lateral.
    private val baseForward = floatArrayOf(.050f, .000f, -.050f, .050f, .000f, -.050f)
    private val baseLateral = floatArrayOf(-.058f, -.065f, -.058f, .058f, .065f, .058f)

    fun reset() {
        phase.fill(0f); contact.fill(0f); load.fill(0f); stance.fill(0f); swing.fill(0f)
        stride.fill(0f); lift.fill(0f); extension.fill(0f)
        footForward.fill(0f); footLateral.fill(0f); footLift.fill(0f)
        previousStride.fill(0f)
        initializedStride = false
        forwardVelocity = 0f; lateralVelocity = 0f; yawRate = 0f
        forwardAcceleration = 0f; lateralAcceleration = 0f; yawAcceleration = 0f
        proprioceptionLeft = 0f; proprioceptionRight = 0f; proprioceptionGlobal = 0f
        supportMean = 0f; leftSupport = 0f; rightSupport = 0f; supportBalance = 0f
        mechanicalActivity = 0f; wallPressure = 0f
        wallEscapeBias = 0f
        wallEscapeDirection = 1f
        wallContactLatched = false
        lastWallNx = 0f
        lastWallNy = 0f
    }

    /**
     * Advance one 20 ms public mechanical frame.
     * legActivation is already derived from measured neural spike output.
     */
    fun step(legActivation: FloatArray, walkOffActivation: Float, dtRaw: Float) {
        require(legActivation.size >= LEG_COUNT)
        val dt = dtRaw.coerceAtLeast(LINEAR_RESPONSE_MIN_DT)
        val walkGate = (1f - .97f * walkOffActivation.coerceIn(0f, 1f)).coerceIn(0f, 1f)

        var leftLoad = 0f
        var rightLoad = 0f
        var leftPropulsion = 0f
        var rightPropulsion = 0f
        var totalLoad = 0f
        var totalContact = 0f
        var totalPropulsion = 0f

        for (g in 0 until LEG_COUNT) {
            val a = (legActivation[g].coerceIn(0f, 1f) * walkGate).coerceIn(0f, 1f)
            val phaseHz = if (a > MOTOR_THRESHOLD) {
                MIN_PHASE_HZ + (MAX_PHASE_HZ - MIN_PHASE_HZ) * sqrt(a)
            } else 0f
            phase[g] += TWO_PI * phaseHz * dt
            while (phase[g] >= TWO_PI) phase[g] -= TWO_PI
            while (phase[g] < 0f) phase[g] += TWO_PI

            var cycle = (phase[g] / TWO_PI + TRIPOD_OFFSETS[g]) % 1f
            if (cycle < 0f) cycle += 1f
            val s = cycle < STANCE_DUTY
            val u = if (s) (cycle / STANCE_DUTY).coerceIn(0f, 1f) else
                ((cycle - STANCE_DUTY) / (1f - STANCE_DUTY)).coerceIn(0f, 1f)
            val smooth = u * u * (3f - 2f * u)
            val stanceFraction = if (s) 1f - smooth else 0f
            val swingFraction = if (s) 0f else smooth

            stance[g] = stanceFraction
            swing[g] = swingFraction
            contact[g] = if (a <= MOTOR_THRESHOLD) 0f else
                (a * stanceFraction).coerceIn(0f, 1f)
            load[g] = (a * stanceFraction).coerceIn(0f, 1f)
            extension[g] = (0.18f + .82f * a).coerceIn(0f, 1f)

            stride[g] = if (s) {
                1f - 2f * (cycle / STANCE_DUTY)
            } else {
                -1f + 2f * u
            }.coerceIn(-1f, 1f)
            lift[g] = if (a <= MOTOR_THRESHOLD) 0f else
                (kneeSin(swingFraction) * a).coerceIn(0f, 1f)

            // Body-frame foot geometry is driven by the same gait state shown on screen.
            footForward[g] = baseForward[g] + stride[g] * FOOT_STROKE
            footLateral[g] = baseLateral[g] + (if (g < 3) -1f else 1f) * .007f * extension[g]
            footLift[g] = lift[g] * .020f

            val strideRate = if (initializedStride) (stride[g] - previousStride[g]) / dt else 0f
            previousStride[g] = stride[g]
            val propulsive = if (s && a > MOTOR_THRESHOLD) {
                (-strideRate * contact[g]).coerceAtLeast(0f)
            } else 0f
            // Normalize the foot stroke velocity so it becomes a bounded force proxy.
            val force = (propulsive * .055f * (0.55f + .45f * a)).coerceIn(0f, .22f)
            totalPropulsion += force
            totalLoad += load[g]
            totalContact += contact[g]
            if (g < 3) {
                leftLoad += load[g]; leftPropulsion += force
            } else {
                rightLoad += load[g]; rightPropulsion += force
            }
        }
        initializedStride = true

        supportMean = (totalLoad / LEG_COUNT).coerceIn(0f, 1f)
        leftSupport = (leftLoad / 3f).coerceIn(0f, 1f)
        rightSupport = (rightLoad / 3f).coerceIn(0f, 1f)
        supportBalance = ((rightSupport - leftSupport) / (leftSupport + rightSupport + .001f)).coerceIn(-1f, 1f)

        // Each stance foot contributes to the body's net propulsive force.
        // Summing then saturating preserves the contribution of multiple legs;
        // averaging by LEG_COUNT incorrectly diluted the total force sixfold.
        // Propulsion still comes only from backward foot motion during stance.
        val propulsive = totalPropulsion.coerceIn(0f, .66f)
        val walkOff = walkOffActivation.coerceIn(0f, 1f)
        forwardAcceleration = FORWARD_ACCEL * propulsive -
            FORWARD_DAMPING * forwardVelocity -
            WALK_OFF_BRAKE_ACCEL * walkOff
        forwardVelocity = (forwardVelocity + forwardAcceleration * dt).coerceIn(0f, MAX_FORWARD_SPEED)

        lateralAcceleration = LATERAL_ACCEL * supportBalance - LATERAL_DAMPING * lateralVelocity
        lateralVelocity = (lateralVelocity + lateralAcceleration * dt)
            .coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)

        val turnBalance = ((rightPropulsion - leftPropulsion) /
            (rightPropulsion + leftPropulsion + .0005f)).coerceIn(-1f, 1f)
        val neuralYawTarget = turnBalance * MAX_YAW_RATE
        val wallYawTarget = wallEscapeBias * WALL_ESCAPE_MAX_YAW_RATE
        val yawTarget = (neuralYawTarget + wallYawTarget)
            .coerceIn(-WALL_ESCAPE_MAX_YAW_RATE, WALL_ESCAPE_MAX_YAW_RATE)
        val yawTau = if (abs(wallEscapeBias) > .06f) WALL_ESCAPE_RESPONSE_TAU else YAW_RESPONSE_TAU
        val yawAlpha = (1f - exp((-dt / yawTau).toDouble()).toFloat()).coerceIn(0f, 1f)
        val oldYaw = yawRate
        yawRate += (yawTarget - yawRate) * yawAlpha
        yawAcceleration = (yawRate - oldYaw) / dt

        val leftMechanical = bilateralMechanicalMean(0, 3)
        val rightMechanical = bilateralMechanicalMean(3, 6)
        proprioceptionLeft = (leftMechanical + abs(leftPropulsion) * .10f).coerceIn(0f, 1f)
        proprioceptionRight = (rightMechanical + abs(rightPropulsion) * .10f).coerceIn(0f, 1f)
        proprioceptionGlobal = ((proprioceptionLeft + proprioceptionRight) * .5f +
            abs(forwardAcceleration) * .07f + wallPressure * .25f).coerceIn(0f, 1f)
        mechanicalActivity = ((totalContact / LEG_COUNT) * .60f +
            (forwardVelocity / MAX_FORWARD_SPEED) * .40f).coerceIn(0f, 1f)
        wallPressure = (wallPressure * exp((-dt / .14f).toDouble()).toFloat()).coerceIn(0f, 1f)
        if (!wallContactLatched) {
            wallEscapeBias = (wallEscapeBias *
                exp((-dt / WALL_ESCAPE_DECAY_TAU).toDouble()).toFloat())
                .coerceIn(-1f, 1f)
        }
    }

    /**
     * Physical wall response. This is a local mechanosensory reflex, not an
     * external "go around the wall" command:
     *
     * 1) remove the velocity component directed into the wall;
     * 2) detect whether the body is actually facing the wall;
     * 3) on contact onset choose one escape side from geometry / bilateral leg
     *    imbalance and latch it for that contact episode;
     * 4) immediately drive the existing actuator yaw state toward that escape
     *    side, with a short time constant;
     * 5) let the drive decay once contact ends.
     *
     * Crucially, the escape drive is NOT normalized by current forward speed.
     * A fly that has already slowed to nearly zero at the wall must still turn.
     */
    fun applyWallConstraint(
        heading: Float,
        normalX: Float,
        normalY: Float,
        dtRaw: Float,
        contactActive: Boolean
    ) {
        val dt = dtRaw.coerceAtLeast(LINEAR_RESPONSE_MIN_DT)

        if (!contactActive) {
            wallContactLatched = false
            return
        }

        val nLen = sqrt(normalX * normalX + normalY * normalY)
        if (nLen <= .00001f) return

        val nx = normalX / nLen
        val ny = normalY / nLen
        lastWallNx = nx
        lastWallNy = ny

        val c = kotlin.math.cos(heading)
        val s = kotlin.math.sin(heading)
        var vx = c * forwardVelocity - s * lateralVelocity
        var vy = s * forwardVelocity + c * lateralVelocity

        val outward = vx * nx + vy * ny
        val forwardIntoWall = (-outward / MAX_FORWARD_SPEED).coerceIn(0f, 1f)

        // Geometry-based "facing the wall" signal. 1 = face-on, 0 = parallel/away.
        val headingDotNormal = (c * nx + s * ny)
        val wallFacing = (-headingDotNormal).coerceIn(0f, 1f)

        if (outward < 0f) {
            // Remove only the penetrating component and preserve tangential slip.
            vx -= outward * nx
            vy -= outward * ny
            wallPressure = max(wallPressure, max(forwardIntoWall, .10f * wallFacing))
            vx *= WALL_TANGENTIAL_FRICTION
            vy *= WALL_TANGENTIAL_FRICTION
        } else {
            // A fly can remain at the boundary after penetration velocity was
            // already removed. Keep the reflex alive from geometry/contact.
            wallPressure = max(wallPressure, .08f * wallFacing)
        }

        if (!wallContactLatched) {
            // Choose one direction once per contact episode.
            val rightX = -s
            val rightY = c
            val wallRightProjection = nx * rightX + ny * rightY

            val sideByLegs = ((rightSupport - leftSupport) /
                (leftSupport + rightSupport + .001f)).coerceIn(-1f, 1f)

            val escapeSide = when {
                abs(wallRightProjection) > .45f ->
                    -wallRightProjection.signOrZero()
                abs(sideByLegs) > .10f ->
                    -sideByLegs.signOrZero()
                else ->
                    if (wallEscapeDirection == 0f) 1f else wallEscapeDirection
            }

            wallEscapeDirection = if (escapeSide == 0f) 1f else escapeSide
            wallContactLatched = true
        }

        // Contact onset gets a strong enough impulse even when translational speed
        // has already collapsed to ~0 at the wall. Face-on impact receives more drive.
        val contactGain = (0.72f + 0.28f * wallFacing).coerceIn(.72f, 1f)
        val episodeDrive = wallEscapeDirection * contactGain
        wallEscapeBias = if (wallContactLatched) {
            episodeDrive
        } else {
            0f
        }

        // Immediate actuator response removes the one-frame delay between detecting
        // the wall and applying the turn. This still filters the yaw through a
        // first-order time constant; MainActivity continues to integrate heading.
        val wallYawTarget = wallEscapeBias * WALL_ESCAPE_MAX_YAW_RATE
        val yawAlpha = (1f - exp(
            (-dt / WALL_ESCAPE_RESPONSE_TAU).toDouble()
        ).toFloat()).coerceIn(0f, 1f)
        yawRate += (wallYawTarget - yawRate) * yawAlpha
        yawRate = yawRate.coerceIn(-WALL_ESCAPE_MAX_YAW_RATE, WALL_ESCAPE_MAX_YAW_RATE)

        // Head-on contact briefly unloads propulsion, but never freezes the body.
        if (wallFacing > .55f) {
            val unload = (.22f + .24f * wallFacing).coerceIn(.22f, .46f)
            forwardVelocity *= (1f - unload)
        }

        forwardVelocity = (vx * c + vy * s).coerceAtLeast(0f)
            .coerceIn(0f, MAX_FORWARD_SPEED)
        lateralVelocity = (-vx * s + vy * c)
            .coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)

        // Small bilateral gait phase offset makes the escape visible in the same
        // six-leg mechanics instead of looking like a direct heading command.
        if (wallFacing > .35f) {
            val phaseKick = (0.028f * wallEscapeDirection * wallFacing)
                .coerceIn(-.028f, .028f)
            for (g in 0 until LEG_COUNT) {
                phase[g] = (phase[g] +
                    if (g < 3) -phaseKick else phaseKick).mod(TWO_PI)
            }
        }
    }

    private fun Float.signOrZero(): Float = when {
        this > 0f -> 1f
        this < 0f -> -1f
        else -> 0f
    }

    private fun bilateralMechanicalMean(start: Int, end: Int): Float {
        var sum = 0f
        for (i in start until end) {
            sum += contact[i] * .20f + load[i] * .65f + swing[i] * .15f
        }
        return (sum / (end - start)).coerceIn(0f, 1f)
    }

    private fun kneeSin(x: Float): Float {
        val y = x.coerceIn(0f, 1f)
        return kotlin.math.sin(PI.toFloat() * y).coerceIn(0f, 1f)
    }
}

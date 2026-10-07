package com.example.flybrain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * V1.19.31 six-leg ground-reaction sensorimotor actuator.
 *
 * The neural substrate remains upstream and immutable. This class is the
 * mechanical interface: six decoded LEG motor streams drive six independent
 * leg phases, stance/swing and foot trajectories; the resulting stance motion
 * produces body force and yaw. No food/light/danger/action variable is accepted.
 *
 * Each stance foot generates a bounded ground reaction from its measured neural
 * leg activation and foot velocity relative to the ground. This couples force,
 * lateral slip and yaw through the actual six foot lever arms instead of using
 * a scalar turn or lateral-motion command.
 */
class LeggedSensorimotorActuator {
    companion object {
        const val LEG_COUNT = 6
        private const val TWO_PI = (PI * 2.0).toFloat()
        // Group order: LF, LM, LH, RF, RM, RH.
        // These are startup phases only. They are NOT re-applied as a permanent
        // tripod constraint: each leg subsequently advances from its own measured
        // neural motor drive. This lets neural activity and proprioceptive state
        // change the inter-leg timing instead of forcing a perpetual tripod.
        private val INITIAL_PHASES = floatArrayOf(.50f, .08f, .66f, 0f, .58f, .16f)
        private const val STANCE_DUTY = .62f
        private const val MIN_PHASE_HZ = 1.20f
        private const val MAX_PHASE_HZ = 16.0f
        private const val MOTOR_THRESHOLD = .035f
        private const val MAX_FORWARD_SPEED = 5.00f
        private const val MAX_BACKWARD_SPEED = .80f
        private const val MAX_LATERAL_SPEED = .020f
        private const val FORWARD_SLIP_SCALE = .55f
        private const val LATERAL_SLIP_SCALE = .12f
        private const val YAW_TORQUE_NORMALIZER = .0143f
        private const val MAX_FOOT_FORCE = .22f
        private const val BODY_FORCE_TO_ACCEL = 45.0f
        private const val LATERAL_FORCE_TO_ACCEL = 28.0f
        private const val YAW_TORQUE_TO_ACCEL = 180.0f
        private const val YAW_DAMPING = 4.0f
        private const val FORWARD_DAMPING = 6.0f
        private const val WALK_OFF_BRAKE_ACCEL = 7.0f
        private const val LATERAL_DAMPING = 6.5f
        // A left/right ratio is meaningful only when there is enough measured
        // stance propulsion. Without this gate, tiny residual forces normalize
        // to a full turn command and the body spins while translationally stopped.
        private const val LINEAR_RESPONSE_MIN_DT = .0005f
        private const val FOOT_STROKE = .065f
        private const val WALL_TANGENTIAL_FRICTION = .94f
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

    /** Dimensionless forward ground-force proxy produced by stance legs. */
    var forwardForceProxy = 0f
        private set
    /** Signed dimensionless left/right force imbalance used by the physical yaw model. */
    var yawForceProxy = 0f
        private set
    /** Signed body-yaw torque proxy from the six individual stance-foot force vectors. */
    var yawTorqueProxy = 0f
        private set
    /** Fraction of the six legs carrying measurable stance contact. */
    var supportCoverage = 0f
        private set
    /** Signed yaw torque proxy created by local wall/foot contact reaction mechanics. */
    var wallReactionTorqueProxy = 0f
        private set
    /** 0..1 bilateral mechanical symmetry; 1 means equal left/right support. */
    var bilateralMechanicalSymmetry = 0f
        private set
    private var bilateralSymmetryFiltered = 0f
    private var leftPropulsionFiltered = 0f
    private var rightPropulsionFiltered = 0f
    /** 0..1 spread of the six independent leg phases; diagnostic only. */
    var legPhaseSpread = 0f
        private set
    var wallPressure = 0f
        private set

    private val previousStride = FloatArray(LEG_COUNT)
    private var initializedStride = false

    /**
     * The actuator must start in the same anatomically defined startup state
     * that reset() establishes. Android constructs this object before the first
     * simulation frame; without explicit initialization, phase[] remained all
     * zero and the six legs began in artificial synchrony, bypassing the intended
     * INITIAL_PHASES. This is a state-initialization bug, not a behavioral shortcut.
     */
    init {
        reset()
    }

    // Anatomy in body coordinates: positive forward, negative/positive lateral.
    private val baseForward = floatArrayOf(.050f, .000f, -.050f, .050f, .000f, -.050f)
    private val baseLateral = floatArrayOf(-.058f, -.065f, -.058f, .058f, .065f, .058f)

    fun reset() {
        for (g in 0 until LEG_COUNT) phase[g] = INITIAL_PHASES[g] * TWO_PI
        contact.fill(0f); load.fill(0f); stance.fill(0f); swing.fill(0f)
        stride.fill(0f); lift.fill(0f); extension.fill(0f)
        footForward.fill(0f); footLateral.fill(0f); footLift.fill(0f)
        previousStride.fill(0f)
        initializedStride = false
        forwardVelocity = 0f; lateralVelocity = 0f; yawRate = 0f
        forwardAcceleration = 0f; lateralAcceleration = 0f; yawAcceleration = 0f
        proprioceptionLeft = 0f; proprioceptionRight = 0f; proprioceptionGlobal = 0f
        supportMean = 0f; leftSupport = 0f; rightSupport = 0f; supportBalance = 0f
        mechanicalActivity = 0f
        forwardForceProxy = 0f
        yawForceProxy = 0f
        yawTorqueProxy = 0f
        wallReactionTorqueProxy = 0f
        supportCoverage = 0f
        bilateralMechanicalSymmetry = 0f
        bilateralSymmetryFiltered = 0f
        leftPropulsionFiltered = 0f
        rightPropulsionFiltered = 0f
        legPhaseSpread = 0f
        wallPressure = 0f
    }

    /**
     * Advance one 20 ms public mechanical frame.
     * legActivation is already derived from measured neural spike output.
     */
    fun step(legActivation: FloatArray, walkOffActivation: Float, dtRaw: Float) {
        require(legActivation.size >= LEG_COUNT)
        val dt = dtRaw.coerceAtLeast(LINEAR_RESPONSE_MIN_DT)
        wallReactionTorqueProxy = 0f
        // A near-complete neural halt must actually stop the gait oscillator.
        // Squaring the residual gate preserves graded partial walk-off, while
        // preventing a small residual leg drive from sustaining a slow crawl
        // during a measured feeding/halting episode.
        val walkGateBase = (1f - .97f * walkOffActivation.coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
        val walkGate = walkGateBase * walkGateBase

        var leftLoad = 0f
        var rightLoad = 0f
        var totalLoad = 0f
        var totalContact = 0f
        var totalForwardForce = 0f
        var totalLateralForce = 0f
        var yawTorque = 0f

        for (g in 0 until LEG_COUNT) {
            val a = (legActivation[g].coerceIn(0f, 1f) * walkGate).coerceIn(0f, 1f)
            val phaseHz = if (a > MOTOR_THRESHOLD) {
                MIN_PHASE_HZ + (MAX_PHASE_HZ - MIN_PHASE_HZ) * sqrt(a)
            } else 0f
            phase[g] += TWO_PI * phaseHz * dt
            while (phase[g] >= TWO_PI) phase[g] -= TWO_PI
            while (phase[g] < 0f) phase[g] += TWO_PI

            // The phase is now the leg's own state. There is no per-frame
            // tripod offset imposed here. Neural motor output determines the
            // phase progression of each individual leg.
            var cycle = (phase[g] / TWO_PI) % 1f
            if (cycle < 0f) cycle += 1f
            val s = cycle < STANCE_DUTY
            val u = if (s) (cycle / STANCE_DUTY).coerceIn(0f, 1f) else
                ((cycle - STANCE_DUTY) / (1f - STANCE_DUTY)).coerceIn(0f, 1f)
            val smooth = u * u * (3f - 2f * u)
            val stanceFraction = if (s) 1f - smooth else 0f
            val swingFraction = if (s) smooth else 0f

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

            footForward[g] = baseForward[g] + stride[g] * FOOT_STROKE
            footLateral[g] = baseLateral[g] + (if (g < 3) -1f else 1f) * .007f * extension[g]
            footLift[g] = lift[g] * .020f

            val strideRate = if (initializedStride) (stride[g] - previousStride[g]) / dt else 0f
            previousStride[g] = stride[g]

            // During stance, the foot is treated as a temporary ground contact.
            // The reaction force opposes the foot's velocity relative to the ground.
            // This couples translational slip and yaw through the actual six-foot
            // lever arms instead of injecting a scalar left/right steering command.
            if (s && contact[g] > 0f) {
                val footVelocityForward = forwardVelocity - yawRate * footLateral[g]
                val footVelocityLateral = lateralVelocity + yawRate * footForward[g]
                val footStrokeVelocity = strideRate * FOOT_STROKE
                val slipForward = footVelocityForward + footStrokeVelocity
                val slipLateral = footVelocityLateral

                val forceScale = MAX_FOOT_FORCE * contact[g]
                val forceForward = (-forceScale * tanh((slipForward / FORWARD_SLIP_SCALE).toDouble()).toFloat())
                    .coerceIn(-MAX_FOOT_FORCE, MAX_FOOT_FORCE)
                val forceLateral = (-forceScale * tanh((slipLateral / LATERAL_SLIP_SCALE).toDouble()).toFloat())
                    .coerceIn(-MAX_FOOT_FORCE, MAX_FOOT_FORCE)

                totalForwardForce += forceForward
                totalLateralForce += forceLateral
                // Exact planar moment r × F. The body turns only when the measured
                // six-foot ground reactions are mechanically asymmetric.
                yawTorque += footForward[g] * forceLateral - footLateral[g] * forceForward
            }

            totalLoad += load[g]
            totalContact += contact[g]
            if (g < 3) leftLoad += load[g] else rightLoad += load[g]
        }
        initializedStride = true

        supportMean = (totalLoad / LEG_COUNT).coerceIn(0f, 1f)
        leftSupport = (leftLoad / 3f).coerceIn(0f, 1f)
        rightSupport = (rightLoad / 3f).coerceIn(0f, 1f)
        supportBalance = ((rightSupport - leftSupport) / (leftSupport + rightSupport + .001f)).coerceIn(-1f, 1f)
        supportCoverage = (totalContact / LEG_COUNT.toFloat()).coerceIn(0f, 1f)

        val symmetryAlpha = (1f - exp((-dt / .20f).toDouble()).toFloat()).coerceIn(0f, 1f)
        val leftContactForce = totalSideLoad(0, 3)
        val rightContactForce = totalSideLoad(3, 6)
        leftPropulsionFiltered += (leftContactForce - leftPropulsionFiltered) * symmetryAlpha
        rightPropulsionFiltered += (rightContactForce - rightPropulsionFiltered) * symmetryAlpha
        bilateralSymmetryFiltered = (1f - abs(leftPropulsionFiltered - rightPropulsionFiltered) /
            (leftPropulsionFiltered + rightPropulsionFiltered + .001f)).coerceIn(0f, 1f)
        bilateralMechanicalSymmetry = bilateralSymmetryFiltered

        var phaseMeanX = 0f
        var phaseMeanY = 0f
        for (g in 0 until LEG_COUNT) {
            phaseMeanX += kotlin.math.cos(phase[g].toDouble()).toFloat()
            phaseMeanY += kotlin.math.sin(phase[g].toDouble()).toFloat()
        }
        val phaseConcentration = hypot(phaseMeanX, phaseMeanY) / LEG_COUNT.toFloat()
        legPhaseSpread = (1f - phaseConcentration).coerceIn(0f, 1f)

        val netForwardForce = totalForwardForce.coerceIn(-.66f, .66f)
        val netLateralForce = totalLateralForce.coerceIn(-.66f, .66f)
        forwardForceProxy = netForwardForce.coerceIn(0f, 1f)

        val walkOff = walkOffActivation.coerceIn(0f, 1f)
        forwardAcceleration = BODY_FORCE_TO_ACCEL * netForwardForce -
            FORWARD_DAMPING * forwardVelocity - WALK_OFF_BRAKE_ACCEL * walkOff
        lateralAcceleration = LATERAL_FORCE_TO_ACCEL * netLateralForce -
            LATERAL_DAMPING * lateralVelocity

        forwardVelocity = (forwardVelocity + forwardAcceleration * dt)
            .coerceIn(-MAX_BACKWARD_SPEED, MAX_FORWARD_SPEED)
        lateralVelocity = (lateralVelocity + lateralAcceleration * dt)
            .coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)

        val torqueNorm = (yawTorque / YAW_TORQUE_NORMALIZER).coerceIn(-1f, 1f)
        yawTorqueProxy = torqueNorm
        // Rotational dynamics are integrated from the measured six-foot moment.
        // There is no target angle, bilateral scalar fallback, or wall-escape turn.
        yawForceProxy = torqueNorm
        yawAcceleration = YAW_TORQUE_TO_ACCEL * yawTorque - YAW_DAMPING * yawRate
        val oldYaw = yawRate
        yawRate = (yawRate + yawAcceleration * dt).coerceIn(-1.80f, 1.80f)
        yawAcceleration = (yawRate - oldYaw) / dt

        val leftMechanical = bilateralMechanicalMean(0, 3)
        val rightMechanical = bilateralMechanicalMean(3, 6)
        proprioceptionLeft = (leftMechanical + abs(leftContactForce) * .10f).coerceIn(0f, 1f)
        proprioceptionRight = (rightMechanical + abs(rightContactForce) * .10f).coerceIn(0f, 1f)
        proprioceptionGlobal = ((proprioceptionLeft + proprioceptionRight) * .5f +
            abs(forwardAcceleration) * .07f + wallPressure * .25f).coerceIn(0f, 1f)
        mechanicalActivity = ((totalContact / LEG_COUNT) * .60f +
            (abs(forwardVelocity) / MAX_FORWARD_SPEED) * .40f).coerceIn(0f, 1f)
        wallPressure = (wallPressure * exp((-dt / .14f).toDouble()).toFloat()).coerceIn(0f, 1f)
    }

    private fun totalSideLoad(start: Int, end: Int): Float {
        var sum = 0f
        for (i in start until end) sum += contact[i]
        return sum.coerceIn(0f, 1.5f)
    }

    /**
     * Apply a normalized world-frame reaction from a real physical wall contact.
     * The caller supplies only contact mechanics (force and moment), never a goal
     * or behavioural action. This lets a stance foot push the body away from a
     * wall and lets the resulting moment reorient the body without a wall-escape
     * controller.
     */
    fun applyWallReactionWorld(
        heading: Float,
        forceWorldX: Float,
        forceWorldY: Float,
        torqueWorld: Float,
        dtRaw: Float
    ) {
        val dt = dtRaw.coerceAtLeast(LINEAR_RESPONSE_MIN_DT)
        val c = kotlin.math.cos(heading)
        val s = kotlin.math.sin(heading)
        val forceForward = c * forceWorldX + s * forceWorldY
        val forceLateral = -s * forceWorldX + c * forceWorldY

        forwardVelocity = (forwardVelocity + BODY_FORCE_TO_ACCEL * forceForward * dt)
            .coerceIn(0f, MAX_FORWARD_SPEED)
        lateralVelocity = (lateralVelocity + LATERAL_FORCE_TO_ACCEL * forceLateral * dt)
            .coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)
        yawRate = (yawRate + YAW_TORQUE_TO_ACCEL * torqueWorld * dt)
            .coerceIn(-1.80f, 1.80f)
        wallPressure = max(wallPressure, hypot(forceWorldX, forceWorldY).coerceIn(0f, 1f))
        wallReactionTorqueProxy = (torqueWorld / YAW_TORQUE_NORMALIZER).coerceIn(-1f, 1f)
    }

    /**
     * Resolve physical contact with the arena boundary.
     *
     * This function is deliberately NOT a locomotor controller. It performs only
     * collision mechanics: remove velocity directed into the wall, preserve a
     * bounded tangential slip, and expose wall pressure through the existing
     * mechanosensory/proprioceptive feedback channel. It never writes yaw, gait
     * phase, leg activation, or body position. Any turn/escape response must
     * therefore come from the retained neural circuit on a subsequent simulation
     * step.
     */
    fun applyWallConstraint(
        heading: Float,
        normalX: Float,
        normalY: Float,
        dtRaw: Float,
        contactActive: Boolean
    ) {
        val dt = dtRaw.coerceAtLeast(LINEAR_RESPONSE_MIN_DT)
        if (!contactActive) return

        val nLen = sqrt(normalX * normalX + normalY * normalY)
        if (nLen <= .00001f) return
        val nx = normalX / nLen
        val ny = normalY / nLen
        val c = kotlin.math.cos(heading)
        val s = kotlin.math.sin(heading)
        var vx = c * forwardVelocity - s * lateralVelocity
        var vy = s * forwardVelocity + c * lateralVelocity

        val outward = vx * nx + vy * ny
        val forwardIntoWall = (-outward / MAX_FORWARD_SPEED).coerceIn(0f, 1f)
        val wallFacing = (-c * nx - s * ny).coerceIn(0f, 1f)

        // Pure contact mechanics: remove only penetration and retain tangential
        // motion. No heading reflection and no synthetic escape turn are applied.
        if (outward < 0f) {
            vx -= outward * nx
            vy -= outward * ny
            vx *= WALL_TANGENTIAL_FRICTION
            vy *= WALL_TANGENTIAL_FRICTION
        }

        wallPressure = max(
            wallPressure * exp((-dt / .14f).toDouble()).toFloat(),
            max(forwardIntoWall, .10f * wallFacing)
        ).coerceIn(0f, 1f)

        forwardVelocity = (vx * c + vy * s)
            .coerceIn(-MAX_BACKWARD_SPEED, MAX_FORWARD_SPEED)
        lateralVelocity = (-vx * s + vy * c)
            .coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)
    }

    /**
     * Record a mechanically derived wall-reaction torque. This is a physical
     * contact result from the already simulated six stance feet; it is not a
     * behavioral command and never changes leg phase or neural activation.
     */
    fun recordWallReactionTorque(normalizedTorque: Float) {
        wallReactionTorqueProxy = normalizedTorque.coerceIn(-1f, 1f)
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

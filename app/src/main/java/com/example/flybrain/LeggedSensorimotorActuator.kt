package com.example.flybrain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * V1.19.36 adaptive six-leg sensorimotor actuator.
 *
 * The neural substrate remains upstream and immutable. This class is the
 * mechanical interface: six decoded LEG motor streams drive six independent
 * leg phases, stance/swing and foot trajectories; retained bilateral TURN
 * descending-neuron output supplies an additional neural yaw signal; stance
 * mechanics supplies the remaining body force/torque. No food/light/danger/action
 * variable is accepted.
 *
 * Tonic baseline motor firing is not, by itself, a behavioral command. The
 * actuator also receives the separately measured neural walk-OFF and BRK brake
 * populations. Walk-OFF suppresses the gait drive; BRK adds an independent
 * VNC-level brake so a sustained motor output cannot behave like a wind-up toy.
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
        private const val MIN_PHASE_HZ = 0.80f
        private const val MAX_PHASE_HZ = 14.0f
        private const val MOTOR_THRESHOLD = .035f
        private const val MAX_FORWARD_SPEED = 5.00f
        private const val MAX_LATERAL_SPEED = .025f
        private const val FORWARD_ACCEL = 45.0f
        private const val LATERAL_ACCEL = .10f
        private const val FORWARD_DAMPING = 6.0f
        private const val WALK_OFF_BRAKE_ACCEL = 7.0f
        private const val BRK_BRAKE_ACCEL = 18.0f
        private const val LATERAL_DAMPING = 6.5f
        private const val MAX_YAW_RATE = 1.35f
        // A left/right ratio is meaningful only when there is enough measured
        // stance propulsion. Without this gate, tiny residual forces normalize
        // to a full turn command and the body spins while translationally stopped.
        private const val TURN_PROPULSION_DEADZONE = .018f
        private const val TURN_PROPULSION_FULL_SCALE = .105f
        private const val WALKOFF_YAW_SUPPRESSION = .92f
        private const val PAUSE_YAW_CUTOFF = .20f
        private const val YAW_STOP_RESPONSE_TAU = .075f
        private const val YAW_RESPONSE_TAU = .16f
        // Role-2 retained descending TURN population: calibrated per-neuron rate
        // reference. The FBR-10 loader verifies the bilateral 10/10 census.
        private const val TURN_DN_ACTIVATION_DEADZONE = .025f
        private const val TURN_DN_MAX_CONTRIBUTION = .70f
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
    private var yawTorqueFiltered = 0f
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
        yawTorqueFiltered = 0f
        legPhaseSpread = 0f
        wallPressure = 0f
    }

    /**
     * Advance one 20 ms public mechanical frame.
     * legActivation and bilateral turn-DN activations are already derived from
     * measured neural spike output.
     */
    /** Backward-compatible four-channel step for legacy callers/tests. */
    fun step(legActivation: FloatArray, walkOffActivation: Float, brakeActivation: Float, dtRaw: Float) {
        stepInternal(legActivation, walkOffActivation, brakeActivation, dtRaw, 0f, 0f)
    }

    /**
     * Six-channel body step: measured LEG motor output plus measured bilateral
     * descending TURN output. The latter is neural yaw evidence, not a synthetic
     * action command.
     */
    fun step(legActivation: FloatArray, walkOffActivation: Float, brakeActivation: Float, dtRaw: Float, turnDnLeftActivation: Float, turnDnRightActivation: Float) {
        stepInternal(legActivation, walkOffActivation, brakeActivation, dtRaw, turnDnLeftActivation, turnDnRightActivation)
    }

    private fun stepInternal(
        legActivation: FloatArray,
        walkOffActivation: Float,
        brakeActivation: Float,
        dtRaw: Float,
        turnDnLeftActivation: Float,
        turnDnRightActivation: Float
    ) {
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
        // BRK is a distinct VNC brake mechanism. It is not merged into the
        // walk-OFF signal: the two populations have different biological
        // mechanisms and therefore remain independently measurable.
        val brake = brakeActivation.coerceIn(0f, 1f)
        val brakeGate = (1f - .98f * brake).coerceIn(0f, 1f)
        val gaitGate = walkGate * brakeGate

        var leftLoad = 0f
        var rightLoad = 0f
        var leftPropulsion = 0f
        var rightPropulsion = 0f
        var totalLoad = 0f
        var totalContact = 0f
        var totalPropulsion = 0f
        var yawTorque = 0f

        for (g in 0 until LEG_COUNT) {
            val a = (legActivation[g].coerceIn(0f, 1f) * gaitGate).coerceIn(0f, 1f)
            // Phase is a locomotor state variable, not a clock. It advances only
            // while the upstream neural decoder supplies phasic leg-MN drive. A
            // tonic/background activation below threshold must not wind the legs
            // forever. This is the mechanical correction for the V1.19.36
            // “toy-car” failure mode; the actuator still receives neural output
            // exclusively and contains no stimulus/action controller.
            val phaseHz = if (a > MOTOR_THRESHOLD) {
                MIN_PHASE_HZ + (MAX_PHASE_HZ - MIN_PHASE_HZ) * sqrt(a)
            } else 0f
            phase[g] += TWO_PI * phaseHz * dt
            while (phase[g] >= TWO_PI) phase[g] -= TWO_PI
            while (phase[g] < 0f) phase[g] += TWO_PI

            // The phase is now the leg's own state. There is no per-frame
            // tripod offset imposed here. Different neural drives can therefore
            // advance the six legs at different rates and let their coordination
            // drift from the startup condition.
            var cycle = (phase[g] / TWO_PI) % 1f
            if (cycle < 0f) cycle += 1f
            val s = cycle < STANCE_DUTY
            val u = if (s) (cycle / STANCE_DUTY).coerceIn(0f, 1f) else
                ((cycle - STANCE_DUTY) / (1f - STANCE_DUTY)).coerceIn(0f, 1f)
            val smooth = u * u * (3f - 2f * u)
            val stanceFraction = if (s) 1f - smooth else 0f
            val swingFraction = if (s) 0f else smooth

            stance[g] = stanceFraction
            swing[g] = swingFraction
            // Contact is geometric support, not a proxy for neural firing.
            // A leg that has entered stance remains a physical support contact
            // even while its neural drive is momentarily silent. Propulsive load
            // remains neural-drive dependent below.
            contact[g] = stanceFraction.coerceIn(0f, 1f)
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
            // Use the actual lateral location of each stance foot to form a
            // signed torque proxy. This is still downstream mechanics: no wall,
            // food or action variable enters. A side imbalance is therefore
            // weighted by where the force is applied, rather than collapsing the
            // six legs immediately into one left/right scalar.
            if (s && force > 0f) {
                // Exact planar cross-product for a forward stance force. Keeping the
                // full lever-arm expression makes the mechanics explicit: the six
                // individual foot positions, not a direct left/right command, create
                // the yaw moment.
                yawTorque += footLateral[g] * force
            }
            totalLoad += load[g]
            totalContact += contact[g]
            if (g < 3) {
                leftLoad += load[g]; leftPropulsion += force
            } else {
                rightLoad += load[g]; rightPropulsion += force
            }
        }
        initializedStride = true

        // Support describes physical stance/contact. Neural load is kept separate
        // because it represents how strongly the motor system is driving that contact.
        supportMean = (totalContact / LEG_COUNT).coerceIn(0f, 1f)
        leftSupport = (leftLoad / 3f).coerceIn(0f, 1f)
        rightSupport = (rightLoad / 3f).coerceIn(0f, 1f)
        supportBalance = ((rightSupport - leftSupport) / (leftSupport + rightSupport + .001f)).coerceIn(-1f, 1f)
        supportCoverage = (totalContact / LEG_COUNT.toFloat()).coerceIn(0f, 1f)
        // Instantaneous tripod support is intentionally asymmetric (two legs on
        // one side can be in stance while one is on the other). For diagnostics,
        // bilateral symmetry therefore compares left/right propulsive impulse
        // accumulated over time rather than treating each frame as a standing
        // posture. This metric never feeds back into dynamics.
        val symmetryAlpha = (1f - exp((-dt / .20f).toDouble()).toFloat()).coerceIn(0f, 1f)
        leftPropulsionFiltered += (leftPropulsion - leftPropulsionFiltered) * symmetryAlpha
        rightPropulsionFiltered += (rightPropulsion - rightPropulsionFiltered) * symmetryAlpha
        bilateralSymmetryFiltered = (1f - abs(leftPropulsionFiltered - rightPropulsionFiltered) /
            (leftPropulsionFiltered + rightPropulsionFiltered + .001f)).coerceIn(0f, 1f)
        bilateralMechanicalSymmetry = bilateralSymmetryFiltered

        // Inter-leg timing is intentionally no longer forced back toward a
        // modified-tripod reference. The six phases are independent state variables;
        // the only coordination available to them is the neural drive and the
        // proprioceptive feedback returned through MainActivity.

        var phaseMeanX = 0f
        var phaseMeanY = 0f
        for (g in 0 until LEG_COUNT) {
            phaseMeanX += kotlin.math.cos(phase[g].toDouble()).toFloat()
            phaseMeanY += kotlin.math.sin(phase[g].toDouble()).toFloat()
        }
        val phaseConcentration = hypot(phaseMeanX, phaseMeanY) / LEG_COUNT.toFloat()
        legPhaseSpread = (1f - phaseConcentration).coerceIn(0f, 1f)

        // Each stance foot contributes to the body's net propulsive force.
        // Summing then saturating preserves the contribution of multiple legs;
        // averaging by LEG_COUNT incorrectly diluted the total force sixfold.
        // Propulsion still comes only from backward foot motion during stance.
        val propulsive = totalPropulsion.coerceIn(0f, .66f)
        forwardForceProxy = propulsive
        val walkOff = walkOffActivation.coerceIn(0f, 1f)
        forwardAcceleration = FORWARD_ACCEL * propulsive -
            FORWARD_DAMPING * forwardVelocity -
            WALK_OFF_BRAKE_ACCEL * walkOff -
            BRK_BRAKE_ACCEL * brake
        forwardVelocity = (forwardVelocity + forwardAcceleration * dt).coerceIn(0f, MAX_FORWARD_SPEED)

        val totalTurnPropulsion = (rightPropulsion + leftPropulsion).coerceAtLeast(0f)
        val turnBalance = ((rightPropulsion - leftPropulsion) /
            (totalTurnPropulsion + .0005f)).coerceIn(-1f, 1f)
        // Use the bilateral net stance force to form the body's steering moment.
        // The six individual foot forces still determine propulsion and contact,
        // but a perfectly symmetric gait must have zero mean yaw regardless of its
        // alternating tripod phase. This pairwise moment therefore preserves real
        // left/right motor asymmetry without turning normal gait phasing into a
        // permanent heading bias.
        val effectiveLever = .065f
        val bilateralYawTorque = (rightPropulsion - leftPropulsion) * effectiveLever
        yawTorque = bilateralYawTorque
        val torqueScale = (effectiveLever * .22f).coerceAtLeast(.0001f)
        val normalizedYawTorque = (yawTorque / torqueScale).coerceIn(-1f, 1f)
        yawTorqueProxy = normalizedYawTorque
        // The instantaneous tripod force moment is intentionally alternating even
        // during perfectly symmetric walking. Filter it over the same short window
        // used for bilateral propulsion so the actuator does not convert ordinary
        // gait phasing into a persistent steering bias. A sustained neural asymmetry
        // still survives this filter and produces real yaw.
        val yawFilterAlpha = (1f - exp((-dt / .12f).toDouble()).toFloat()).coerceIn(0f, 1f)
        yawTorqueFiltered += (normalizedYawTorque - yawTorqueFiltered) * yawFilterAlpha
        // Turn authority exists only when real stance propulsion exists. This
        // preserves a genuine asymmetric gait response while preventing residual
        // one-sided activity from spinning a stationary body.
        val turnDrive = ((totalTurnPropulsion - TURN_PROPULSION_DEADZONE) /
            (TURN_PROPULSION_FULL_SCALE - TURN_PROPULSION_DEADZONE))
            .coerceIn(0f, 1f)
        yawForceProxy = (turnBalance * turnDrive).coerceIn(-1f, 1f)
        // A load imbalance can bias lateral motion only while the stance gait is
        // actually producing propulsive force. Static asymmetry alone cannot move
        // the body sideways.
        lateralAcceleration = LATERAL_ACCEL * supportBalance * turnDrive -
            LATERAL_DAMPING * lateralVelocity
        lateralVelocity = (lateralVelocity + lateralAcceleration * dt)
            .coerceIn(-MAX_LATERAL_SPEED, MAX_LATERAL_SPEED)

        val pauseYawGate = if (walkOff >= PAUSE_YAW_CUTOFF) 0f else
            (1f - WALKOFF_YAW_SUPPRESSION * walkOff).coerceIn(0f, 1f)
        // Two causally distinct neural-to-body routes now coexist:
        //   (1) six LEG-MN outputs -> stance-force asymmetry -> mechanical yaw, and
        //   (2) retained bilateral role-2 descending TURN output -> neural yaw torque.
        // The second path is the missing closure in V1.19.36: turn DNs previously
        // existed only as an action/diagnostic readout, so a stationary body at a wall
        // had no direct neural degree of freedom with which to reorient.
        //
        // Do not gate either route by forward translation. A fly legitimately may
        // turn in place, including after a wall collision removes its forward speed.
        val legSteering = ((if (abs(yawTorqueFiltered) > .015f) yawTorqueFiltered else turnBalance) * turnDrive)
            .coerceIn(-1f, 1f)
        val dnLeft = turnDnLeftActivation.coerceIn(0f, 1f)
        val dnRight = turnDnRightActivation.coerceIn(0f, 1f)
        val dnTotal = (dnLeft + dnRight).coerceAtLeast(0f)
        val dnBalance = if (dnTotal <= .0001f) 0f else
            ((dnRight - dnLeft) / dnTotal).coerceIn(-1f, 1f)
        val dnDrive = ((dnTotal - TURN_DN_ACTIVATION_DEADZONE) /
            (1f - TURN_DN_ACTIVATION_DEADZONE)).coerceIn(0f, 1f)
        val dnSteering = dnBalance * dnDrive * TURN_DN_MAX_CONTRIBUTION
        // Bilateral agreement between the two neural routes adds authority without
        // allowing either route to exceed the same bounded yaw command. Opposing
        // signals cancel, which is the desired interpretation of competing steering
        // drives rather than an arbitrary winner-takes-all selector.
        val steeringSignal = (legSteering + dnSteering).coerceIn(-1f, 1f)
        val neuralYawTarget = steeringSignal * MAX_YAW_RATE * pauseYawGate
        val yawTarget = neuralYawTarget.coerceIn(-MAX_YAW_RATE, MAX_YAW_RATE)
        val yawTau = if (walkOff >= PAUSE_YAW_CUTOFF || (turnDrive < .04f && dnDrive < .04f)) {
            YAW_STOP_RESPONSE_TAU
        } else {
            YAW_RESPONSE_TAU
        }
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

        forwardVelocity = (vx * c + vy * s).coerceAtLeast(0f)
            .coerceIn(0f, MAX_FORWARD_SPEED)
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

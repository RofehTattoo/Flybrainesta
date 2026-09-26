package com.example.flybrain

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.sin
import java.util.Random
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class MainActivity : Activity() {
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(11, 16, 20)
        window.navigationBarColor = Color.rgb(11, 16, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 249))
            clipToPadding = true
        }

        // UI-only identity header. It does not participate in the simulation.
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 6.dp(), 12.dp(), 6.dp())
            setBackgroundColor(Color.rgb(11, 18, 22))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.flybrain_official)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        identity.addView(icon, LinearLayout.LayoutParams(48.dp(), 48.dp()))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 0, 0, 0)
        }
        val wordmark = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val flyText = TextView(this).apply {
            text = "FLY"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(245, 247, 248))
        }
        val brainText = TextView(this).apply {
            text = "BRAIN"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(105, 188, 241))
        }
        wordmark.addView(flyText)
        wordmark.addView(brainText)
        brand.addView(wordmark)
        brand.addView(TextView(this).apply {
            text = "EXPLORE A TINY MIND"
            textSize = 7.5f
            letterSpacing = .28f
            setTextColor(Color.rgb(190, 198, 202))
        })
        identity.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        identity.addView(TextView(this).apply {
            text = "MaleCNS v1.0"
            textSize = 8.5f
            setTextColor(Color.rgb(139, 151, 158))
        })
        root.addView(identity, LinearLayout.LayoutParams(-1, 62.dp()))

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars())
            } else null
            val top = bars?.top ?: insets.systemWindowInsetTop
            val bottom = bars?.bottom ?: insets.systemWindowInsetBottom
            view.setPadding(0, top, 0, bottom)
            insets
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(7.dp(), 5.dp(), 7.dp(), 5.dp())
            setBackgroundColor(Color.rgb(15, 23, 28))
        }

        fun makeButton(label: String) = Button(this).apply {
            text = label
            textSize = 12.5f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setTextColor(Color.rgb(236, 241, 244))
            setPadding(1.dp(), 0, 1.dp(), 0)
            stateListAnimator = null
        }

        val food = makeButton("◉  COMIDA")
        val light = makeButton("☼  LUZ")
        val danger = makeButton("△  PELIGRO")
        val reset = makeButton("↻  RESET")
        val bh = 54.dp()
        listOf(food, light, danger, reset).forEach { button ->
            controls.addView(
                button,
                LinearLayout.LayoutParams(0, bh, 1f).apply {
                    setMargins(3.dp(), 0, 3.dp(), 0)
                }
            )
        }
        root.addView(controls, LinearLayout.LayoutParams(-1, 64.dp()))

        val sim = FlyView()
        root.addView(sim, LinearLayout.LayoutParams(-1, 0, 1f))

        fun refresh() {
            sim.updateButtons()
        }

        food.setOnClickListener { sim.selectAndToggle(0); refresh() }
        light.setOnClickListener { sim.selectAndToggle(1); refresh() }
        danger.setOnClickListener { sim.selectAndToggle(2); refresh() }
        reset.setOnClickListener { sim.resetSimulation(); refresh() }

        sim.foodButton = food
        sim.lightButton = light
        sim.dangerButton = danger
        sim.resetButton = reset

        setContentView(root)
        root.requestApplyInsets()
        refresh()
    }

    inner class FlyView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rng = Random(9301)
        // Zero-mean, temporally correlated exploratory yaw perturbation. This is
        // an actuator-level stochastic term, not a directional preference.
        private val locomotionRng = Random()
        private var exploratoryTurn = 0f

        // V1.04: exactly 16,669 simulated neurons. The graph is generated at
        // build time from the public MaleCNS v1.0 tables: neurons are sampled
        // within the published superclasses and retained edges are real
        // body-to-body connections from the source connectome.
        private val N = GeneratedConnectomeMeta.NEURONS

        // V1.18: sensory interfaces are encoded as Poisson spike rates; the neural
        // substrate itself uses the reference-style LIF/alpha-synapse dynamics.
        private val SENSORY_VIS_MAX_HZ = 120f
        // V1.18.3: food/olfaction uses an official-annotation-derived per-ORN map.
        // The gain is an environmental sensor calibration parameter only; it never
        // writes motor state or a turn command.
        private val FOOD_OLF_MAX_HZ = 160f
        // Calibrated to the normalized scene: odor presence decays over a
        // local neighborhood instead of saturating almost the entire arena.
        private val FOOD_OLF_SIGMA = OlfactorySensorModel.ODOR_SIGMA
        private val EXPECTED_RETAINED_OLFACTORY_ORNS = GeneratedConnectomeMeta.RETAINED_OLFACTORY_ORNS
        private val EXPECTED_RETAINED_OLFACTORY_ORN_TYPE_ENTRY_NERVE_PAIRS = GeneratedConnectomeMeta.RETAINED_OLFACTORY_ORN_TYPE_ENTRY_NERVE_PAIRS
        private val SENSORY_GUST_GAIN = 0.85f

        // Reference-style neural dynamics (Shiu et al., Nature 2024):
        // v_rest = v_reset = -52 mV, threshold = -45 mV, tau_m = 20 ms,
        // tau_syn = 5 ms, refractory = 2.2 ms, delay = 1.8 ms. Android uses
        // a 0.5 ms fixed step, rounding the 1.8 ms delay to 2.0 ms.
        private val NEURAL_FRAME_DT_SECONDS = 0.020f
        private val NEURAL_SUBSTEP_DT_SECONDS = 0.0005f
        private val NEURAL_SUBSTEPS_PER_FRAME = 40
        private val REFRACTORY_SECONDS = 0.0022f
        private val SYNAPTIC_DELAY_SECONDS = 0.0018f
        private val SYNAPTIC_DELAY_STEPS = 4
        private val TAU_MEMBRANE_SECONDS = 0.020f
        private val TAU_SYNAPSE_SECONDS = 0.005f
        private val V_REST = -52.0f
        private val V_THRESHOLD = -45.0f
        private val V_RESET = -52.0f

        // Motor-actuator dynamics: muscle force is driven only by measured VNC
        // motor-neuron spike output, but it is not a boolean per 20 ms frame.
        // A short first-order activation state avoids frame-quantized limb force.
        // This is an actuator filter, not a sensory or action shortcut.
        private val MOTOR_ACTIVATION_TAU_SECONDS = 0.040f

        private val VIS_START = GeneratedConnectomeMeta.VIS_START
        private val VIS_END = GeneratedConnectomeMeta.VIS_END
        private val OLF_START = GeneratedConnectomeMeta.OLF_START
        private val OLF_END = GeneratedConnectomeMeta.OLF_END
        private val GUST_START = GeneratedConnectomeMeta.GUST_START
        private val GUST_END = GeneratedConnectomeMeta.GUST_END
        private val MECH_START = GeneratedConnectomeMeta.MECH_START
        private val MECH_END = GeneratedConnectomeMeta.MECH_END
        private val SENSOR_END = maxOf(VIS_END, OLF_END, GUST_END, MECH_END)
        private val OTHER_START = GeneratedConnectomeMeta.OTHER_START
        private val OTHER_END = GeneratedConnectomeMeta.OTHER_END
        private val DESC_START = GeneratedConnectomeMeta.DESC_START
        private val DESC_END = GeneratedConnectomeMeta.DESC_END
        private val ASC_START = GeneratedConnectomeMeta.ASC_START
        private val ASC_END = GeneratedConnectomeMeta.ASC_END
        private val MOTOR_START = GeneratedConnectomeMeta.VMOTOR_START
        private val MOTOR_END = GeneratedConnectomeMeta.VMOTOR_END

        private val v = FloatArray(N) { V_REST }
        // g is the aggregate synaptic state in mV. Unlike the previous
        // per-tick voltage kick, it has its own 5 ms decay and is driven by
        // delayed presynaptic spike events.
        private val synConductance = FloatArray(N)
        private val fired = BooleanArray(N)
        private val externalForced = BooleanArray(N)
        private val externalRateHz = FloatArray(N)
        private val rngState = LongArray(N) { i ->
            val z = -7046029254386353131L xor (i.toLong() * 2862933555777941757L)
            if (z == 0L) 1L else z
        }
        private val motorRole = ByteArray(N)
        // V1.15.2 anatomical VNC semantics are loaded from the official-annotation-derived asset.
        // The FBC103 role byte remains frozen and is retained only for provenance/audit.
        private val motorFunctionalTag = ByteArray(N)
        private val descendingRole = ByteArray(N)
        // Real MaleCNS body IDs for the selected neurons. Presentation/diagnostic only.
        private val bodyId = LongArray(N)
        // Official-MaleCNS-derived sensory receptor populations. These indices are
        // loaded from build-generated maps and are the only neurons allowed to receive
        // external sensory current in Phase 1. No synthetic neurons or edges are added.
        private var visualReceptorIndices = IntArray(0)
        private var olfactoryNeuronIndices = IntArray(0)
        private var gustatoryReceptorIndices = IntArray(0)
        private var mechanosensoryReceptorIndices = IntArray(0)
        // Official side evidence: -1=L, +1=R, 0=bilateral/unknown.
        // This is sensory-input metadata only, never a behavioral command.
        private val olfactorySide = ByteArray(N)
        private val topDnIds = IntArray(6) { -1 }
        private val topDnHz = FloatArray(6)
        private val topDnDelta = FloatArray(6)
        private val topDnVm = FloatArray(6)
        private val topDnWindowSpikes = IntArray(6)
        private val topMotorIds = IntArray(6) { -1 }
        private val topMotorHz = FloatArray(6)
        private val topMotorDelta = FloatArray(6)
        private val topMotorVm = FloatArray(6)
        private val topMotorWindowSpikes = IntArray(6)
        private var dnSpikingCount = 0
        private var motorSpikingCount = 0
        private var diagnosticRefreshClock = 0f
        private val diagWindowSeconds = 0.50f
        private var diagWindowElapsed = 0f
        private val dnWindowSpikes = IntArray(DESC_END - DESC_START)
        private val motorWindowSpikes = IntArray(MOTOR_END - MOTOR_START)
        // Side-resolved spike telemetry over the same diagnostic window. These
        // counters are read-only and never enter neural or body dynamics.
        private var olfWindowSpikesLeft = 0
        private var olfWindowSpikesRight = 0
        private var olfWindowSpikesUnknown = 0
        private var olfLeftHz = 0f
        private var olfRightHz = 0f
        private var dnLeftHz = 0f
        private var dnRightHz = 0f
        private var legLeftHz = 0f
        private var legRightHz = 0f
        private val dnBaselineSpikes = IntArray(DESC_END - DESC_START)
        private val motorBaselineSpikes = IntArray(MOTOR_END - MOTOR_START)

        // Frozen FBR-10 VNC motor-role census. These denominators come only from
        // the role metadata stored in FBC103 and are never inferred from firing.
        private val motorRoleTotals = IntArray(7)
        private var legLeftTotal = 0
        private var legRightTotal = 0
        private var legUnknownSideTotal = 0
        private var wingLeftTotal = 0
        private var wingRightTotal = 0
        private var jumpLeftTotal = 0
        private var jumpRightTotal = 0
        private var haltereActiveCache = 0
        private var abdomenActiveCache = 0
        private var unresolvedMotorActiveCache = 0
        private var legActiveCache = 0
        private var leftLegActiveCache = 0
        private var rightLegActiveCache = 0
        private var unknownLegActiveCache = 0
        private var wingActiveCache = 0
        private var jumpActiveCache = 0
        private var motorOtherActiveCache = 0
        private var baselineCaptureSeconds = 0f
        private var baselineReady = false
        // V1.14.3: presentation-only 5 s diagnostic history and video-legible observability. Never feeds back into dynamics.
        private val historyNeural = FloatArray(50)
        private val historyDn = FloatArray(50)
        private val historyMotor = FloatArray(50)
        private var historyCursor = 0
        private var historyClock = 0f
        // V1.13: experimentally identified halt populations retained from the published MaleCNS annotations.
        // 1=FG walk-OFF, 2=BB walk-OFF, 3=BRK VNC brake.
        private val haltRole = ByteArray(N)
        private val nodeSide = ByteArray(N)
        // Connectome-derived two-hop route metadata: descriptive weights for
        // forward, turning and escape-related paths. These never create edges.
        private val routeForward = FloatArray(N)
        private val routeTurn = FloatArray(N)
        private val routeEscape = FloatArray(N)
        private val refractory = FloatArray(N)
        // V1.15.5 diagnostic: read-only net synaptic drive immediately before LIF thresholding.
        // This is telemetry only and never feeds back into the dynamics.
        private val lastSynDrive = FloatArray(N)

        // PHASE 2B: body/diagnostic output must consume the complete 20 ms frame,
        // including motor spikes that occurred before the final internal substep.
        private val motorSubstepSpikeCounts = IntArray(N - MOTOR_START)
        private val motorSynDriveSum = FloatArray(N - MOTOR_START)
        private val motorSynDrivePeak = FloatArray(N - MOTOR_START)

        // V1.07: presentation-only activity persistence. It smooths individual
        // spikes into a short visual intensity trail so activation/deactivation
        // can be read without changing the neural state or connectivity.
        private val visualActivity = FloatArray(N)
        // PHASE 2B correction: presentation memory remains frame-level (20 ms),
        // while a latch preserves any spike that occurred during the four internal
        // 5 ms substeps. This keeps the legacy display semantics unchanged.
        private val frameFired = BooleanArray(N)
        // FBR-10 dynamics layer: +1 excitatory, -1 inhibitory, 0 unknown/modulatory.
        private val neuroSign = ByteArray(N)

        private val incoming = Array(N) { IntArray(0) }
        private val outgoing = Array(N) { IntArray(0) }
        private val outgoingW = Array(N) { FloatArray(0) }
        private val pendingSpikeSources = Array(SYNAPTIC_DELAY_STEPS + 1) { IntArray(N) }
        private val pendingSpikeCounts = IntArray(SYNAPTIC_DELAY_STEPS + 1)
        private var pendingSpikeCursor = 0

        var foodOn = false
        var lightOn = false
        var dangerOn = false
        var selectedStimulus = 0
        var foodButton: Button? = null
        var lightButton: Button? = null
        var dangerButton: Button? = null
        var resetButton: Button? = null

        private var flyX = .24f
        private var flyY = .55f
        private var heading = -.15f
        private var flySpeed = 0f
        private var flightFactor = 0f
        private var flightPhase = 0f

        // ENVIRONMENT ONLY: food position is never written by neural dynamics or
        // by the feeding/reward path. It changes only through explicit user
        // placement or RESET, keeping the stimulus environment deterministic.
        private var foodX = .76f
        private var foodY = .35f
        private val FOOD_TARSAL_CONTACT_RADIUS = .055f
        private val FOOD_GUSTATORY_SIGMA = .018f
        private val FOOD_CONTACT_GUSTATORY_MIN = .04f
        private var foodContactLatched = false
        private var draggingStimulus = false
        private var lightX = .72f
        private var lightY = .72f
        private var dangerX = .30f
        private var dangerY = .30f

        private var simTime = 0f
        private var neuralAccumulator = 0f
        private var neuralStepsLastFrame = 0
        private var neuralBacklogSeconds = 0f
        private var lastNs = System.nanoTime()
        private var fps = 60f
        private var foodHits = 0
        private var escapeEvents = 0
        private var satiety = 0f
        private var memoryTrace = 0f
        private var lastReward = 0f
        // The connectome is anatomical evidence, not a fitted learning model.
        // Keep synaptic plasticity disabled by default until a biologically
        // justified learning rule and validation set are introduced.
        private val plasticityEnabled = false
        private var lastDangerLevel = 0f
        private var stableLocomotion = 0f

        private var sensoryDisplay = 0f
        private var centralDisplay = 0f
        private var motorDisplay = 0f
        private var visualRateDisplay = 0f
        private var olfactoryRateDisplay = 0f
        private var gustatoryRateDisplay = 0f
        private var mechanosensoryRateDisplay = 0f
        private var descendingRateDisplay = 0f
        private var ascendingRateDisplay = 0f
        private var centralRateDisplay = 0f
        private var motorRateDisplay = 0f
        private var foodSignalDisplay = 0f
        private var lightSignalDisplay = 0f
        private var dangerSignalDisplay = 0f

        private var leftMotor = 0f
        private var rightMotor = 0f
        private var forwardMotor = 0f
        private var escapeMotor = 0f
        private var brakeMotor = 0f
        private var exploreMotor = 0f

        // V1.04: action-selection populations. These are readouts of measured
        // descending/VNC activity, not direct stimulus-to-body commands.
        private var approachAction = 0f
        private var exploreAction = 0f
        private var orientAction = 0f
        private var escapeAction = 0f
        private var brakeAction = 0f
        private var turnLeftAction = 0f
        private var turnRightAction = 0f
        private var forwardRouteActivityDisplay = 0f
        private var turnRouteActivityDisplay = 0f
        private var escapeRouteActivityDisplay = 0f

        // V1.04 presentation: a small set of actual retained neurons is projected
        // onto a 2D anatomical schematic. Links shown in the panel are real edges
        // between those representative neurons, never invented visual topology.
        private val brainDisplayIds = ArrayList<Int>(320)
        private val brainDisplayLookup = IntArray(N) { -1 }
        private val brainDisplayLinks = ArrayList<Pair<Int, Int>>(1800)

        // Visual wing-beat and sound are presentation layers only. They do not
        // feed back into the neural state or body mechanics.
        private var wingActivityCache = 0f
        private var wingBeatPhase = 0f
        private var soundPool: SoundPool? = null
        private var buzzSoundId = 0
        private var buzzStreamId = 0
        private var buzzLoaded = false
        private var soundReleased = false

        private var foodDrive = 0f
        private var lightDrive = 0f
        private var dangerDrive = 0f
        private var foodDirectionalBias = 0f
        private var olfInputLeftCache = 0f
        private var olfInputCenterCache = 0f
        private var olfInputRightCache = 0f
        private var olfInputFrontCache = 0f
        private var olfInputRearCache = 0f
        private var dangerLoom = 0f
        private var previousDangerDistance = Float.NaN

        // V1.13: behaviour is separated into homeostatic pressure, arousal,
        // rest-state modulation, and *measured* locomotor pauses. A pause is no
        // longer created by an external random timer. It is detected from the
        // actual VNC motor output, while the retained halting DNs can strengthen
        // only their EXISTING synapses onto motor neurons. No synthetic edge is added.
        private var explorationState = 0f
        private var explorationPhase = 0f
        private var motorActivityMemory = 0f
        private var sleepPressure = 0f
        private var arousalDrive = 0f
        // V1.13: state is a readout of the body, not a command that forces it.
        private var restState = false
        private var restStateBlend = 0f
        private var pauseDetected = false
        private var stopDetected = false
        private var pauseTimer = 0f
        private var inactivityContinuous = 0f
        private var recentMovementMemory = 0f
        private var pauseCount = 0
        private var lastPauseDuration = 0f
        private var haltEvidenceDisplay = 0f
        private var haltGateDisplay = 0f
        private var walkOffEvidenceDisplay = 0f
        private var brakeEvidenceDisplay = 0f
        private var previousFoodDrive = 0f
        private var previousLightDrive = 0f
        private var previousDangerDrive = 0f
        private var baselineTurnBias = 0f

        // V1.13 diagnostics: these values expose the actual neural pipeline instead
        // of inferring activity from the stimulus UI. They never feed back into the
        // neural dynamics or body mechanics.
        private var spikesLastStep = 0
        private var spikesPerSecond = 0f
        private var spikeWindowCount = 0
        private var spikeWindowTime = 0f
        private var physicalSpeed = 0f
        private var displacementPerSecond = 0f
        private var physicalMovementMemory = 0f
        // Read-only trajectory telemetry for V1.15 validation; never feeds back into dynamics.
        private var pathLength = 0f
        private var maxDisplayedActivity = 0f
        private var sensorySpikesDisplay = 0f
        private var centralSpikesDisplay = 0f
        private var descendingSpikesDisplay = 0f
        private var motorSpikesDisplay = 0f
        private var sensoryDriveDisplay = 0f
        // V1.15.7-UI: motor drive is measured before spike thresholding.
        // Signed values preserve net excitation/inhibition; absolute values expose
        // subthreshold input even when excitation and inhibition partially cancel.
        private var motorDriveSignedCache = 0f
        private var motorDriveAbsCache = 0f
        private var legDriveSignedCache = 0f
        private var wingDriveSignedCache = 0f
        private var neckDriveSignedCache = 0f
        private var abdomenDriveSignedCache = 0f
        private var haltereDriveSignedCache = 0f
        private var otherMotorDriveSignedCache = 0f
        private var leftLegDriveSignedCache = 0f
        private var rightLegDriveSignedCache = 0f
        private var motorDriveAbsPeakCache = 0f
        private var wallDistanceCache = 0f
        private var wallSignalCache = 0f
        private var physicalAcceleration = 0f
        private var previousPhysicalSpeed = 0f
        private var lastMotionX = .24f
        private var lastMotionY = .55f
        private var runtimeFault = ""

        init {
            setBackgroundColor(Color.rgb(250, 250, 250))
            explorationState = .45f
            checkTemporalConfiguration()
            buildBrain()
            setupBuzzSound()
        }

        private fun checkTemporalConfiguration() {
            if (NEURAL_FRAME_DT_SECONDS <= 0f || NEURAL_SUBSTEP_DT_SECONDS <= 0f) {
                throw IllegalStateException("configuracion temporal no positiva")
            }
            val ratio = NEURAL_FRAME_DT_SECONDS / NEURAL_SUBSTEP_DT_SECONDS
            if (abs(ratio - NEURAL_SUBSTEPS_PER_FRAME.toFloat()) > 0.0001f) {
                throw IllegalStateException("substepping inconsistente frame=$NEURAL_FRAME_DT_SECONDS sub=$NEURAL_SUBSTEP_DT_SECONDS n=$NEURAL_SUBSTEPS_PER_FRAME")
            }
            if (NEURAL_SUBSTEP_DT_SECONDS > 0.001f) {
                throw IllegalStateException("substep demasiado grande para tau_syn=5ms")
            }
            if (SYNAPTIC_DELAY_STEPS <= 0 || abs(SYNAPTIC_DELAY_STEPS * NEURAL_SUBSTEP_DT_SECONDS - SYNAPTIC_DELAY_SECONDS) > 0.0003f) {
                throw IllegalStateException("retardo sinaptico incompatible con el substep")
            }
        }

        private fun behaviorLabel(): String {
            val maxAction = max(approachAction, max(escapeAction, max(orientAction, max(exploreAction, brakeAction))))
            return when {
                escapeAction > .16f && escapeAction >= maxAction - .015f -> "ESCAPE"
                brakeAction > .16f && brakeAction >= maxAction - .015f -> "FRENADO NEURAL"
                approachAction > .16f && approachAction >= maxAction - .015f -> "APROXIMACIÓN"
                orientAction > .12f && orientAction >= maxAction - .015f -> {
                    if (turnRightAction >= turnLeftAction) "ORIENTACIÓN DERECHA" else "ORIENTACIÓN IZQUIERDA"
                }
                exploreAction > .12f && exploreAction >= maxAction - .015f -> "EXPLORACIÓN"
                stableLocomotion > .08f && turnRightAction > turnLeftAction + .08f -> "GIRO DERECHA"
                stableLocomotion > .08f && turnLeftAction > turnRightAction + .08f -> "GIRO IZQUIERDA"
                stableLocomotion > .08f -> "AVANCE"
                else -> "REPOSO / ORIENTACIÓN"
            }
        }

        fun selectAndToggle(s: Int) {
            if (selectedStimulus == s) {
                when (s) {
                    0 -> foodOn = !foodOn
                    1 -> lightOn = !lightOn
                    else -> dangerOn = !dangerOn
                }
            } else {
                selectedStimulus = s
                when (s) {
                    0 -> foodOn = true
                    1 -> lightOn = true
                    else -> dangerOn = true
                }
            }
            invalidate()
        }

        private fun styleButton(button: Button, active: Boolean, selected: Boolean, accent: Int) {
            val fill = when {
                active -> Color.rgb(31, 45, 51)
                else -> Color.rgb(28, 36, 41)
            }
            button.background = GradientDrawable().apply {
                cornerRadius = 13.dp().toFloat()
                setColor(fill)
                setStroke(if (selected) 2 else 1, if (selected) accent else Color.rgb(66, 78, 85))
            }
            button.setTextColor(if (active) Color.WHITE else Color.rgb(214, 221, 224))
            button.alpha = if (active || selected) 1f else .82f
        }

        fun updateButtons() {
            foodButton?.let {
                it.text = if (foodOn) "◉  COMIDA  ON" else "◉  COMIDA"
                styleButton(it, foodOn, selectedStimulus == 0, Color.rgb(35, 120, 70))
            }
            lightButton?.let {
                it.text = if (lightOn) "☼  LUZ  ON" else "☼  LUZ"
                styleButton(it, lightOn, selectedStimulus == 1, Color.rgb(210, 145, 10))
            }
            dangerButton?.let {
                it.text = if (dangerOn) "△  PELIGRO  ON" else "△  PELIGRO"
                styleButton(it, dangerOn, selectedStimulus == 2, Color.rgb(190, 45, 45))
            }
        }

        fun resetSimulation() {
            for (i in 0 until N) {
                v[i] = V_REST
                fired[i] = false
                frameFired[i] = false
                refractory[i] = 0f
                synConductance[i] = 0f
                externalForced[i] = false
                externalRateHz[i] = 0f
                lastSynDrive[i] = 0f
                visualActivity[i] = 0f
                if (i in MOTOR_START until MOTOR_END) {
                    val mi = i - MOTOR_START
                    motorSubstepSpikeCounts[mi] = 0
                    motorSynDriveSum[mi] = 0f
                    motorSynDrivePeak[mi] = 0f
                }
            }
            flyX = .24f
            flyY = .55f
            heading = -.15f
            flySpeed = 0f
            flightFactor = 0f
            flightPhase = 0f
            jumpActivityCacheValue = 0f
            setFoodPosition(.76f, .35f)
            foodContactLatched = false
            draggingStimulus = false
            lightX = .72f
            lightY = .72f
            dangerX = .30f
            dangerY = .30f
            simTime = 0f
            neuralAccumulator = 0f
            neuralStepsLastFrame = 0
            neuralBacklogSeconds = 0f
            foodHits = 0
            escapeEvents = 0
            satiety = 0f
            memoryTrace = 0f
            lastReward = 0f
            lastDangerLevel = 0f
            stableLocomotion = 0f
            sensoryDisplay = 0f
            centralDisplay = 0f
            motorDisplay = 0f
            visualRateDisplay = 0f
            olfactoryRateDisplay = 0f
            gustatoryRateDisplay = 0f
            mechanosensoryRateDisplay = 0f
            descendingRateDisplay = 0f
            ascendingRateDisplay = 0f
            centralRateDisplay = 0f
            motorRateDisplay = 0f
            foodSignalDisplay = 0f
            lightSignalDisplay = 0f
            dangerSignalDisplay = 0f
            leftMotor = 0f
            rightMotor = 0f
            forwardMotor = 0f
            escapeMotor = 0f
            brakeMotor = 0f
            exploreMotor = 0f
            approachAction = 0f
            exploreAction = 0f
            orientAction = 0f
            escapeAction = 0f
            brakeAction = 0f
            turnLeftAction = 0f
            turnRightAction = 0f
            forwardRouteActivityDisplay = 0f
            turnRouteActivityDisplay = 0f
            escapeRouteActivityDisplay = 0f
            foodDrive = 0f
            lightDrive = 0f
            dangerDrive = 0f
            foodDirectionalBias = 0f
            olfInputLeftCache = 0f
            olfInputCenterCache = 0f
            olfInputRightCache = 0f
            olfInputFrontCache = 0f
            olfInputRearCache = 0f
            dangerLoom = 0f
            previousDangerDistance = Float.NaN
            explorationState = .45f
            explorationPhase = 0f
            motorActivityMemory = 0f
            sleepPressure = 0f
            arousalDrive = 0f
            restState = false
            restStateBlend = 0f
            pauseDetected = false
            stopDetected = false
            pauseTimer = 0f
            inactivityContinuous = 0f
            recentMovementMemory = 0f
            pauseCount = 0
            lastPauseDuration = 0f
            haltEvidenceDisplay = 0f
            haltGateDisplay = 0f
            walkOffEvidenceDisplay = 0f
            brakeEvidenceDisplay = 0f
            previousFoodDrive = 0f
            previousLightDrive = 0f
            previousDangerDrive = 0f
            baselineTurnBias = 0f
            exploratoryTurn = 0f
            spikesLastStep = 0
            spikesPerSecond = 0f
            spikeWindowCount = 0
            spikeWindowTime = 0f
            physicalSpeed = 0f
            displacementPerSecond = 0f
            physicalMovementMemory = 0f
            pathLength = 0f
            maxDisplayedActivity = 0f
            sensorySpikesDisplay = 0f
            centralSpikesDisplay = 0f
            descendingSpikesDisplay = 0f
            motorSpikesDisplay = 0f
            sensoryDriveDisplay = 0f
            motorDriveSignedCache = 0f
            motorDriveAbsCache = 0f
            legDriveSignedCache = 0f
            wingDriveSignedCache = 0f
            neckDriveSignedCache = 0f
            abdomenDriveSignedCache = 0f
            haltereDriveSignedCache = 0f
            otherMotorDriveSignedCache = 0f
            leftLegDriveSignedCache = 0f
            rightLegDriveSignedCache = 0f
            motorDriveAbsPeakCache = 0f
            wallDistanceCache = 0f
            wallSignalCache = 0f
            physicalAcceleration = 0f
            previousPhysicalSpeed = 0f
            lastMotionX = flyX
            lastMotionY = flyY
            runtimeFault = ""
            dnSpikingCount = 0
            motorSpikingCount = 0
            diagnosticRefreshClock = 0f
            diagWindowElapsed = 0f
            baselineCaptureSeconds = 0f
            baselineReady = false
            olfWindowSpikesLeft = 0
            olfWindowSpikesRight = 0
            olfWindowSpikesUnknown = 0
            olfLeftHz = 0f
            olfRightHz = 0f
            dnLeftHz = 0f
            dnRightHz = 0f
            legLeftHz = 0f
            legRightHz = 0f
            for (slot in pendingSpikeCounts.indices) pendingSpikeCounts[slot] = 0
            pendingSpikeCursor = 0
            java.util.Arrays.fill(dnWindowSpikes, 0)
            java.util.Arrays.fill(motorWindowSpikes, 0)
            java.util.Arrays.fill(dnBaselineSpikes, 0)
            legActiveCache = 0
            leftLegActiveCache = 0
            rightLegActiveCache = 0
            unknownLegActiveCache = 0
            wingActiveCache = 0
            jumpActiveCache = 0
            motorOtherActiveCache = 0
            java.util.Arrays.fill(motorBaselineSpikes, 0)
            historyCursor = 0
            historyClock = 0f
            java.util.Arrays.fill(historyNeural, 0f)
            java.util.Arrays.fill(historyDn, 0f)
            java.util.Arrays.fill(historyMotor, 0f)
            java.util.Arrays.fill(topDnIds, -1)
            java.util.Arrays.fill(topDnHz, 0f)
            java.util.Arrays.fill(topDnDelta, 0f)
            java.util.Arrays.fill(topDnVm, V_REST)
            java.util.Arrays.fill(topDnWindowSpikes, 0)
            java.util.Arrays.fill(topMotorIds, -1)
            java.util.Arrays.fill(topMotorHz, 0f)
            java.util.Arrays.fill(topMotorDelta, 0f)
            java.util.Arrays.fill(topMotorVm, V_REST)
            java.util.Arrays.fill(topMotorWindowSpikes, 0)
            foodOn = false
            lightOn = false
            dangerOn = false
            selectedStimulus = 0
            legActivityCache = 0f
            wingActivityCache = 0f
            legActivationState = 0f
            leftLegActivationState = 0f
            rightLegActivationState = 0f
            wingActivationState = 0f
            neckActivationState = 0f
            jumpActivationState = 0f
            abdomenActivationState = 0f
            wingBeatPhase = 0f
            if (buzzStreamId != 0) {
                soundPool?.stop(buzzStreamId)
                buzzStreamId = 0
            }
            lastNs = System.nanoTime()
            invalidate()
        }

        private var connectomeLoaded = false
        private var connectomeError = ""
        private var loadedEdgeCount = 0
        private var loadedDynamicsEdgeCount = 0
        private var dynamicsLoaded = false

        private fun buildBrain() {
            connectomeLoaded = loadMeasuredConnectome()
            if (!connectomeLoaded) {
                runtimeFault = connectomeError.ifEmpty { "no se pudo cargar FBR-10 / dinámica FBD105" }
                // Fail closed: never substitute an artificial graph for the
                // published connectome. This makes a packaging/format error
                // visible instead of producing scientifically misleading output.
                for (i in 0 until N) {
                    incoming[i] = IntArray(0)
                    outgoing[i] = IntArray(0)
                    outgoingW[i] = FloatArray(0)
                }
            }
        }

        private fun validateGeneratedMeta() {
            val ranges = arrayOf(
                intArrayOf(VIS_START, VIS_END),
                intArrayOf(OLF_START, OLF_END),
                intArrayOf(GUST_START, GUST_END),
                intArrayOf(MECH_START, MECH_END),
                intArrayOf(DESC_START, DESC_END),
                intArrayOf(ASC_START, ASC_END),
                intArrayOf(MOTOR_START, MOTOR_END),
                intArrayOf(OTHER_START, OTHER_END),
            )
            var previous = 0
            for (pair in ranges) {
                val start = pair[0]
                val end = pair[1]
                if (start < 0 || end < start || end > N || start != previous) {
                    throw IllegalStateException(
                        "rangos de poblacion invalidos: $start..$end (N=$N, prev=$previous)"
                    )
                }
                previous = end
            }
            if (previous != N) {
                throw IllegalStateException("rangos no cubren exactamente N=$N (fin=$previous)")
            }
            val required = arrayOf(
                "VIS" to (VIS_END - VIS_START),
                "OLF" to (OLF_END - OLF_START),
                "GUST" to (GUST_END - GUST_START),
                "MECH" to (MECH_END - MECH_START),
                "DESC" to (DESC_END - DESC_START),
                "ASC" to (ASC_END - ASC_START),
                "MOTOR" to (MOTOR_END - MOTOR_START)
            )
            for ((name, size) in required) {
                if (size <= 0) throw IllegalStateException("poblacion $name vacia")
            }
        }

        private fun loadSensoryInputMap() {
            val parsed = assets.open("sensory_input_map.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
                val header = reader.readLine() ?: throw IllegalStateException("SENSMAP cabecera ausente")
                val expectedHeader = "index\tbodyId\tmodality\tsideCode\tsideSource\ttype\tclass\tsuperclass\tsubclass\treceptorType\tflywireType"
                if (header != expectedHeader) throw IllegalStateException("SENSMAP cabecera inesperada")
                reader.readLines()
            }

            val visual = ArrayList<Int>()
            val gustatory = ArrayList<Int>()
            val mechanosensory = ArrayList<Int>()
            val seen = HashSet<Int>(parsed.size * 2)
            val sideSource = setOf("somaSide", "rootSide", "unknown")

            for (line in parsed) {
                val c = line.split('\t')
                if (c.size != 11) throw IllegalStateException("SENSMAP esquema inesperado: ${c.size} columnas")
                val idx = c[0].toInt()
                val bid = c[1].toLong()
                val modality = c[2]
                val side = c[3].toInt()
                val source = c[4]
                val type = c[5]
                val clazz = c[6]
                val superclass = c[7]
                val subtype = c[8]
                val receptorType = c[9]
                val flywireType = c[10]

                if (idx !in 0 until N) throw IllegalStateException("SENSMAP index fuera de FBC103: $idx")
                if (bodyId[idx] != bid) {
                    throw IllegalStateException("SENSMAP bodyId mismatch idx=$idx expected=${bodyId[idx]} got=$bid")
                }
                if (side !in -1..1) throw IllegalStateException("SENSMAP sideCode invalido bodyId=$bid")
                if (source !in sideSource) throw IllegalStateException("SENSMAP sideSource invalido bodyId=$bid source=$source")
                if (!seen.add(idx)) throw IllegalStateException("SENSMAP indice duplicado=$idx")

                when (modality) {
                    "VIS" -> {
                        if (idx !in VIS_START until VIS_END) {
                            throw IllegalStateException("SENSMAP VIS fuera de bloque bodyId=$bid idx=$idx")
                        }
                        if (superclass != "ol_sensory" || clazz != "visual") {
                            throw IllegalStateException("SENSMAP VIS provenance invalida bodyId=$bid class=$clazz superclass=$superclass")
                        }
                        val photoreceptor = flywireType in setOf("R1-6", "R7", "R8") ||
                            type == "R1-R6" || type.startsWith("R7") || type.startsWith("R8")
                        if (!photoreceptor) throw IllegalStateException("SENSMAP VIS no photoreceptor bodyId=$bid type=$type flywireType=$flywireType")
                        visual.add(idx)
                    }
                    "GUST" -> {
                        if (idx !in GUST_START until GUST_END) {
                            throw IllegalStateException("SENSMAP GUST fuera de bloque bodyId=$bid idx=$idx")
                        }
                        if (clazz != "gustatory" || superclass !in setOf("cb_sensory", "vnc_sensory")) {
                            throw IllegalStateException("SENSMAP GUST provenance invalida bodyId=$bid class=$clazz superclass=$superclass")
                        }
                        gustatory.add(idx)
                    }
                    "MECH" -> {
                        if (idx !in MECH_START until MECH_END) {
                            throw IllegalStateException("SENSMAP MECH fuera de bloque bodyId=$bid idx=$idx")
                        }
                        if (clazz != "mechanosensory_proprioceptive") {
                            throw IllegalStateException("SENSMAP MECH provenance invalida bodyId=$bid class=$clazz")
                        }
                        if (subtype.isBlank()) {
                            throw IllegalStateException("SENSMAP MECH sin organo receptor bodyId=$bid type=$type")
                        }
                        mechanosensory.add(idx)
                    }
                    else -> throw IllegalStateException("SENSMAP modalidad desconocida=$modality bodyId=$bid")
                }
            }

            if (visual.isEmpty()) throw IllegalStateException("SENSMAP sin fotorreceptores retenidos")
            if (gustatory.isEmpty()) throw IllegalStateException("SENSMAP sin receptores gustativos retenidos")
            if (mechanosensory.isEmpty()) throw IllegalStateException("SENSMAP sin receptores mecanosensoriales retenidos")

            visualReceptorIndices = visual.toIntArray().also { it.sort() }
            gustatoryReceptorIndices = gustatory.toIntArray().also { it.sort() }
            mechanosensoryReceptorIndices = mechanosensory.toIntArray().also { it.sort() }
        }

        private fun loadOlfactoryInputMap() {
            val parsed = assets.open("olfactory_input_map.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
                val header = reader.readLine() ?: throw IllegalStateException("OLFMAP cabecera ausente")
                val expectedHeader = "index\tbodyId\tsideCode\tsideSource\ttype\tclass\tsuperclass\tconsensus_nt\tsubclass\tinstance\treceptorType\trootSide\tsomaSide\tentryNerve"
                if (header != expectedHeader) throw IllegalStateException("OLFMAP cabecera inesperada")
                reader.readLines()
            }
            if (parsed.size != EXPECTED_RETAINED_OLFACTORY_ORNS) {
                throw IllegalStateException(
                    "OLFMAP filas=${parsed.size} esperado=$EXPECTED_RETAINED_OLFACTORY_ORNS"
                )
            }
            val seen = HashSet<Long>(parsed.size * 2)
            val typeEntryNervePairs = HashSet<String>(
                EXPECTED_RETAINED_OLFACTORY_ORN_TYPE_ENTRY_NERVE_PAIRS * 2
            )
            val officialUntypedOrnIds = setOf(242812L, 242908L, 488209L, 956041L)
            val indices = IntArray(parsed.size)
            for ((rowNo, line) in parsed.withIndex()) {
                val c = line.split('\t')
                if (c.size != 14) throw IllegalStateException("OLFMAP esquema inesperado: ${c.size} columnas")
                val idx = c[0].toInt()
                val bid = c[1].toLong()
                val side = c[2].toInt()
                val type = c[4]
                val clazz = c[5]
                val superclass = c[6]
                val nt = c[7]
                if (idx !in 0 until N) throw IllegalStateException("OLFMAP index fuera de FBC103: $idx")
                if (bodyId[idx] != bid) throw IllegalStateException("OLFMAP bodyId mismatch idx=$idx expected=${bodyId[idx]} got=$bid")
                if (side !in -1..1) throw IllegalStateException("OLFMAP sideCode invalido bodyId=$bid")
                if (!seen.add(bid)) throw IllegalStateException("OLFMAP bodyId duplicado=$bid")
                if (type.isBlank()) {
                    if (bid !in officialUntypedOrnIds) {
                        throw IllegalStateException(
                            "OLFMAP untyped ORN is not an official MaleCNS exception bodyId=$bid"
                        )
                    }
                } else if (!type.startsWith("ORN_")) {
                    throw IllegalStateException("OLFMAP no-ORN bodyId=$bid type=$type")
                }
                if (clazz != "olfactory") throw IllegalStateException("OLFMAP class no olfactory bodyId=$bid class=$clazz")
                if (superclass != "cb_sensory") throw IllegalStateException("OLFMAP superclass inesperada bodyId=$bid superclass=$superclass")
                val entryNerve = c[13].uppercase()
                if (entryNerve != "AN" && entryNerve != "MXLBN") {
                    throw IllegalStateException(
                        "OLFMAP entryNerve inesperado bodyId=$bid entryNerve=${c[13]}"
                    )
                }
                if (type.isNotBlank()) {
                    typeEntryNervePairs.add("$type|$entryNerve")
                }
                if (nt.lowercase() != "acetylcholine") {
                    throw IllegalStateException("OLFMAP NT inesperado bodyId=$bid nt=$nt")
                }
                indices[rowNo] = idx
                olfactorySide[idx] = side.toByte()
            }
            indices.sort()
            for (i in indices.indices) {
                if (i > 0 && indices[i] == indices[i - 1]) throw IllegalStateException("OLFMAP indice duplicado=${indices[i]}")
            }
            olfactoryNeuronIndices = indices
            val left = olfactoryNeuronIndices.count { olfactorySide[it].toInt() == -1 }
            val right = olfactoryNeuronIndices.count { olfactorySide[it].toInt() == 1 }
            val unknown = olfactoryNeuronIndices.count { olfactorySide[it].toInt() == 0 }
            if (left + right + unknown != EXPECTED_RETAINED_OLFACTORY_ORNS) {
                throw IllegalStateException(
                    "OLFMAP lateralidad inconsistente L=$left R=$right U=$unknown total=$EXPECTED_RETAINED_OLFACTORY_ORNS"
                )
            }
            if (left == 0 || right == 0) {
                throw IllegalStateException("OLFMAP debe conservar evidencia bilateral: L=$left R=$right U=$unknown")
            }
            if (typeEntryNervePairs.size != EXPECTED_RETAINED_OLFACTORY_ORN_TYPE_ENTRY_NERVE_PAIRS) {
                throw IllegalStateException(
                    "OLFMAP combinaciones type+entryNerve=${typeEntryNervePairs.size} " +
                        "esperado=$EXPECTED_RETAINED_OLFACTORY_ORN_TYPE_ENTRY_NERVE_PAIRS"
                )
            }
        }

        private fun isOlfactoryNeuron(index: Int): Boolean {
            return index >= 0 && index < N && olfactoryNeuronIndices.binarySearch(index) >= 0
        }

        private fun antennaOdorConcentration(foodX: Float, foodY: Float, side: Int): Float {
            // Two virtual antenna sampling points are a physical sensor-interface
            // model only. They are not a neural shortcut: the resulting current is
            // injected only into the retained OLF neurons and must propagate through
            // FBR-10 to affect behavior.
            return OlfactorySensorModel.sampleAntenna(
                flyX = flyX,
                flyY = flyY,
                heading = heading,
                foodX = foodX,
                foodY = foodY,
                side = side,
                sigma = FOOD_OLF_SIGMA
            )
        }

        private fun loadVncMotorSemantics() {
            val parsed = assets.open("vnc_motor_semantics.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
                val header = reader.readLine() ?: throw IllegalStateException("VNCSEM cabecera ausente")
                val expectedHeader = "bodyId\ttype\tclass\tsubclass\tsomaSide\tsomaNeuromere\texitNerve\tanatomicalClass\tfunctionalTag\tclassSource\tcurrentFBC103Role\tcurrentFBC103RoleCode\tsemanticRoleCode\tdiscrepancy"
                if (header != expectedHeader) throw IllegalStateException("VNCSEM cabecera inesperada")
                reader.readLines()
            }
            val rows = parsed
            if (rows.size != (MOTOR_END - MOTOR_START)) {
                throw IllegalStateException("VNCSEM filas=${rows.size} esperado=${MOTOR_END - MOTOR_START}")
            }
            val byBody = HashMap<Long, Triple<Int, Int, Int>>(rows.size * 2)
            for (line in rows) {
                val c = line.split('\t')
                if (c.size != 14) throw IllegalStateException("VNCSEM esquema inesperado: ${c.size} columnas")
                val id = c[0].toLong()
                val role = c[12].toInt()
                val classRole = when (c[7]) {
                    "LEG" -> GeneratedConnectomeMeta.MOTOR_LEG
                    "WING" -> GeneratedConnectomeMeta.MOTOR_WING
                    "HALTERE" -> GeneratedConnectomeMeta.MOTOR_HALTERE
                    "NECK" -> GeneratedConnectomeMeta.MOTOR_NECK
                    "ABDOMEN" -> GeneratedConnectomeMeta.MOTOR_ABDOMEN
                    "OTHER" -> GeneratedConnectomeMeta.MOTOR_OTHER
                    else -> throw IllegalStateException("VNCSEM clase anatómica inválida=${c[7]} bodyId=$id")
                }
                if (role != classRole) {
                    throw IllegalStateException("VNCSEM role/class mismatch bodyId=$id class=${c[7]} role=$role expected=$classRole")
                }
                val fn = when (c[8]) {
                    "JUMP" -> GeneratedConnectomeMeta.MOTOR_FUNCTION_JUMP
                    "NONE" -> GeneratedConnectomeMeta.MOTOR_FUNCTION_NONE
                    else -> throw IllegalStateException("VNCSEM functionalTag inválido=${c[8]} bodyId=$id")
                }
                val side = when (c[4]) {
                    "L" -> -1
                    "R" -> 1
                    else -> throw IllegalStateException("VNCSEM lado inválido bodyId=$id")
                }
                if (role !in GeneratedConnectomeMeta.MOTOR_LEG..GeneratedConnectomeMeta.MOTOR_OTHER) {
                    throw IllegalStateException("VNCSEM rol anatómico inválido=$role bodyId=$id")
                }
                byBody[id] = Triple(role, fn, side)
            }
            if (byBody.size != rows.size) throw IllegalStateException("VNCSEM bodyId duplicado")
            for (i in MOTOR_START until MOTOR_END) {
                val pair = byBody[bodyId[i]] ?: throw IllegalStateException("VNCSEM falta bodyId=${bodyId[i]}")
                motorRole[i] = pair.first.toByte()
                motorFunctionalTag[i] = pair.second.toByte()
                nodeSide[i] = pair.third.toByte()
            }
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = StringBuilder(digest.size * 2)
            for (value in digest) {
                val v = value.toInt() and 0xff
                if (v < 16) out.append('0')
                out.append(v.toString(16))
            }
            return out.toString()
        }

        private fun loadMeasuredConnectome(): Boolean {
            return try {
                validateGeneratedMeta()
                val resourceId = resources.getIdentifier("malecns_reduced", "raw", packageName)
                if (resourceId == 0) {
                    connectomeError = "recurso malecns_reduced no encontrado"
                    return false
                }
                val bytes = resources.openRawResource(resourceId).use { it.readBytes() }
                val actualBinarySha = sha256Hex(bytes)
                if (actualBinarySha != GeneratedConnectomeMeta.BINARY_SHA256) {
                    throw IllegalStateException(
                        "FBC103 SHA-256=$actualBinarySha esperado=${GeneratedConnectomeMeta.BINARY_SHA256}"
                    )
                }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (b.remaining() < 16) throw IllegalStateException("cabecera incompleta")
                val magic = ByteArray(8)
                b.get(magic)
                val magicText = magic.toString(Charsets.US_ASCII).trimEnd('\u0000')
                if (magicText != GeneratedConnectomeMeta.FORMAT_MAGIC) {
                    throw IllegalStateException("magic=$magicText esperado=${GeneratedConnectomeMeta.FORMAT_MAGIC}")
                }
                val n = b.int
                val e = b.int
                if (n != N) throw IllegalStateException("neuronas=$n esperado=$N")
                if (e < 0 || e > 50_000_000) throw IllegalStateException("edges=$e fuera de rango")
                val nodeBytes = n.toLong() * 26L
                val edgeBytes = e.toLong() * 12L
                val expectedBytes = 16L + nodeBytes + edgeBytes
                if (bytes.size.toLong() != expectedBytes) {
                    throw IllegalStateException("tamaño=${bytes.size} esperado=$expectedBytes")
                }
                // Node metadata is kept in the binary for provenance/inspection.
                repeat(n) {
                    bodyId[it] = b.long
                    b.get()
                    nodeSide[it] = b.get()
                    b.get()
                    motorRole[it] = b.get()
                    descendingRole[it] = b.get()
                    haltRole[it] = b.get()
                    routeForward[it] = b.float
                    routeTurn[it] = b.float
                    routeEscape[it] = b.float
                    if (!routeForward[it].isFinite() ||
                        !routeTurn[it].isFinite() ||
                        !routeEscape[it].isFinite() ||
                        routeForward[it] !in 0f..1f ||
                        routeTurn[it] !in 0f..1f ||
                        routeEscape[it] !in 0f..1f) {
                        throw IllegalStateException("metadata de ruta invalida en nodo $it")
                    }
                }

                loadSensoryInputMap()
                loadOlfactoryInputMap()
                loadVncMotorSemantics()

                // Build fixed VNC motor-role denominators from the official-annotation-derived VNC semantics layer.

                // The runtime never invents a role: every neuron in VMOTOR is already
                // a published/curated vnc_motor entry with one stored role code.
                java.util.Arrays.fill(motorRoleTotals, 0)
                legLeftTotal = 0
                legRightTotal = 0
                legUnknownSideTotal = 0
                wingLeftTotal = 0
                wingRightTotal = 0
                jumpLeftTotal = 0
                jumpRightTotal = 0
                for (i in MOTOR_START until MOTOR_END) {
                    val role = motorRole[i].toInt()
                    if (role !in GeneratedConnectomeMeta.MOTOR_LEG..GeneratedConnectomeMeta.MOTOR_OTHER) {
                        throw IllegalStateException("rol VNC motor anatómico invalido en nodo=$i: $role")
                    }
                    motorRoleTotals[role]++
                    when (role) {
                        GeneratedConnectomeMeta.MOTOR_LEG -> when (nodeSide[i].toInt()) {
                            -1 -> legLeftTotal++
                            1 -> legRightTotal++
                            else -> legUnknownSideTotal++
                        }
                        GeneratedConnectomeMeta.MOTOR_WING -> when (nodeSide[i].toInt()) {
                            -1 -> wingLeftTotal++
                            1 -> wingRightTotal++
                        }
                    }
                    if (motorFunctionalTag[i].toInt() == GeneratedConnectomeMeta.MOTOR_FUNCTION_JUMP) {
                        when (nodeSide[i].toInt()) {
                            -1 -> jumpLeftTotal++
                            1 -> jumpRightTotal++
                        }
                    }
                }
                if (motorRoleTotals.sum() != (MOTOR_END - MOTOR_START)) {
                    throw IllegalStateException(
                        "censo VNC motor inconsistente: ${motorRoleTotals.sum()} != ${MOTOR_END - MOTOR_START}"
                    )
                }

                // FBR-10 has ~2.06M edges. Do not build boxed MutableList<Int/Float>
                // structures here: on Android that creates a very large temporary
                // object graph and can trigger GC pressure/OOM before the simulation starts.
                // Read the packed edge records into primitive arrays, count incoming
                // degree, then materialize compact primitive adjacency arrays.
                val srcs = IntArray(e)
                val dsts = IntArray(e)
                val weights = FloatArray(e)
                val incomingCount = IntArray(n)
                repeat(e) {
                    val src = b.int
                    val dst = b.int
                    val weight = b.float
                    if (src !in 0 until n || dst !in 0 until n) {
                        throw IllegalStateException("edge[$it] fuera de rango: $src->$dst")
                    }
                    if (!weight.isFinite() || weight == 0f) {
                        throw IllegalStateException("edge[$it] con peso invalido")
                    }
                    srcs[it] = src
                    dsts[it] = dst
                    weights[it] = weight
                    incomingCount[dst]++
                }
                if (b.hasRemaining()) throw IllegalStateException("bytes restantes=${b.remaining()}")

                val writePos = IntArray(n)
                for (i in 0 until n) {
                    incoming[i] = IntArray(incomingCount[i])
                }
                for (k in 0 until e) {
                    val target = dsts[k]
                    val pos = writePos[target]++
                    incoming[target][pos] = srcs[k]
                }
                buildBrainDisplayGraph()
                loadedEdgeCount = e
                if (!loadDynamicsLayer()) {
                    throw IllegalStateException(connectomeError.ifEmpty { "FBD105 dynamics layer unavailable" })
                }
                if (loadedDynamicsEdgeCount <= 0 || loadedDynamicsEdgeCount > loadedEdgeCount) {
                    throw IllegalStateException(
                        "FBD105 edges=$loadedDynamicsEdgeCount incompatible con FBC103 edges=$loadedEdgeCount"
                    )
                }
                if (loadedEdgeCount != GeneratedConnectomeMeta.EDGES) {
                    throw IllegalStateException(
                        "edges=$loadedEdgeCount esperado=${GeneratedConnectomeMeta.EDGES}; binario y metadata no coinciden"
                    )
                }
                if (loadedEdgeCount <= 0) throw IllegalStateException("connectome sin conexiones")
                if (brainDisplayIds.isEmpty()) throw IllegalStateException("visualizador neuronal sin neuronas")
                if (brainDisplayLinks.isEmpty()) throw IllegalStateException("visualizador neuronal sin conexiones representables")
                connectomeError = ""
                true
            } catch (ex: Exception) {
                connectomeError = ex.message ?: ex.javaClass.simpleName
                loadedEdgeCount = 0
                false
            }
        }

        private fun loadDynamicsLayer(): Boolean {
            return try {
                val resourceId = resources.getIdentifier("malecns_fbr10_dynamics", "raw", packageName)
                if (resourceId == 0) {
                    connectomeError = "recurso malecns_fbr10_dynamics no encontrado"
                    return false
                }
                val bytes = resources.openRawResource(resourceId).use { it.readBytes() }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (b.remaining() < 17) throw IllegalStateException("FBD105 cabecera incompleta")
                val magic = ByteArray(8)
                b.get(magic)
                val magicText = magic.toString(Charsets.US_ASCII).trimEnd('\u0000')
                if (magicText != "FBD105") throw IllegalStateException("magic=$magicText esperado=FBD105")
                val n = b.int
                val e = b.int
                if (n != N) throw IllegalStateException("FBD105 neuronas=$n esperado=$N")
                if (e < 0 || e > 50_000_000) throw IllegalStateException("FBD105 edges=$e fuera de rango")
                val expectedBytes = 16L + n.toLong() + e.toLong() * 12L
                if (bytes.size.toLong() != expectedBytes) {
                    throw IllegalStateException("FBD105 tamaño=${bytes.size} esperado=$expectedBytes")
                }
                repeat(n) { idx ->
                    neuroSign[idx] = b.get()
                    val sign = neuroSign[idx].toInt()
                    if (sign != -1 && sign != 0 && sign != 1) {
                        throw IllegalStateException("FBD105 signo invalido en nodo $idx")
                    }
                }

                val srcs = IntArray(e)
                val dsts = IntArray(e)
                val weights = FloatArray(e)
                val incomingCount = IntArray(n)
                val outgoingCount = IntArray(n)
                repeat(e) { edgeIndex ->
                    val src = b.int
                    val dst = b.int
                    val weight = b.float
                    if (src !in 0 until n || dst !in 0 until n) throw IllegalStateException("FBD105 edge[$edgeIndex] fuera de rango: $src->$dst")
                    if (!weight.isFinite() || weight == 0f) throw IllegalStateException("FBD105 edge[$edgeIndex] con peso invalido")
                    if (neuroSign[src].toInt() == 0) throw IllegalStateException("FBD105 edge[$edgeIndex] usa presinaptica sin signo")
                    if ((weight > 0f) != (neuroSign[src].toInt() > 0)) throw IllegalStateException("FBD105 signo inconsistente en edge[$edgeIndex]")
                    srcs[edgeIndex] = src
                    dsts[edgeIndex] = dst
                    weights[edgeIndex] = weight
                    incomingCount[dst]++
                    outgoingCount[src]++
                }
                if (b.hasRemaining()) throw IllegalStateException("FBD105 bytes restantes=${b.remaining()}")

                for (i in 0 until n) {
                    incoming[i] = IntArray(incomingCount[i])
                    outgoing[i] = IntArray(outgoingCount[i])
                    outgoingW[i] = FloatArray(outgoingCount[i])
                }
                val inPos = IntArray(n)
                val outPos = IntArray(n)
                for (k in 0 until e) {
                    val src = srcs[k]
                    val dst = dsts[k]
                    val w = weights[k]
                    val ip = inPos[dst]++
                    incoming[dst][ip] = src
                    val op = outPos[src]++
                    outgoing[src][op] = dst
                    outgoingW[src][op] = w
                }
                loadedDynamicsEdgeCount = e
                dynamicsLoaded = true
                true
            } catch (ex: Exception) {
                dynamicsLoaded = false
                loadedDynamicsEdgeCount = 0
                connectomeError = ex.message ?: ex.javaClass.simpleName
                false
            }
        }

        private fun buildBrainDisplayGraph() {
            brainDisplayIds.clear()
            java.util.Arrays.fill(brainDisplayLookup, -1)
            brainDisplayLinks.clear()

            fun addId(idx: Int) {
                if (idx !in 0 until N) return
                if (brainDisplayLookup[idx] >= 0) return
                if (brainDisplayIds.size >= 320) return
                brainDisplayLookup[idx] = brainDisplayIds.size
                brainDisplayIds.add(idx)
            }

            fun addPopulation(start: Int, end: Int, count: Int, excludeOlfactory: Boolean = false) {
                val candidates = if (excludeOlfactory) (start until end).filterNot { isOlfactoryNeuron(it) } else (start until end).toList()
                val size = candidates.size
                if (size <= 0 || count <= 0) return
                val take = min(count, size)
                for (j in 0 until take) {
                    val idx = candidates[if (take == 1) 0 else
                        ((j.toLong() * (size - 1).toLong()) / (take - 1).toLong()).toInt()]
                    addId(idx)
                }
            }

            // Balanced anatomical sample plus explicit diagnostic coverage of the
            // VNC/halting populations. This is presentation only: every displayed
            // neuron and every displayed edge still comes from the real graph.
            addPopulation(VIS_START, VIS_END, 42)
            for (j in 0 until min(20, olfactoryNeuronIndices.size)) addId(olfactoryNeuronIndices[j * olfactoryNeuronIndices.size / min(20, olfactoryNeuronIndices.size)])
            addPopulation(GUST_START, GUST_END, 12)
            addPopulation(MECH_START, MECH_END, 12, excludeOlfactory = true)
            addPopulation(OTHER_START, OTHER_END, 92)
            addPopulation(DESC_START, DESC_END, 28)
            addPopulation(ASC_START, ASC_END, 18)
            addPopulation(MOTOR_START, MOTOR_END, 40)

            for (i in 0 until N) {
                if (haltRole[i].toInt() != 0) addId(i)
                if (brainDisplayIds.size >= 320) break
            }

            // First preserve actual edges among the representative set. Then make
            // a second pass that favours active anatomical paths, while never
            // inventing a link.
            fun rebuildLinks() {
                brainDisplayLinks.clear()
                for (targetRep in brainDisplayIds.indices) {
                    val target = brainDisplayIds[targetRep]
                    var added = 0
                    for (source in incoming[target]) {
                        val sourceRep = brainDisplayLookup[source]
                        if (sourceRep >= 0 && sourceRep != targetRep) {
                            brainDisplayLinks.add(Pair(sourceRep, targetRep))
                            added++
                            if (added >= 8) break
                        }
                    }
                }
            }

            rebuildLinks()
            if (brainDisplayLinks.isEmpty()) {
                // Guarantee that the diagnostic map can display at least one real
                // retained connection without inventing topology. Add actual source
                // endpoints of representative targets, then rebuild the link list.
                val seeds = brainDisplayIds.toList()
                for (target in seeds) {
                    val source = incoming[target].firstOrNull { it != target } ?: continue
                    addId(source)
                    if (brainDisplayIds.size >= 320) break
                }
                rebuildLinks()
            }
            if (brainDisplayLinks.isEmpty()) {
                throw IllegalStateException("ninguna arista real conecta las neuronas representativas")
            }
        }

        private fun setupBuzzSound() {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setAudioAttributes(attributes)
                .setMaxStreams(1)
                .build()
                .also { pool ->
                    pool.setOnLoadCompleteListener { _, sampleId, status ->
                        if (sampleId == buzzSoundId) buzzLoaded = status == 0
                    }
                    buzzSoundId = pool.load(this@MainActivity, R.raw.fly_buzz, 1)
                }
        }

        private fun updateBuzzSound() {
            if (soundReleased || !buzzLoaded || buzzSoundId == 0) return
            val movement = (abs(flySpeed) / .010f).coerceIn(0f, 1f)
            val interaction = max(foodDrive, max(lightDrive, dangerDrive)).coerceIn(0f, 1f)
            val active = movement > .035f || interaction > .16f
            if (!active) {
                if (buzzStreamId != 0) {
                    soundPool?.stop(buzzStreamId)
                    buzzStreamId = 0
                }
                return
            }
            val volume = (0.035f + movement * .16f + interaction * .045f).coerceIn(.035f, .24f)
            if (buzzStreamId == 0) {
                buzzStreamId = soundPool?.play(buzzSoundId, volume, volume, 1, -1, 1.0f) ?: 0
            } else {
                soundPool?.setVolume(buzzStreamId, volume, volume)
            }
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE && buzzStreamId != 0) {
                soundPool?.stop(buzzStreamId)
                buzzStreamId = 0
            }
        }

        override fun onDetachedFromWindow() {
            if (buzzStreamId != 0) {
                soundPool?.stop(buzzStreamId)
                buzzStreamId = 0
            }
            soundPool?.release()
            soundPool = null
            soundReleased = true
            super.onDetachedFromWindow()
        }

        private fun gaussian(d: Float, radius: Float): Float {
            return exp((-(d * d) / (2f * radius * radius)).toDouble()).toFloat().coerceIn(0f, 1f)
        }

        private fun stimulusIntensity(enabled: Boolean, sx: Float, sy: Float, sigma: Float): Float {
            if (!enabled) return 0f
            val d = hypot(sx - flyX, sy - flyY)
            return gaussian(d, sigma)
        }

        /**
         * Tarsal gustation is a contact sensor, not a second long-range odor
         * field. The two virtual anterior tarsi are body-relative environmental
         * sampling points. They only drive the retained gustatory sensory
         * population when the food surface is physically close to a fore-tarsus.
         *
         * This is an environment/sensor interface, not a feeding command: no
         * motor state, heading, reward or food position is written here.
         */
        private fun tarsalFoodContactIntensity(sx: Float, sy: Float): Float {
            if (!foodOn) return 0f
            val ca = cos(heading)
            val sa = sin(heading)
            val forward = .030f
            val halfSpacing = .022f
            var best = 0f
            for (side in intArrayOf(-1, 1)) {
                val tx = flyX + ca * forward - sa * (halfSpacing * side)
                val ty = flyY + sa * forward + ca * (halfSpacing * side)
                val d = hypot(sx - tx, sy - ty)
                if (d <= FOOD_TARSAL_CONTACT_RADIUS) {
                    best = max(best, gaussian(d, FOOD_GUSTATORY_SIGMA))
                }
            }
            return best.coerceIn(0f, 1f)
        }

        private fun setMappedSensoryRate(indices: IntArray, rateHz: Float) {
            if (indices.isEmpty() || rateHz <= 0f) return
            val bounded = rateHz.coerceIn(0f, 260f)
            for (index in indices) externalRateHz[index] = bounded
        }

        private fun xorshiftUnit(index: Int): Float {
            var x = rngState[index]
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            rngState[index] = if (x == 0L) 1L else x
            val bits = (x ushr 32) and 0xffffffffL
            return bits.toFloat() / 4294967296f
        }

        private fun poissonEvent(index: Int, dt: Float): Boolean {
            val rate = externalRateHz[index]
            if (rate <= 0f) return false
            val probability = (-Math.expm1((-rate * dt).toDouble())).toFloat()
            return xorshiftUnit(index) < probability
        }

        private fun injectOlfactoryPopulation(enabled: Boolean, sx: Float, sy: Float, maxRateHz: Float) {
            if (!enabled) {
                olfInputLeftCache = 0f
                olfInputCenterCache = 0f
                olfInputRightCache = 0f
                olfInputFrontCache = 0f
                olfInputRearCache = 0f
                foodDirectionalBias = 0f
                foodDrive = 0f
                return
            }
            val left = antennaOdorConcentration(sx, sy, -1)
            val right = antennaOdorConcentration(sx, sy, 1)
            val center = (left + right) * .5f
            for (i in olfactoryNeuronIndices) {
                val concentration = when (olfactorySide[i].toInt()) {
                    -1 -> left
                    1 -> right
                    else -> center
                }
                externalRateHz[i] = (concentration * maxRateHz).coerceIn(0f, 260f)
            }
            olfInputLeftCache = left
            olfInputCenterCache = center
            olfInputRightCache = right
            olfInputFrontCache = 0f
            olfInputRearCache = 0f
            foodDirectionalBias = ((left - right) / (left + right + .001f)).coerceIn(-1f, 1f)
            foodDrive = center
        }

        private fun sense(dt: Float) {
            // V1.18: environmental inputs are rate encoders, not voltage kicks.
            // They are converted to Poisson spike trains in stepBrainSubstep(),
            // matching the event-based external stimulation used by the reference LIF model.
            java.util.Arrays.fill(externalRateHz, 0f)
            // Gustation is contact-gated: long-range food attraction belongs to
            // olfaction. The retained gustatory receptors are driven only when
            // the food surface reaches the virtual anterior tarsi.
            val foodGustatoryIntensity = tarsalFoodContactIntensity(foodX, foodY) *
                2.35f * (1f - satiety * .35f)
            val lightIntensity = stimulusIntensity(lightOn, lightX, lightY, .48f) * 1.55f
            val dangerBaseIntensity = stimulusIntensity(dangerOn, dangerX, dangerY, .48f) * 2.15f

            val dangerDistance = hypot(dangerX - flyX, dangerY - flyY)
            val approachRate = if (dangerOn && previousDangerDistance.isFinite()) {
                ((previousDangerDistance - dangerDistance) / dt.coerceAtLeast(.001f)).coerceAtLeast(0f)
            } else 0f
            dangerLoom = if (dangerOn) (approachRate / .35f).coerceIn(0f, 1f) else 0f
            previousDangerDistance = if (dangerOn) dangerDistance else Float.NaN
            val visualThreatIntensity = dangerBaseIntensity * (.45f + .80f * dangerLoom)
            val combinedVisualIntensity = (lightIntensity + visualThreatIntensity).coerceIn(0f, 3.5f)

            setMappedSensoryRate(visualReceptorIndices, combinedVisualIntensity / 3.5f * SENSORY_VIS_MAX_HZ)
            injectOlfactoryPopulation(foodOn, foodX, foodY, FOOD_OLF_MAX_HZ)
            setMappedSensoryRate(gustatoryReceptorIndices, foodGustatoryIntensity / 2.35f * 180f)

            val wall = min(min(flyX - .06f, .94f - flyX), min(flyY - .10f, .79f - flyY)).coerceIn(0f, .4f)
            val wallSignal = (1f - wall / .4f).coerceIn(0f, 1f)
            wallDistanceCache = wall
            wallSignalCache = wallSignal
            setMappedSensoryRate(mechanosensoryReceptorIndices, wallSignal * 80f)

            lightDrive = lightIntensity.coerceIn(0f, 1f)
            dangerDrive = visualThreatIntensity.coerceIn(0f, 1f)
            foodSignalDisplay = .90f * foodSignalDisplay + .10f * foodDrive
            lightSignalDisplay = .90f * lightSignalDisplay + .10f * lightDrive
            dangerSignalDisplay = .90f * dangerSignalDisplay + .10f * dangerDrive
            sensoryDisplay = .90f * sensoryDisplay + .10f * ((foodDrive + lightDrive + dangerDrive) / 3f)
        }

        private fun scheduleSpike(source: Int) {
            val slot = (pendingSpikeCursor + SYNAPTIC_DELAY_STEPS) % pendingSpikeSources.size
            val count = pendingSpikeCounts[slot]
            if (count >= pendingSpikeSources[slot].size) {
                throw IllegalStateException("cola sinaptica saturada en slot=$slot")
            }
            pendingSpikeSources[slot][count] = source
            pendingSpikeCounts[slot] = count + 1
        }

        private fun deliverDelayedSynapses() {
            val slot = pendingSpikeCursor
            val count = pendingSpikeCounts[slot]
            for (p in 0 until count) {
                val source = pendingSpikeSources[slot][p]
                val targets = outgoing[source]
                val weights = outgoingW[source]
                for (k in targets.indices) {
                    // Match Brian2's event semantics: on_pre still executes while
                    // the target is refractory. The (unless refractory) flag
                    // freezes membrane-potential integration, not synaptic
                    // event delivery. The synaptic state still decays with its
                    // published 5 ms time constant while the membrane is refractory.
                    val target = targets[k]
                    synConductance[target] += weights[k]
                }
            }
            pendingSpikeCounts[slot] = 0
        }

        private fun forceExternalSpike(index: Int) {
            fired[index] = true
            frameFired[index] = true
            v[index] = V_RESET
            synConductance[index] = 0f
            refractory[index] = 0f
            externalForced[index] = true
            scheduleSpike(index)
        }

        private fun stepBrainSubstep(dt: Float, applySensoryKick: Boolean): Int {
            deliverDelayedSynapses()

            // External receptor spikes are Poisson events. The event itself is a
            // forced spike, as in the reference PoissonInput formulation.
            if (applySensoryKick) {
                for (i in 0 until SENSOR_END) {
                    if (poissonEvent(i, dt)) forceExternalSpike(i)
                }
            }

            var stepSpikes = 0
            val a = exp((-dt / TAU_MEMBRANE_SECONDS).toDouble()).toFloat()
            val b = exp((-dt / TAU_SYNAPSE_SECONDS).toDouble()).toFloat()
            val coupling = TAU_SYNAPSE_SECONDS / (TAU_MEMBRANE_SECONDS - TAU_SYNAPSE_SECONDS)

            for (i in 0 until N) {
                if (externalForced[i]) {
                    externalForced[i] = false
                    stepSpikes++
                    continue
                }
                if (refractory[i] > 0f) {
                    fired[i] = false
                    // Match the reference Brian2 refractory semantics used by
                    // Shiu et al.: refractory neurons do not integrate either
                    // membrane or synaptic state until the refractory interval
                    // has elapsed. Presynaptic events may still be delivered to
                    // the postsynaptic conductance before this gate, exactly as
                    // the event queue defines; the state is then held here.
                    lastSynDrive[i] = synConductance[i]
                    refractory[i] = (refractory[i] - dt).coerceAtLeast(0f)
                    continue
                }

                fired[i] = false
                val g0 = synConductance[i]
                lastSynDrive[i] = g0
                val x0 = v[i] - V_REST
                v[i] = V_REST + x0 * a + g0 * coupling * (a - b)
                synConductance[i] = g0 * b

                if (v[i] > V_THRESHOLD) {
                    fired[i] = true
                    frameFired[i] = true
                    stepSpikes++
                    v[i] = V_RESET
                    // Match the reference implementation exactly: a spike resets
                    // both membrane potential and the alpha-synapse state of the
                    // spiking neuron. Future presynaptic events rebuild g after the
                    // published synaptic delay.
                    synConductance[i] = 0f
                    refractory[i] = REFRACTORY_SECONDS
                    scheduleSpike(i)
                }
            }

            pendingSpikeCursor = (pendingSpikeCursor + 1) % pendingSpikeSources.size
            return stepSpikes
        }

        private fun resetMotorSubstepAccumulators() {
            java.util.Arrays.fill(motorSubstepSpikeCounts, 0)
            java.util.Arrays.fill(motorSynDriveSum, 0f)
            java.util.Arrays.fill(motorSynDrivePeak, 0f)
        }

        private fun accumulateNeuralSubstepDiagnostics() {
            // Count actual ORN spikes by annotated side. The sensory-input
            // concentration is displayed separately; this is downstream neural
            // output measured after the LIF update, not the injected rate.
            for (i in olfactoryNeuronIndices) {
                if (!fired[i]) continue
                when (olfactorySide[i].toInt()) {
                    -1 -> olfWindowSpikesLeft++
                    1 -> olfWindowSpikesRight++
                    else -> olfWindowSpikesUnknown++
                }
            }
            for (i in DESC_START until DESC_END) {
                if (fired[i]) dnWindowSpikes[i - DESC_START]++
            }
            for (i in MOTOR_START until MOTOR_END) {
                val mi = i - MOTOR_START
                if (fired[i]) {
                    motorWindowSpikes[mi]++
                    motorSubstepSpikeCounts[mi]++
                }
                motorSynDriveSum[mi] += lastSynDrive[i]
                motorSynDrivePeak[mi] = max(motorSynDrivePeak[mi], abs(lastSynDrive[i]))
            }
            if (!foodOn && !lightOn && !dangerOn && !baselineReady) {
                for (i in DESC_START until DESC_END) {
                    if (fired[i]) dnBaselineSpikes[i - DESC_START]++
                }
                for (i in MOTOR_START until MOTOR_END) {
                    if (fired[i]) motorBaselineSpikes[i - MOTOR_START]++
                }
            }
        }

        private fun updateOuterNeuralState(dt: Float, totalSpikes: Int) {
            // These state variables are updated once per public 20 ms neural
            // frame, not once per internal 5 ms integration substep. They remain
            // diagnostics/internal state; the connectome and measured VNC output
            // remain the causal source of locomotion.
            explorationPhase += dt * (1.0f + explorationState * .25f)

            val sensoryNovelty = (abs(foodDrive - previousFoodDrive) +
                abs(lightDrive - previousLightDrive) +
                abs(dangerDrive - previousDangerDrive)).coerceIn(0f, 1f)

            val sensoryArousal = max(foodDrive, max(lightDrive, dangerDrive))
            val targetExploration = (.42f + .12f * sensoryNovelty + .06f * sensoryArousal).coerceIn(.15f, .75f)
            explorationState += dt * (.12f * (targetExploration - explorationState))
            explorationState = explorationState.coerceIn(.05f, .80f)

            val recentMotor = populationRate(MOTOR_START, MOTOR_END)
            motorActivityMemory = .94f * motorActivityMemory + .06f * recentMotor

            // V1.13: separate the experimentally described halt populations from
            // backward-walking DNs. In V1.11, role 3 mixed MDN/DNp09 with "halting";
            // that was anatomically incorrect. These readouts are diagnostics only.
            var fg = 0f
            var bb = 0f
            var brk = 0f
            for (i in 0 until N) {
                if (!fired[i]) continue
                when (haltRole[i].toInt()) {
                    1 -> fg += 1f
                    2 -> bb += 1f
                    3 -> brk += 1f
                }
            }
            var haltDen = 0
            var walkOffDen = 0
            var brakeDen = 0
            for (i in 0 until N) {
                when (haltRole[i].toInt()) {
                    1 -> walkOffDen++
                    2 -> walkOffDen++
                    3 -> brakeDen++
                }
            }
            val walkOffNow = if (walkOffDen > 0) ((fg + bb) / walkOffDen).coerceIn(0f, 1f) else 0f
            val brakeNow = if (brakeDen > 0) (brk / brakeDen).coerceIn(0f, 1f) else 0f
            val haltNow = max(walkOffNow, brakeNow)
            walkOffEvidenceDisplay += (walkOffNow - walkOffEvidenceDisplay) *
                (1f - exp((-dt / .12f).toDouble()).toFloat())
            brakeEvidenceDisplay += (brakeNow - brakeEvidenceDisplay) *
                (1f - exp((-dt / .12f).toDouble()).toFloat())
            haltEvidenceDisplay += (haltNow - haltEvidenceDisplay) *
                (1f - exp((-dt / .12f).toDouble()).toFloat())
            haltGateDisplay = haltEvidenceDisplay

            val threatDemand = dangerDrive.coerceIn(0f, 1f)
            val wakeDemand = max(threatDemand, lightDrive * .35f)

            // V1.12 HOMEOSTAT: this is a diagnostic/internal-state model of the
            // measured body state, not a controller. Published work describes
            // exponential homeostatic dynamics with time constants of minutes to
            // tens of minutes; no arbitrary percentage triggers a state change.
            val awakeTau = 600f
            val restTau = 1200f
            val currentlyWalking = recentMovementMemory > .025f
            if (currentlyWalking) {
                sleepPressure += (1f - sleepPressure) *
                    (1f - exp((-dt / awakeTau).toDouble()).toFloat())
            } else if (inactivityContinuous >= .25f) {
                sleepPressure += (0f - sleepPressure) *
                    (1f - exp((-dt / restTau).toDouble()).toFloat())
            }
            sleepPressure = sleepPressure.coerceIn(0f, 1f)

            // Arousal remains a separate state variable. It can increase during
            // threat/light, but never deletes accumulated homeostatic pressure.
            val arousalInput = (threatDemand * 1.8f + lightDrive * .35f + sensoryArousal * .12f)
                .coerceIn(0f, 2f)
            arousalDrive += dt * (.0065f * arousalInput - .0012f * arousalDrive)
            arousalDrive = arousalDrive.coerceIn(0f, 1f)

            // The behavioral state is read from the actual body output later in
            // driveBody(). There is intentionally no RNG-based REST transition.
            restStateBlend = if (inactivityContinuous >= .25f) {
                (restStateBlend + dt / .35f).coerceAtMost(1f)
            } else {
                (restStateBlend - dt / .35f).coerceAtLeast(0f)
            }
            restState = inactivityContinuous >= .25f

            previousFoodDrive = foodDrive
            previousLightDrive = lightDrive
            previousDangerDrive = dangerDrive

            if (!foodOn && !lightOn && !dangerOn && !baselineReady) {
                baselineCaptureSeconds += dt
                if (baselineCaptureSeconds >= 5f) baselineReady = true
            }

            // PHASE 2B: all spike-rate windows are expressed in the public
            // 20 ms frame, while their spike counts include every 5 ms substep.
            spikesLastStep = totalSpikes
            spikeWindowCount += totalSpikes
            spikeWindowTime += dt
            if (spikeWindowTime >= 1f) {
                spikesPerSecond = spikeWindowCount.toFloat() / spikeWindowTime
                spikeWindowCount = 0
                spikeWindowTime = 0f
            }
            // PHASE 2B visual correction: keep presentation memory at the original
            // 20 ms cadence. `frameFired` latches spikes from all 40 internal 0.5 ms substeps,
            // then this legacy 0.88/0.22 update is applied exactly once per frame.
            for (i in 0 until N) {
                visualActivity[i] = (visualActivity[i] * .88f +
                    if (frameFired[i]) .22f else 0f).coerceIn(0f, 1f)
                frameFired[i] = false
            }
            var activeVisual = 0f
            for (i in 0 until N) {
                if (visualActivity[i] > activeVisual) activeVisual = visualActivity[i]
            }
            maxDisplayedActivity = activeVisual

            // Pipeline diagnostics remain read-only and refresh once per public frame.
            val sensoryRate = populationRate(VIS_START, MECH_END)
            val centralRateNow = populationRate(OTHER_START, OTHER_END)
            val descRateNow = populationRate(DESC_START, DESC_END)
            val motorRateNow = populationRate(MOTOR_START, MOTOR_END)
            val drivePeak = if (SENSOR_END > 0) {
                var peak = 0f
                for (i in 0 until SENSOR_END) peak = max(peak, externalRateHz[i])
                peak
            } else 0f
            val diagTau = 1f - exp((-dt / .10f).toDouble()).toFloat()
            sensorySpikesDisplay += (sensoryRate - sensorySpikesDisplay) * diagTau
            centralSpikesDisplay += (centralRateNow - centralSpikesDisplay) * diagTau
            descendingSpikesDisplay += (descRateNow - descendingSpikesDisplay) * diagTau
            motorSpikesDisplay += (motorRateNow - motorSpikesDisplay) * diagTau
            sensoryDriveDisplay += (drivePeak - sensoryDriveDisplay) * diagTau
            diagnosticRefreshClock += dt
            historyClock += dt
            if (historyClock >= 0.10f) {
                historyClock -= 0.10f
                historyNeural[historyCursor] = centralSpikesDisplay
                historyDn[historyCursor] = descendingSpikesDisplay
                historyMotor[historyCursor] = motorSpikesDisplay
                historyCursor = (historyCursor + 1) % historyNeural.size
            }
            diagWindowElapsed += dt
            if (diagWindowElapsed >= diagWindowSeconds) {
                refreshNodeDiagnostics(diagWindowElapsed)
                diagWindowElapsed = 0f
                java.util.Arrays.fill(dnWindowSpikes, 0)
                java.util.Arrays.fill(motorWindowSpikes, 0)
            }
        }

        private fun refreshNodeDiagnostics(windowSeconds: Float) {
            dnSpikingCount = 0
            motorSpikingCount = 0
            java.util.Arrays.fill(topDnIds, -1)
            java.util.Arrays.fill(topDnHz, 0f)
            java.util.Arrays.fill(topDnDelta, 0f)
            java.util.Arrays.fill(topDnVm, V_REST)
            java.util.Arrays.fill(topDnWindowSpikes, 0)
            java.util.Arrays.fill(topMotorIds, -1)
            java.util.Arrays.fill(topMotorHz, 0f)
            java.util.Arrays.fill(topMotorDelta, 0f)
            java.util.Arrays.fill(topMotorVm, V_REST)
            java.util.Arrays.fill(topMotorWindowSpikes, 0)

            val safeWindow = max(0.001f, windowSeconds)
            val invWindow = 1f / safeWindow
            val baselineInv = if (baselineReady && baselineCaptureSeconds > 0f) 1f / baselineCaptureSeconds else 0f

            val olfLeftN = olfactoryNeuronIndices.count { olfactorySide[it].toInt() == -1 }.coerceAtLeast(1)
            val olfRightN = olfactoryNeuronIndices.count { olfactorySide[it].toInt() == 1 }.coerceAtLeast(1)
            olfLeftHz = olfWindowSpikesLeft * invWindow / olfLeftN.toFloat()
            olfRightHz = olfWindowSpikesRight * invWindow / olfRightN.toFloat()
            olfWindowSpikesLeft = 0
            olfWindowSpikesRight = 0
            olfWindowSpikesUnknown = 0

            var dnLeftCount = 0
            var dnRightCount = 0
            var dnLeftSpikes = 0
            var dnRightSpikes = 0
            for (i in DESC_START until DESC_END) {
                when (nodeSide[i].toInt()) {
                    -1 -> { dnLeftCount++; dnLeftSpikes += dnWindowSpikes[i - DESC_START] }
                    1 -> { dnRightCount++; dnRightSpikes += dnWindowSpikes[i - DESC_START] }
                }
            }
            dnLeftHz = if (dnLeftCount == 0) 0f else dnLeftSpikes * invWindow / dnLeftCount.toFloat()
            dnRightHz = if (dnRightCount == 0) 0f else dnRightSpikes * invWindow / dnRightCount.toFloat()

            var legLeftSpikes = 0
            var legRightSpikes = 0
            for (i in MOTOR_START until MOTOR_END) {
                if (motorRole[i].toInt() != GeneratedConnectomeMeta.MOTOR_LEG) continue
                when (nodeSide[i].toInt()) {
                    -1 -> legLeftSpikes += motorWindowSpikes[i - MOTOR_START]
                    1 -> legRightSpikes += motorWindowSpikes[i - MOTOR_START]
                }
            }
            legLeftHz = if (legLeftTotal == 0) 0f else legLeftSpikes * invWindow / legLeftTotal.toFloat()
            legRightHz = if (legRightTotal == 0) 0f else legRightSpikes * invWindow / legRightTotal.toFloat()

            fun offer(ids: IntArray, hz: FloatArray, delta: FloatArray, vm: FloatArray, spikes: IntArray, id: Int, currentHz: Float, baseHz: Float) {
                if (currentHz <= 0f) return
                var pos = -1
                for (k in ids.indices) {
                    if (ids[k] == id) return
                    if (pos < 0 && currentHz > hz[k]) pos = k
                }
                if (pos < 0) return
                for (k in ids.lastIndex downTo pos + 1) {
                    ids[k] = ids[k - 1]
                    hz[k] = hz[k - 1]
                    delta[k] = delta[k - 1]
                    vm[k] = vm[k - 1]
                    spikes[k] = spikes[k - 1]
                }
                ids[pos] = id
                hz[pos] = currentHz
                delta[pos] = currentHz - baseHz
                vm[pos] = v[id]
                spikes[pos] = if (id in DESC_START until DESC_END) dnWindowSpikes[id - DESC_START] else motorWindowSpikes[id - MOTOR_START]
            }

            for (i in DESC_START until DESC_END) {
                val n = dnWindowSpikes[i - DESC_START]
                if (n > 0) dnSpikingCount++
                val hz = n * invWindow
                val baseHz = if (baselineReady) dnBaselineSpikes[i - DESC_START] * baselineInv else 0f
                offer(topDnIds, topDnHz, topDnDelta, topDnVm, topDnWindowSpikes, i, hz, baseHz)
            }
            for (i in MOTOR_START until MOTOR_END) {
                val n = motorWindowSpikes[i - MOTOR_START]
                if (n > 0) motorSpikingCount++
                val hz = n * invWindow
                val baseHz = if (baselineReady) motorBaselineSpikes[i - MOTOR_START] * baselineInv else 0f
                offer(topMotorIds, topMotorHz, topMotorDelta, topMotorVm, topMotorWindowSpikes, i, hz, baseHz)
            }
        }

        private fun dnRoleLabel(role: Int): String = when (role) {
            1 -> "FWD"
            2 -> "TURN"
            3 -> "BACK"
            4 -> "ESC"
            else -> "DN"
        }

        private fun motorRoleLabel(role: Int): String = when (role) {
            GeneratedConnectomeMeta.MOTOR_LEG -> "LEG"
            GeneratedConnectomeMeta.MOTOR_WING -> "WING"
            GeneratedConnectomeMeta.MOTOR_HALTERE -> "HALT"
            GeneratedConnectomeMeta.MOTOR_NECK -> "NECK"
            GeneratedConnectomeMeta.MOTOR_ABDOMEN -> "ABD"
            else -> "MOTOR"
        }

        private fun count(a: Int, b: Int): Int {
            var n = 0
            for (i in a until b) if (fired[i]) n++
            return n
        }

        private fun populationRate(a: Int, b: Int): Float {
            return count(a, b).toFloat() / (b - a).toFloat()
        }

        private fun olfactoryPopulationRate(): Float {
            if (olfactoryNeuronIndices.isEmpty()) return 0f
            var firedCount = 0
            for (i in olfactoryNeuronIndices) if (fired[i]) firedCount++
            return firedCount.toFloat() / olfactoryNeuronIndices.size.toFloat()
        }

        private fun mechanosensoryPopulationRate(): Float {
            val total = (MECH_END - MECH_START) - olfactoryNeuronIndices.count { it in MECH_START until MECH_END }
            if (total <= 0) return 0f
            var firedCount = 0
            for (i in MECH_START until MECH_END) {
                if (!isOlfactoryNeuron(i) && fired[i]) firedCount++
            }
            return firedCount.toFloat() / total.toFloat()
        }

        private fun descendingRoleRate(role: Int): Float {
            var firedCount = 0
            var total = 0
            for (i in DESC_START until DESC_END) {
                if (descendingRole[i].toInt() == role) {
                    total++
                    if (fired[i]) firedCount++
                }
            }
            return if (total == 0) 0f else firedCount.toFloat() / total.toFloat()
        }

        // V1.04: competitive action readout. Each action requires measured
        // neural evidence first; sensory context only gates that evidence.
        // This keeps the causal direction: sensory input -> connectome activity
        // -> action population -> measured VNC motor output.
        private fun updateActionSelection(
            dt: Float,
            visualRate: Float,
            olfactoryRate: Float,
            gustatoryRate: Float,
            mechanosensoryRate: Float,
            motorRate: Float
        ) {
            val dnForward = descendingRoleRate(1)
            val dnTurn = descendingRoleRate(2)
            val dnBackward = descendingRoleRate(3)
            val dnEscape = descendingRoleRate(4)

            // Connectome-derived activity of intermediate cells carrying the
            // measured two-hop route metadata. This is a readout, not a command.
            var forwardRouteActivity = 0f
            var turnRouteActivity = 0f
            var escapeRouteActivity = 0f
            var routeCount = 0
            for (i in 0 until N) {
                if (!fired[i]) continue
                // Route scores are intended for intermediate circuit cells, not
                // the sensory/DN/MN readout populations themselves.
                if (i < SENSOR_END || i in DESC_START until DESC_END || i in MOTOR_START until MOTOR_END) continue
                if (routeForward[i] > 0f || routeTurn[i] > 0f || routeEscape[i] > 0f) {
                    forwardRouteActivity += routeForward[i]
                    turnRouteActivity += routeTurn[i]
                    escapeRouteActivity += routeEscape[i]
                    routeCount++
                }
            }
            if (routeCount > 0) {
                val inv = 1f / routeCount.toFloat()
                forwardRouteActivity = (forwardRouteActivity * inv).coerceIn(0f, 1f)
                turnRouteActivity = (turnRouteActivity * inv).coerceIn(0f, 1f)
                escapeRouteActivity = (escapeRouteActivity * inv).coerceIn(0f, 1f)
            }
            forwardRouteActivityDisplay = .82f * forwardRouteActivityDisplay + .18f * forwardRouteActivity
            turnRouteActivityDisplay = .82f * turnRouteActivityDisplay + .18f * turnRouteActivity
            escapeRouteActivityDisplay = .82f * escapeRouteActivityDisplay + .18f * escapeRouteActivity

            val foodContext = ((olfactoryRate * .72f + gustatoryRate * .28f) *
                (1f - satiety * .35f)).coerceIn(0f, 1f)
            val threatContext = (mechanosensoryRate * .35f + dangerDrive * .65f).coerceIn(0f, 1f)
            val visualContext = visualRate.coerceIn(0f, 1f)

            // Forward/approach is now separated. Walking evidence alone is not
            // enough: approach also needs an olfactory/gustatory target signal.
            val approachNeural = (dnForward * .45f + forwardRouteActivity * .35f +
                forwardMotor * .20f).coerceIn(0f, 1f)
            val approachContext = foodContext
            val approach = (approachNeural * approachContext *
                (1f - threatContext * .90f)).coerceIn(0f, 1f)

            // Escape uses explicit escape-labelled DN activity, measured escape-route
            // intermediates, and current motor evidence. The motor term is no longer
            // equated with jump activity alone.
            val escapeNeural = (dnEscape * .48f + escapeRouteActivity * .34f +
                escapeMotor * .18f).coerceIn(0f, 1f)
            val escapeContext = (threatContext * (.55f + .45f * dangerLoom)).coerceIn(0f, 1f)
            val escape = (escapeNeural * escapeContext).coerceIn(0f, 1f)

            // Turning/orientation uses turning DN evidence and visual route activity.
            val orientNeural = (dnTurn * .50f + turnRouteActivity * .35f +
                ((turnLeftAction + turnRightAction) * .15f)).coerceIn(0f, 1f)
            val orientContext = visualContext
            val orient = (orientNeural * (0.20f + .80f * orientContext) *
                (1f - escape * .75f)).coerceIn(0f, 1f)

            val external = max(foodContext, threatContext)
            val exploreRaw = (dnForward * .20f + dnTurn * .20f +
                forwardRouteActivity * .15f + turnRouteActivity * .15f +
                exploreMotor * .30f) * (1f - external * .70f)
            val explore = (exploreRaw * (1f - escape * .85f)).coerceIn(0f, 1f)

            // Brake is a readout of the measured FG/BB/BRK halt populations
            // plus residual low locomotor output; it is never a body command.
            val brakeRaw = (haltEvidenceDisplay * .80f +
                (1f - legActivityProxy()) * motorRate * .20f).coerceIn(0f, 1f)
            val brake = (brakeRaw * (1f + threatContext * .45f) *
                (1f - escape * .55f)).coerceIn(0f, 1f)

            val tau = 1f - exp((-dt / .12f).toDouble()).toFloat()
            approachAction += (approach - approachAction) * tau
            escapeAction += (escape - escapeAction) * tau
            orientAction += (orient - orientAction) * tau
            exploreAction += (explore - exploreAction) * tau
            brakeAction += (brake - brakeAction) * tau

            turnLeftAction += ((leftMotor - turnLeftAction) * tau).coerceIn(-.08f, .08f)
            turnRightAction += ((rightMotor - turnRightAction) * tau).coerceIn(-.08f, .08f)
        }

        // Cached proxy is updated from actual motor activity in driveBody.
        // Activation states below are driven exclusively by measured VNC MN spikes.
        private var legActivityCache = 0f
        private var jumpActivityCacheValue = 0f
        private var legActivationState = 0f
        private var leftLegActivationState = 0f
        private var rightLegActivationState = 0f
        private var wingActivationState = 0f
        private var neckActivationState = 0f
        private var jumpActivationState = 0f
        private var abdomenActivationState = 0f

        private fun relaxMotorActivation(previous: Float, target: Float, dt: Float): Float {
            val alpha = (1f - exp((-dt / MOTOR_ACTIVATION_TAU_SECONDS).toDouble()).toFloat()).coerceIn(0f, 1f)
            return previous + (target - previous) * alpha
        }

        private fun legActivityProxy(): Float = legActivationState
        private fun jumpActivityCache(): Float = jumpActivationState

        private fun learn(reward: Float, dt: Float) {
            // Synapses are fixed connectome measurements in this release. The old
            // reward-dependent weight update was disabled but still contained a
            // dormant code path; keeping it out of the runtime removes any risk
            // that motor weights diverge from FBD105 during an experiment.
            val r = reward.coerceIn(-1f, 1f)
            memoryTrace = (memoryTrace * exp((-dt * .035f).toDouble()).toFloat() + abs(r) * .03f).coerceIn(0f, 1f)
            lastReward = .94f * lastReward + .06f * r
        }

        private fun driveBody(dt: Float) {
            val motorCount = MOTOR_END - MOTOR_START
            if (motorCount <= 0) return

            // Motor output is read from the curated VNC motor neurons only.
            // The role (leg/wing/haltere/neck/abdomen/jump) comes from the
            // published motor annotations generated by build_connectome.py;
            // neuron indices are never used to invent a motor function.
            var leftLeg = 0f
            var rightLeg = 0f
            var legActivity = 0f
            var wingActivity = 0f
            var neckActivity = 0f
            var jumpActivity = 0f
            var abdomenActivity = 0f
            var allMotor = 0f
            var legActive = 0
            var leftLegActive = 0
            var rightLegActive = 0
            var unknownLegActive = 0
            var legSpikeEvents = 0
            var leftLegSpikeEvents = 0
            var rightLegSpikeEvents = 0
            var unknownLegSpikeEvents = 0
            var wingSpikeEvents = 0
            var neckSpikeEvents = 0
            var jumpSpikeEvents = 0
            var abdomenSpikeEvents = 0
            var haltereSpikeEvents = 0
            var motorOtherSpikeEvents = 0
            var allMotorSpikeEvents = 0
            var wingActive = 0
            var neckActive = 0
            var jumpActive = 0
            var abdomenActive = 0
            var haltereActive = 0
            var motorOtherActive = 0
            var motorDriveSigned = 0f
            var motorDriveAbs = 0f
            var motorDriveAbsPeak = 0f
            var legDriveSigned = 0f
            var wingDriveSigned = 0f
            var neckDriveSigned = 0f
            var abdomenDriveSigned = 0f
            var haltereDriveSigned = 0f
            var otherDriveSigned = 0f
            var leftLegDrive = 0f
            var rightLegDrive = 0f

            for (i in MOTOR_START until MOTOR_END) {
                val mi = i - MOTOR_START
                val drive = motorSynDriveSum[mi] / NEURAL_SUBSTEPS_PER_FRAME.toFloat()
                motorDriveSigned += drive
                motorDriveAbs += abs(drive)
                motorDriveAbsPeak = max(motorDriveAbsPeak, motorSynDrivePeak[mi])
                when (motorRole[i].toInt()) {
                    GeneratedConnectomeMeta.MOTOR_LEG -> {
                        legDriveSigned += drive
                        when (nodeSide[i].toInt()) {
                            -1 -> leftLegDrive += drive
                            1 -> rightLegDrive += drive
                        }
                    }
                    GeneratedConnectomeMeta.MOTOR_WING -> wingDriveSigned += drive
                    GeneratedConnectomeMeta.MOTOR_NECK -> neckDriveSigned += drive
                    GeneratedConnectomeMeta.MOTOR_ABDOMEN -> abdomenDriveSigned += drive
                    GeneratedConnectomeMeta.MOTOR_HALTERE -> haltereDriveSigned += drive
                    GeneratedConnectomeMeta.MOTOR_OTHER -> otherDriveSigned += drive
                }
                val motorSpikes = motorSubstepSpikeCounts[i - MOTOR_START]
                if (motorSpikes <= 0) continue
                allMotorSpikeEvents += motorSpikes
                when (motorRole[i].toInt()) {
                    GeneratedConnectomeMeta.MOTOR_LEG -> {
                        legActive++
                        legSpikeEvents += motorSpikes
                        when (nodeSide[i].toInt()) {
                            -1 -> { leftLegActive++; leftLegSpikeEvents += motorSpikes }
                            1 -> { rightLegActive++; rightLegSpikeEvents += motorSpikes }
                            else -> { unknownLegActive++; unknownLegSpikeEvents += motorSpikes }
                        }
                    }
                    GeneratedConnectomeMeta.MOTOR_WING -> { wingActive++; wingSpikeEvents += motorSpikes }
                    GeneratedConnectomeMeta.MOTOR_NECK -> { neckActive++; neckSpikeEvents += motorSpikes }
                    GeneratedConnectomeMeta.MOTOR_ABDOMEN -> { abdomenActive++; abdomenSpikeEvents += motorSpikes }
                    GeneratedConnectomeMeta.MOTOR_HALTERE -> { haltereActive++; haltereSpikeEvents += motorSpikes }
                    GeneratedConnectomeMeta.MOTOR_OTHER -> { motorOtherActive++; motorOtherSpikeEvents += motorSpikes }
                }
                if (motorFunctionalTag[i].toInt() == GeneratedConnectomeMeta.MOTOR_FUNCTION_JUMP) {
                    jumpActive++
                    jumpSpikeEvents += motorSpikes
                }
            }

            val legTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_LEG]
            val wingTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_WING]
            val neckTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_NECK]
            val abdomenTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_ABDOMEN]
            val haltereTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_HALTERE]
            val jumpTotal = jumpLeftTotal + jumpRightTotal
            // Body readout is now based on mean spike rate across each anatomical
            // motor population, not a boolean "fired at least once in 20 ms".
            // One spike in a 20 ms frame corresponds to 50 Hz.
            val invFrame = 1f / dt.coerceAtLeast(.001f)
            val legRateHz = if (legTotal == 0) 0f else legSpikeEvents * invFrame / legTotal.toFloat()
            val leftLegRateHz = if (legLeftTotal == 0) 0f else leftLegSpikeEvents * invFrame / legLeftTotal.toFloat()
            val rightLegRateHz = if (legRightTotal == 0) 0f else rightLegSpikeEvents * invFrame / legRightTotal.toFloat()
            val unknownLegRateHz = if (legUnknownSideTotal == 0) 0f else unknownLegSpikeEvents * invFrame / legUnknownSideTotal.toFloat()
            val wingRateHz = if (wingTotal == 0) 0f else wingSpikeEvents * invFrame / wingTotal.toFloat()
            val neckRateHz = if (neckTotal == 0) 0f else neckSpikeEvents * invFrame / neckTotal.toFloat()
            val jumpRateHz = if (jumpTotal == 0) 0f else jumpSpikeEvents * invFrame / jumpTotal.toFloat()
            val abdomenRateHz = if (abdomenTotal == 0) 0f else abdomenSpikeEvents * invFrame / abdomenTotal.toFloat()
            val haltereRateHz = if (haltereTotal == 0) 0f else haltereSpikeEvents * invFrame / haltereTotal.toFloat()
            val otherRateHz = if (motorRoleTotals[GeneratedConnectomeMeta.MOTOR_OTHER] == 0) 0f else motorOtherSpikeEvents * invFrame / motorRoleTotals[GeneratedConnectomeMeta.MOTOR_OTHER].toFloat()
            // Raw firing rates are the neural readout. The actuator states are
            // a short physical integration of that measured spike output, avoiding
            // a single-spike = instantaneous-force discontinuity every 20 ms.
            val rawLegActivity = (legRateHz / 50f).coerceIn(0f, 1f)
            val rawLeftLeg = (leftLegRateHz / 50f).coerceIn(0f, 1f)
            val rawRightLeg = (rightLegRateHz / 50f).coerceIn(0f, 1f)
            val rawWingActivity = (wingRateHz / 50f).coerceIn(0f, 1f)
            val rawNeckActivity = (neckRateHz / 50f).coerceIn(0f, 1f)
            val rawJumpActivity = (jumpRateHz / 50f).coerceIn(0f, 1f)
            val rawAbdomenActivity = (abdomenRateHz / 50f).coerceIn(0f, 1f)

            legActivationState = relaxMotorActivation(legActivationState, rawLegActivity, dt)
            leftLegActivationState = relaxMotorActivation(leftLegActivationState, rawLeftLeg, dt)
            rightLegActivationState = relaxMotorActivation(rightLegActivationState, rawRightLeg, dt)
            wingActivationState = relaxMotorActivation(wingActivationState, rawWingActivity, dt)
            neckActivationState = relaxMotorActivation(neckActivationState, rawNeckActivity, dt)
            jumpActivationState = relaxMotorActivation(jumpActivationState, rawJumpActivity, dt)
            abdomenActivationState = relaxMotorActivation(abdomenActivationState, rawAbdomenActivity, dt)

            // Public activity variables remain the measured, actuator-filtered
            // outputs. The raw spike counters and top-neuron telemetry remain the
            // neural evidence used for auditing.
            legActivity = legActivationState
            leftLeg = leftLegActivationState
            rightLeg = rightLegActivationState
            wingActivity = wingActivationState
            neckActivity = neckActivationState
            jumpActivity = jumpActivationState
            abdomenActivity = abdomenActivationState
            abdomenActiveCache = abdomenActive
            haltereActiveCache = haltereActive
            motorOtherActiveCache = motorOtherActive
            unresolvedMotorActiveCache = motorOtherActive
            val motorCountF = motorCount.toFloat().coerceAtLeast(1f)
            motorDriveSignedCache = motorDriveSigned / motorCountF
            motorDriveAbsCache = motorDriveAbs / motorCountF
            motorDriveAbsPeakCache = motorDriveAbsPeak
            legDriveSignedCache = if (legTotal == 0) 0f else legDriveSigned / legTotal.toFloat()
            wingDriveSignedCache = if (wingTotal == 0) 0f else wingDriveSigned / wingTotal.toFloat()
            neckDriveSignedCache = if (neckTotal == 0) 0f else neckDriveSigned / neckTotal.toFloat()
            abdomenDriveSignedCache = if (abdomenTotal == 0) 0f else abdomenDriveSigned / abdomenTotal.toFloat()
            haltereDriveSignedCache = if (haltereTotal == 0) 0f else haltereDriveSigned / haltereTotal.toFloat()
            otherMotorDriveSignedCache = if (motorRoleTotals[GeneratedConnectomeMeta.MOTOR_OTHER] == 0) 0f else otherDriveSigned / motorRoleTotals[GeneratedConnectomeMeta.MOTOR_OTHER].toFloat()
            leftLegDriveSignedCache = if (legLeftTotal == 0) 0f else leftLegDrive / legLeftTotal.toFloat()
            rightLegDriveSignedCache = if (legRightTotal == 0) 0f else rightLegDrive / legRightTotal.toFloat()
            @Suppress("UNUSED_VARIABLE") val retainedUnknownLegRateHz = unknownLegRateHz

            legActiveCache = legActive
            leftLegActiveCache = leftLegActive
            rightLegActiveCache = rightLegActive
            unknownLegActiveCache = unknownLegActive
            wingActiveCache = wingActive
            jumpActiveCache = jumpActive
            wingActivityCache = wingActivity

            // V1.13: behavioural pause/sleep evidence is derived from actual VNC
            // motor output. No random timer declares a pause. A pause starts only
            // after the body has genuinely become nearly immobile.
            // V1.13: locomotion is a physical readout. Neural leg activity is kept
            // as a diagnostic but cannot by itself declare that the body moved.
            // Pause/homeostasis evidence is based on the measured body state, not
            // on a count of firing leg neurons.
            val bodySpeed = physicalSpeed
            val wasActuallyMoving = physicalMovementMemory > .02f
            if (bodySpeed <= 0.00035f) {
                inactivityContinuous += dt
            } else {
                if (pauseDetected) lastPauseDuration = inactivityContinuous
                inactivityContinuous = 0f
            }
            val wasPause = pauseDetected
            val wasStop = stopDetected
            // 250 ms is the operational stopping-bout threshold used in recent
            // connectome-informed halting experiments; >1 s is also widely used
            // as a pause criterion in free-walking assays.
            pauseDetected = inactivityContinuous >= .25f && wasActuallyMoving
            stopDetected = inactivityContinuous >= 1.0f && wasActuallyMoving
            pauseTimer = if (pauseDetected) inactivityContinuous else 0f
            if (!wasPause && pauseDetected) pauseCount++
            if (!wasStop && stopDetected) {
                // A stop is a behavioral event; it is not a neural command.
            }

            // Body mechanics are deliberately simple, but every locomotor command
            // originates from measured VNC motor activity. Left/right asymmetry in
            // leg and neck output changes heading; leg output supplies walking force.
            val rawTurn = ((rightLeg - leftLeg) + (neckActivity * 0.22f)) * 1.55f
            // Slow turn-bias normalization is based only on measured body state.
            // Stimulus identity is deliberately absent, so no sensory condition can
            // inject a direct turn bias into body mechanics.
            val bodyQuiescent = physicalSpeed < .00035f && legActivity < .01f
            if (bodyQuiescent) {
                baselineTurnBias += (rawTurn - baselineTurnBias) * (1f - exp((-dt / 2.5f).toDouble()).toFloat())
            } else {
                baselineTurnBias *= exp((-dt / 5.0f).toDouble()).toFloat()
            }
            val neuralTurn = rawTurn - baselineTurnBias * .72f
            // Drosophila exploration is not a fixed rightward arc. Add a bounded,
            // zero-mean Ornstein-Uhlenbeck-like yaw fluctuation while locomoting.
            // It has no dependence on X, wall side, food position, or stimulus.
            val movingGate = (legActivity / .025f).coerceIn(0f, 1f)
            val turnNoiseTarget = (locomotionRng.nextFloat() * 2f - 1f) * .20f
            val noiseAlpha = (dt / .42f).coerceIn(0f, 1f)
            exploratoryTurn += (turnNoiseTarget - exploratoryTurn) * noiseAlpha
            val turn = neuralTurn + exploratoryTurn * movingGate
            // V1.06 movement: translation is still generated exclusively from
            // measured VNC motor neurons. Leg MN activity supplies walking force;
            // wing/jump MN activity adds flight thrust. No stimulus or action score
            // writes position directly.
            // Physical force comes only from measured VNC motor-neuron output after
            // the actuator time constant. No food/sensory/action variable enters here.
            val recruitedLeg = legActivity.coerceAtLeast(0f)
            val flightMotor = (wingActivity * .78f + jumpActivity * .22f).coerceIn(0f, 1f)
            flightFactor += (flightMotor - flightFactor) * (1f - exp((-dt / .10f).toDouble()).toFloat())
            val jumpImpulse = jumpActivity * .006f
            val cmdSpeed = (recruitedLeg * .012f + flightMotor * .010f + jumpImpulse).coerceIn(-.002f, .018f)
            heading += turn * dt * 3.6f
            // Keep the internal angle bounded without changing its orientation.
            // This avoids unbounded angle growth during long free-walking runs.
            val twoPi = (Math.PI * 2.0).toFloat()
            if (heading > Math.PI.toFloat()) heading -= twoPi
            if (heading < -Math.PI.toFloat()) heading += twoPi
            val speedBlend = if (cmdSpeed < .00025f) .10f else .16f
            flySpeed = (1f - speedBlend) * flySpeed + speedBlend * cmdSpeed
            flyX += cos(heading) * flySpeed * dt * 60f
            flyY += sin(heading) * flySpeed * dt * 60f

            val dxPhysical = flyX - lastMotionX
            val dyPhysical = flyY - lastMotionY
            physicalSpeed = hypot(dxPhysical, dyPhysical) / dt.coerceAtLeast(.001f)
            physicalAcceleration = (physicalSpeed - previousPhysicalSpeed) / dt.coerceAtLeast(.001f)
            previousPhysicalSpeed = physicalSpeed
            displacementPerSecond = physicalSpeed
            pathLength += hypot(dxPhysical, dyPhysical)
            val movementEvidence = (physicalSpeed / .0035f).coerceIn(0f, 1f)
            physicalMovementMemory = .94f * physicalMovementMemory + .06f * movementEvidence
            lastMotionX = flyX
            lastMotionY = flyY

            // Wing-driven flight adds only a small vertical lift/bob. It is gated
            // by measured wing/jump motor activity, so ordinary walking does not
            // magically become flight.
            flightPhase += dt * (10f + 22f * flightFactor)
            if (flightFactor > .05f) {
                flyY += sin(flightPhase * (Math.PI.toFloat() * 2f)) * .00065f * flightFactor * dt * 60f
            }

            if (flyX < .055f || flyX > .945f) {
                heading = Math.PI.toFloat() - heading
                flyX = flyX.coerceIn(.055f, .945f)
            }
            if (flyY < .10f || flyY > .79f) {
                heading = -heading
                flyY = flyY.coerceIn(.10f, .79f)
            }

            var reward = 0f
            val tarsalContactNow = tarsalFoodContactIntensity(foodX, foodY)
            val gustatoryRateNow = populationRate(GUST_START, GUST_END)
            // Feeding/contact is a neural + body event. It may update internal
            // state, but it MUST NOT mutate the environment position. Contact is
            // defined by the same anterior-tarsal sensor geometry that generated
            // the gustatory input for this frame, then gated by measured gustatory
            // neural activity. The latch counts contact episodes, not frames.
            val foodContact = foodOn &&
                tarsalContactNow >= .04f &&
                gustatoryRateNow > FOOD_CONTACT_GUSTATORY_MIN
            if (foodContact && !foodContactLatched) {
                foodHits++
                satiety = min(1f, satiety + .24f)
                reward += 1f
            }
            foodContactLatched = foodContact

            satiety *= exp((-dt * .018f).toDouble()).toFloat()

            val danger = if (dangerOn) gaussian(hypot(dangerX - flyX, dangerY - flyY), .36f) else 0f

            val visualRate = populationRate(VIS_START, VIS_END)
            val olfactoryRate = olfactoryPopulationRate()
            val gustatoryRate = populationRate(GUST_START, GUST_END)
            val mechanosensoryRate = mechanosensoryPopulationRate()
            val descendingRate = populationRate(DESC_START, DESC_END)
            val ascendingRate = populationRate(ASC_START, ASC_END)
            val centralRate = populationRate(OTHER_START, OTHER_END)
            val motorRate = (allMotorSpikeEvents * invFrame / motorCount.toFloat()).coerceIn(0f, 260f)

            // UI diagnostics report measured firing, not stimulus intensity.
            visualRateDisplay = .82f * visualRateDisplay + .18f * visualRate
            olfactoryRateDisplay = .82f * olfactoryRateDisplay + .18f * olfactoryRate
            gustatoryRateDisplay = .82f * gustatoryRateDisplay + .18f * gustatoryRate
            mechanosensoryRateDisplay = .82f * mechanosensoryRateDisplay + .18f * mechanosensoryRate
            descendingRateDisplay = .82f * descendingRateDisplay + .18f * descendingRate
            ascendingRateDisplay = .82f * ascendingRateDisplay + .18f * ascendingRate
            centralRateDisplay = .82f * centralRateDisplay + .18f * centralRate
            motorRateDisplay = .82f * motorRateDisplay + .18f * motorRate
            legActivityCache = legActivity
            jumpActivityCacheValue = jumpActivity

            sensoryDisplay = .86f * sensoryDisplay + .14f * ((visualRate + olfactoryRate + gustatoryRate + mechanosensoryRate) * .25f)
            centralDisplay = .88f * centralDisplay + .12f * ((centralRate + descendingRate + ascendingRate) / 3f)
            motorDisplay = .88f * motorDisplay + .12f * motorRate

            // Update measured motor traces BEFORE action selection. V1.04 read the
            // previous tick's motor traces, adding avoidable one-step lag.
            leftMotor = .82f * leftMotor + .18f * leftLeg
            rightMotor = .82f * rightMotor + .18f * rightLeg
            forwardMotor = .82f * forwardMotor + .18f * legActivity
            // Defensive motor evidence is distributed across measured jump, wing
            // and leg outputs; jump is weighted most strongly but is not the sole
            // definition of escape.
            escapeMotor = .82f * escapeMotor + .18f *
                (jumpActivity * .60f + wingActivity * .22f + legActivity * .18f).coerceIn(0f, 1f)
            brakeMotor = .82f * brakeMotor + .18f * (1f - legActivity) * if (allMotor > 0f) 1f else 0f
            exploreMotor = .82f * exploreMotor + .18f * ((legActivity + wingActivity + neckActivity + abdomenActivity).coerceIn(0f, 1f))

            updateActionSelection(dt, visualRate, olfactoryRate, gustatoryRate, mechanosensoryRate, motorRate)

            // Escape events are counted only after the current neural action score
            // has been updated, avoiding the one-tick lag present in V1.04.
            val escapeNeural = escapeAction > .16f &&
                (descendingRateDisplay > .01f || escapeRouteActivityDisplay > .01f || motorRateDisplay > .01f)
            if (danger > .68f && lastDangerLevel <= .68f && escapeNeural) escapeEvents++
            if (dangerOn && danger < .20f && lastDangerLevel > .68f && escapeNeural) reward += .5f
            lastDangerLevel = danger

            learn(reward, dt)

            // Stable locomotion is a body readout, not a neural firing shortcut.
            stableLocomotion = (.88f * stableLocomotion + .12f * physicalMovementMemory).coerceIn(0f, 1f)
            recentMovementMemory = .94f * recentMovementMemory + .06f * physicalMovementMemory
            val wingVisualIntensity = max(wingActivityCache, jumpActivityCache())
            wingBeatPhase += dt * (8f + 11f * wingVisualIntensity) * (Math.PI.toFloat() * 2f)
            updateBuzzSound()
        }

        private fun runNeuralSimulation(dt: Float) {
            if (!connectomeLoaded) return
            require(abs(dt - NEURAL_FRAME_DT_SECONDS) < 0.000001f) {
                "neural outer dt inesperado=$dt; esperado=$NEURAL_FRAME_DT_SECONDS"
            }

            simTime += dt
            sense(dt)
            resetMotorSubstepAccumulators()

            var totalSpikes = 0
            for (substep in 0 until NEURAL_SUBSTEPS_PER_FRAME) {
                totalSpikes += stepBrainSubstep(
                    NEURAL_SUBSTEP_DT_SECONDS,
                    // The sensory sample is a zero-order-held environmental signal.
                    // Keep it active for every internal substep; stepBrainSubstep
                    // divides the calibrated frame dose across the four substeps.
                    applySensoryKick = true
                )
                accumulateNeuralSubstepDiagnostics()
            }

            updateOuterNeuralState(dt, totalSpikes)
            driveBody(dt)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val now = System.nanoTime()
            val raw = (now - lastNs) / 1e9
            val frameDt = min(.030, max(.004, raw)).toFloat()
            lastNs = now
            fps = fps * .94f + (1f / frameDt) * .06f
            // Public neural clock at 50 Hz, independent of display refresh rate; each public frame resolves 40 internal 0.5 ms substeps.
            neuralAccumulator += frameDt
            var steps = 0
            while (neuralAccumulator >= NEURAL_FRAME_DT_SECONDS && steps < 5) {
                runNeuralSimulation(NEURAL_FRAME_DT_SECONDS)
                neuralAccumulator -= NEURAL_FRAME_DT_SECONDS
                steps++
            }
            neuralStepsLastFrame = steps
            neuralBacklogSeconds = neuralAccumulator

            drawScene(c)
            drawFly(c, flyX * width, flyY * sceneBottom(), heading)
            drawBrainPanel(c)
            postInvalidateOnAnimation()
        }

        private fun sceneBottom(): Float = height - brainPanelHeight()

        // UI-only layout: give the neural observatory more vertical space while
        // keeping enough room above for the interactive stimulus scene.
        private fun brainPanelHeight(): Float = min(height * .56f, 690.dp().toFloat())

        private fun drawScene(c: Canvas) {
            val bottom = sceneBottom()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(190, 190, 190)
            c.drawRect(8f, 8f, width - 8f, bottom - 6f, paint)
            paint.style = Paint.Style.FILL

            if (foodOn) {
                drawBanana(c, foodX * width, foodY * bottom)
            }

            if (lightOn) {
                val x = lightX * width
                val y = lightY * bottom
                paint.color = Color.rgb(230, 165, 15)
                c.drawCircle(x, y, 38f, paint)
                paint.color = Color.rgb(255, 222, 75)
                c.drawCircle(x, y, 21f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = Color.argb(130, 235, 180, 20)
                c.drawCircle(x, y, 54f, paint)
                paint.style = Paint.Style.FILL
            }

            if (dangerOn) {
                val x = dangerX * width
                val y = dangerY * bottom
                paint.color = Color.rgb(205, 42, 42)
                c.drawCircle(x, y, 34f, paint)
                paint.color = Color.WHITE
                paint.textSize = 33f
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT_BOLD
                c.drawText("!", x, y + 11f, paint)
            }
        }

        private fun drawBanana(c: Canvas, px: Float, py: Float) {
            c.save()
            val scale = (min(width.toFloat(), sceneBottom()) / 520f).coerceIn(.82f, 1.18f)
            c.translate(px, py)
            c.rotate(-18f)
            c.scale(scale, scale)
            val path = android.graphics.Path().apply {
                moveTo(-25f, 8f)
                cubicTo(-12f, 35f, 24f, 38f, 42f, 15f)
                cubicTo(50f, 4f, 45f, -12f, 38f, -20f)
                cubicTo(35f, -4f, 30f, 7f, 20f, 13f)
                cubicTo(8f, 22f, -8f, 20f, -20f, -2f)
                cubicTo(-27f, -11f, -31f, -1f, -25f, 8f)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(247, 202, 45)
            c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(166, 116, 24)
            c.drawPath(path, paint)
            paint.strokeWidth = 4f
            c.drawLine(-26f, 5f, -31f, 0f, paint)
            c.drawLine(39f, -19f, 45f, -24f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(70, 255, 240, 120)
            c.drawOval(-7f, 4f, 22f, 13f, paint)
            c.restore()
        }

        private fun drawBrainPanel(c: Canvas) {
            val d = resources.displayMetrics.density
            val ts = resources.displayMetrics.scaledDensity
            val dp = { v: Float -> v * d }
            val sp = { v: Float -> v * ts }
            val ph = brainPanelHeight()
            val top = height - ph
            val left = 0f
            val right = width.toFloat()
            val innerL = dp(16f)
            val innerR = width - dp(16f)
            val panelW = right - left

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(18, 23, 27)
            c.drawRect(left, top, right, height.toFloat(), paint)

            // Header
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(13f)
            paint.color = Color.rgb(245, 247, 248)
            c.drawText("FLYBRAIN V1.18.4 · FOOD / OLFACTORY ROUTED", innerL, top + dp(22f), paint)

            paint.typeface = Typeface.DEFAULT
            paint.textSize = sp(9.0f)
            paint.color = Color.rgb(171, 181, 187)
            c.drawText("MaleCNS v1.0 · FBR-10-OLF2-MOTORROUTE · FBC103 + FBD105 + VNCSEM102", innerL, top + dp(36f), paint)

            // Live status + model census, kept in one compact row.
            val statusX = innerR - dp(124f)
            paint.color = if (connectomeLoaded && dynamicsLoaded) Color.rgb(70, 205, 120) else Color.rgb(232, 75, 75)
            c.drawCircle(statusX, top + dp(33f), dp(3.2f), paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(8.4f)
            paint.color = Color.rgb(207, 216, 221)
            c.drawText(if (connectomeLoaded && dynamicsLoaded) "CONNECTOME + DYNAMICS OK" else "CONNECTOME / DYNAMICS ERROR", innerR, top + dp(36f), paint)

            // Compact census chips.
            paint.textAlign = Paint.Align.LEFT
            val chips = arrayOf(
                "16.669 N", "${"%.3f".format(loadedEdgeCount / 1_000_000f)}M E", "${"%.3f".format(loadedDynamicsEdgeCount / 1_000_000f)}M DYN", "FPS ${fps.toInt()}"
            )
            val chipY = top + dp(46f)
            var chipX = innerL
            for (label in chips) {
                val cw = when {
                    label.startsWith("16") -> dp(61f)
                    label.startsWith("FPS") -> dp(55f)
                    else -> dp(76f)
                }
                paint.color = Color.rgb(31, 39, 44)
                c.drawRoundRect(chipX, chipY, chipX + cw, chipY + dp(18f), dp(7f), dp(7f), paint)
                paint.color = Color.rgb(196, 205, 210)
                paint.textSize = sp(8.0f)
                paint.typeface = Typeface.DEFAULT_BOLD
                c.drawText(label, chipX + dp(7f), chipY + dp(12.5f), paint)
                chipX += cw + dp(5f)
            }

            // Stimulus + state row.
            val stimulusLabel = when {
                foodOn -> "COMIDA"
                lightOn -> "LUZ"
                dangerOn -> "PELIGRO"
                else -> "NINGUNO"
            }
            val stimulusAccent = when {
                foodOn -> Color.rgb(52, 190, 105)
                lightOn -> Color.rgb(235, 190, 40)
                dangerOn -> Color.rgb(230, 72, 68)
                else -> Color.rgb(105, 116, 124)
            }
            val state = behaviorLabel()
            val rowY = top + dp(69f)
            paint.color = Color.rgb(30, 38, 43)
            c.drawRoundRect(innerL, rowY, innerL + dp(112f), rowY + dp(21f), dp(8f), dp(8f), paint)
            paint.color = stimulusAccent
            c.drawCircle(innerL + dp(10f), rowY + dp(10.5f), dp(3.2f), paint)
            paint.color = Color.rgb(235, 240, 242)
            paint.textSize = sp(8.2f)
            paint.typeface = Typeface.DEFAULT_BOLD
            c.drawText(stimulusLabel, innerL + dp(18f), rowY + dp(14f), paint)
            val stateW = dp(154f)
            paint.color = Color.rgb(30, 38, 43)
            c.drawRoundRect(innerR - stateW, rowY, innerR, rowY + dp(21f), dp(8f), dp(8f), paint)
            paint.color = Color.rgb(210, 218, 222)
            paint.textSize = sp(7.4f)
            paint.textAlign = Paint.Align.CENTER
            c.drawText(state, innerR - stateW * .5f, rowY + dp(14f), paint)
            paint.textAlign = Paint.Align.LEFT

            // Two-column live telemetry.
            val metricsTop = top + dp(98f)
            val gap = dp(8f)
            val colW = (panelW - dp(20f) - gap) * .5f
            val leftX = innerL
            val rightX = innerL + colW + gap
            val cardH = dp(74f)

            fun metricCard(x: Float, y: Float, w: Float, title: String) {
                paint.color = Color.rgb(25, 32, 37)
                c.drawRoundRect(x, y, x + w, y + cardH, dp(9f), dp(9f), paint)
                paint.color = Color.rgb(139, 151, 158)
                paint.textSize = sp(8.2f)
                paint.typeface = Typeface.DEFAULT_BOLD
                c.drawText(title, x + dp(9f), y + dp(13f), paint)
            }

            metricCard(leftX, metricsTop, colW, "NEURAL PIPELINE")
            paint.color = Color.rgb(220, 226, 229)
            paint.textSize = sp(8.5f)
            c.drawText("S ${(sensorySpikesDisplay * 100).toInt()}%   C ${(centralSpikesDisplay * 100).toInt()}%   DN ${(descendingSpikesDisplay * 100).toInt()}%   M ${(motorSpikesDisplay * 100).toInt()}%", leftX + dp(9f), metricsTop + dp(29f), paint)
            c.drawText("DN $dnSpikingCount/1314    MN $motorSpikingCount/708", leftX + dp(9f), metricsTop + dp(43f), paint)
            c.drawText("SPIKES/s ${spikesPerSecond.toInt()}    OLF ${(olfactoryRateDisplay * 100).toInt()}%    VIS ${(visualRateDisplay * 100).toInt()}%", leftX + dp(9f), metricsTop + dp(57f), paint)

            metricCard(rightX, metricsTop, colW, "MOTOR OUTPUT")
            c.drawText("LEG L ${"%.3f".format(leftLegDriveSignedCache)}   R ${"%.3f".format(rightLegDriveSignedCache)}   Δ ${"%.3f".format(rightLegDriveSignedCache - leftLegDriveSignedCache)}", rightX + dp(9f), metricsTop + dp(29f), paint)
            c.drawText("DRIVE ${"%.4f".format(motorDriveSignedCache)}   |${"%.4f".format(motorDriveAbsCache)}   peak ${"%.4f".format(motorDriveAbsPeakCache)}", rightX + dp(9f), metricsTop + dp(43f), paint)
            c.drawText("MN Hz L/R ${"%.2f".format(legLeftHz)}/${"%.2f".format(legRightHz)}  Δ ${"%+.2f".format(legRightHz - legLeftHz)}", rightX + dp(9f), metricsTop + dp(57f), paint)

            val bodyY = metricsTop + cardH + dp(7f)
            metricCard(leftX, bodyY, colW, "CUERPO")
            val headingDeg = Math.toDegrees(heading.toDouble()).toFloat().let { raw ->
                ((raw + 180f) % 360f + 360f) % 360f - 180f
            }
            val netDisplacement = hypot(flyX - .24f, flyY - .55f)
            c.drawText("V ${"%.4f".format(physicalSpeed)}   A ${"%.4f".format(physicalAcceleration)}   recorrido ${"%.3f".format(pathLength)}", leftX + dp(9f), bodyY + dp(29f), paint)
            c.drawText("X ${"%.3f".format(flyX)}   Y ${"%.3f".format(flyY)}   rumbo ${"%.1f".format(headingDeg)}°", leftX + dp(9f), bodyY + dp(43f), paint)
            c.drawText("Δpos ${"%.3f".format(netDisplacement)}   pausa ${"%.1f".format(pauseTimer)}s   eventos $pauseCount", leftX + dp(9f), bodyY + dp(57f), paint)

            metricCard(rightX, bodyY, colW, "ENTORNO / RUTA")
            val topDnText = if (topDnIds[0] >= 0) "${bodyId[topDnIds[0]]} ${dnRoleLabel(descendingRole[topDnIds[0]].toInt())} ${"%.1f".format(topDnHz[0])}Hz" else "—"
            val topMotorText = if (topMotorIds[0] >= 0) "${bodyId[topMotorIds[0]]} ${motorRoleLabel(motorRole[topMotorIds[0]].toInt())} ${"%.1f".format(topMotorHz[0])}Hz" else "—"
            c.drawText("TOP DN   $topDnText", rightX + dp(9f), bodyY + dp(29f), paint)
            c.drawText("TOP MN  $topMotorText", rightX + dp(9f), bodyY + dp(43f), paint)
            c.drawText("OLF ORN L/C/R ${"%.2f".format(olfInputLeftCache)}/${"%.2f".format(olfInputCenterCache)}/${"%.2f".format(olfInputRightCache)}   bias ${"%+.3f".format(foodDirectionalBias)}", rightX + dp(9f), bodyY + dp(57f), paint)
            c.drawText("SPIKE Hz  ORN L/R ${"%.1f".format(olfLeftHz)}/${"%.1f".format(olfRightHz)}  DN L/R ${"%.1f".format(dnLeftHz)}/${"%.1f".format(dnRightHz)}", rightX + dp(9f), bodyY + dp(71f), paint)

            // Larger neural map: the visual center of the final interface.
            val mapTop = bodyY + cardH + dp(18f)
            val mapBottom = height - dp(14f)
            drawBrainMap(c, dp(14f), mapTop, width - dp(28f), max(dp(120f), mapBottom - mapTop))
        }


        private fun regionColor(id: Int): Int = when {
            isOlfactoryNeuron(id) -> Color.rgb(45, 190, 105)
            id in VIS_START until VIS_END -> Color.rgb(55, 145, 235)
            id in GUST_START until GUST_END -> Color.rgb(238, 190, 42)
            id in MECH_START until MECH_END -> Color.rgb(238, 125, 48)
            id in DESC_START until DESC_END -> Color.rgb(218, 75, 175)
            id in ASC_START until ASC_END -> Color.rgb(55, 190, 210)
            id in MOTOR_START until MOTOR_END -> Color.rgb(235, 70, 75)
            else -> Color.rgb(150, 160, 170)
        }

        private fun regionRateForId(id: Int): Float = when {
            isOlfactoryNeuron(id) -> olfactoryRateDisplay
            id in VIS_START until VIS_END -> visualRateDisplay
            id in GUST_START until GUST_END -> gustatoryRateDisplay
            id in MECH_START until MECH_END -> mechanosensoryRateDisplay
            id in DESC_START until DESC_END -> descendingRateDisplay
            id in ASC_START until ASC_END -> ascendingRateDisplay
            id in MOTOR_START until MOTOR_END -> motorRateDisplay
            else -> centralRateDisplay
        }

        private fun drawBrainMap(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(27, 32, 36)
            c.drawRoundRect(x, y, x + w, y + h, 14f, 14f, paint)

            val cx = x + w * .50f
            val cy = y + h * .43f

            // Simplified Drosophila CNS silhouette: bilateral optic lobes,
            // central brain and a short ventral nerve cord. It is a visual map,
            // while the nodes/links over it come from the retained connectome.
            paint.color = Color.argb(48, 55, 145, 235)
            c.drawOval(x + w * .055f, y + h * .18f, x + w * .29f, y + h * .73f, paint)
            c.drawOval(x + w * .71f, y + h * .18f, x + w * .945f, y + h * .73f, paint)

            paint.color = Color.argb(45, 150, 160, 170)
            c.drawOval(x + w * .27f, y + h * .22f, x + w * .73f, y + h * .70f, paint)

            val visGlow = (visualRateDisplay * 255f).toInt().coerceIn(18, 105)
            val olfGlow = (olfactoryRateDisplay * 255f).toInt().coerceIn(18, 105)
            val gustGlow = (gustatoryRateDisplay * 255f).toInt().coerceIn(18, 105)
            val mechGlow = (mechanosensoryRateDisplay * 255f).toInt().coerceIn(18, 105)
            val dnGlow = (descendingRateDisplay * 255f).toInt().coerceIn(18, 105)
            val ascGlow = (ascendingRateDisplay * 255f).toInt().coerceIn(18, 105)
            val motorGlow = (motorRateDisplay * 255f).toInt().coerceIn(18, 105)
            paint.color = Color.argb(visGlow, 55, 145, 235)
            c.drawOval(x + w * .055f, y + h * .18f, x + w * .29f, y + h * .73f, paint)
            c.drawOval(x + w * .71f, y + h * .18f, x + w * .945f, y + h * .73f, paint)
            paint.color = Color.argb(olfGlow, 45, 190, 105)
            c.drawOval(x + w * .35f, y + h * .43f, x + w * .45f, y + h * .64f, paint)
            c.drawOval(x + w * .55f, y + h * .43f, x + w * .65f, y + h * .64f, paint)
            paint.color = Color.argb(gustGlow, 238, 190, 42)
            c.drawCircle(x + w * .40f, y + h * .60f, min(w, h) * .045f, paint)
            c.drawCircle(x + w * .60f, y + h * .60f, min(w, h) * .045f, paint)
            paint.color = Color.argb(mechGlow, 238, 125, 48)
            c.drawOval(x + w * .31f, y + h * .52f, x + w * .41f, y + h * .76f, paint)
            c.drawOval(x + w * .59f, y + h * .52f, x + w * .69f, y + h * .76f, paint)
            paint.color = Color.argb(dnGlow, 218, 75, 175)
            c.drawOval(x + w * .43f, y + h * .58f, x + w * .57f, y + h * .76f, paint)
            paint.color = Color.argb(ascGlow, 55, 190, 210)
            c.drawOval(x + w * .44f, y + h * .62f, x + w * .56f, y + h * .84f, paint)
            paint.color = Color.argb(motorGlow, 235, 70, 75)
            c.drawRoundRect(cx - w * .055f, y + h * .66f, cx + w * .055f, y + h * .92f, w * .025f, w * .025f, paint)

            // Mushroom-body / central-complex hints.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, w * .002f)
            paint.color = Color.argb(80, 175, 185, 193)
            c.drawOval(x + w * .36f, y + h * .29f, x + w * .47f, y + h * .62f, paint)
            c.drawOval(x + w * .53f, y + h * .29f, x + w * .64f, y + h * .62f, paint)
            c.drawOval(x + w * .44f, y + h * .34f, x + w * .56f, y + h * .56f, paint)

            // Antennal lobes and a compact central-complex marker.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(83, 91, 98)
            c.drawCircle(x + w * .40f, y + h * .60f, min(w, h) * .045f, paint)
            c.drawCircle(x + w * .60f, y + h * .60f, min(w, h) * .045f, paint)
            c.drawCircle(cx, y + h * .45f, min(w, h) * .038f, paint)

            // Ventral nerve cord.
            paint.color = Color.rgb(48, 55, 61)
            c.drawRoundRect(
                cx - w * .055f, y + h * .66f,
                cx + w * .055f, y + h * .92f,
                w * .025f, w * .025f, paint
            )

            fun posForNeuron(id: Int): FloatArray {
                val side = nodeSide[id].toInt()
                val u = ((id * 1103515245L + 12345L) and 0x7fffffffL) / 2147483647f
                val v2 = ((id * 1664525L + 1013904223L) and 0x7fffffffL) / 2147483647f
                return when {
                    isOlfactoryNeuron(id) -> {
                        val olfSide = olfactorySide[id].toInt()
                        floatArrayOf(
                            x + w * (if (olfSide < 0) .40f else if (olfSide > 0) .60f else .50f) + (u - .5f) * w * .07f,
                            y + h * (.48f + v2 * .18f)
                        )
                    }
                    id in VIS_START until VIS_END -> {
                        val left = side < 0
                        floatArrayOf(
                            x + w * (if (left) .16f else .84f) + (u - .5f) * w * .14f,
                            y + h * (.28f + v2 * .38f)
                        )
                    }
                    id in OLF_START until OLF_END -> floatArrayOf(
                        x + w * (if (side < 0) .40f else .60f) + (u - .5f) * w * .07f,
                        y + h * (.48f + v2 * .18f)
                    )
                    id in GUST_START until GUST_END -> floatArrayOf(
                        x + w * (if (side < 0) .44f else .56f) + (u - .5f) * w * .10f,
                        y + h * (.55f + v2 * .16f)
                    )
                    id in MECH_START until MECH_END -> floatArrayOf(
                        x + w * (if (side < 0) .35f else .65f) + (u - .5f) * w * .10f,
                        y + h * (.55f + v2 * .23f)
                    )
                    id in DESC_START until DESC_END -> floatArrayOf(
                        x + w * (if (side < 0) .46f else .54f) + (u - .5f) * w * .16f,
                        y + h * (.62f + v2 * .12f)
                    )
                    id in ASC_START until ASC_END -> floatArrayOf(
                        x + w * (if (side < 0) .47f else .53f) + (u - .5f) * w * .18f,
                        y + h * (.65f + v2 * .12f)
                    )
                    id in MOTOR_START until MOTOR_END -> floatArrayOf(
                        cx + (u - .5f) * w * .07f,
                        y + h * (.73f + v2 * .16f)
                    )
                    else -> floatArrayOf(
                        cx + (u - .5f) * w * .34f,
                        y + h * (.28f + v2 * .42f)
                    )
                }
            }

            // Real retained edges between representative neurons. Presentation only.
            // V1.14.3: links are deliberately thin and low-alpha so dense regions remain
            // legible. Activity is encoded primarily by brightness, not diameter.
            val lineDp = resources.displayMetrics.density
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for ((sourceRep, targetRep) in brainDisplayLinks) {
                val sourceId = brainDisplayIds[sourceRep]
                val targetId = brainDisplayIds[targetRep]
                val a = posForNeuron(sourceId)
                val b = posForNeuron(targetId)
                val activity = max(visualActivity[sourceId], visualActivity[targetId]).coerceIn(0f, 1f)
                val regional = max(regionRateForId(sourceId), regionRateForId(targetId))
                val signal = max(activity, regional * .14f)
                val base = regionColor(sourceId)

                // Thin baseline for all represented real edges.
                paint.strokeWidth = (0.16f + 0.24f * signal) * lineDp
                val baseAlpha = (6f + 32f * signal).toInt().coerceIn(6, 38)
                paint.color = Color.argb(baseAlpha, Color.red(base), Color.green(base), Color.blue(base))
                c.drawLine(a[0], a[1], b[0], b[1], paint)

                // Active edge highlight: still thin; brightness/alpha carries the signal.
                if (activity > .06f) {
                    paint.strokeWidth = (0.24f + 0.32f * activity) * lineDp
                    val activeAlpha = (32f + 105f * activity).toInt().coerceIn(32, 140)
                    paint.color = Color.argb(activeAlpha, Color.red(base), Color.green(base), Color.blue(base))
                    c.drawLine(a[0], a[1], b[0], b[1], paint)
                }
            }

            // Representative neurons: inactive = small/dim; active = larger,
            // brighter and surrounded by a visible halo.
            paint.style = Paint.Style.FILL
            for (rep in brainDisplayIds.indices) {
                val id = brainDisplayIds[rep]
                val p = posForNeuron(id)
                val activity = visualActivity[id].coerceIn(0f, 1f)
                val regional = regionRateForId(id)
                val base = regionColor(id)
                val visibleBaseline = (regional * .12f).coerceIn(0f, .12f)
                val intensity = max(activity, visibleBaseline)
                val radius = 1.25f + 6.8f * activity

                if (activity > .035f) {
                    paint.color = Color.argb((22f + 55f * activity).toInt().coerceIn(22, 80),
                        Color.red(base), Color.green(base), Color.blue(base))
                    c.drawCircle(p[0], p[1], radius * 2.7f, paint)
                    paint.color = Color.argb((42f + 95f * activity).toInt().coerceIn(42, 145),
                        Color.red(base), Color.green(base), Color.blue(base))
                    c.drawCircle(p[0], p[1], radius * 1.65f, paint)
                }

                val alpha = if (activity > .02f) {
                    (85f + 170f * intensity).toInt().coerceIn(85, 255)
                } else 72
                paint.color = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
                c.drawCircle(p[0], p[1], radius, paint)

                if (activity > .25f) {
                    paint.color = Color.argb((120f + 120f * activity).toInt().coerceIn(120, 240), 255, 255, 255)
                    c.drawCircle(p[0], p[1], max(1.0f, radius * .25f), paint)
                }
            }

            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 7f
            paint.color = Color.rgb(205, 212, 216)
            c.drawText("TAMAÑO = actividad · BRILLO = actividad · HALO = pico reciente", x + 12f, y + 15f, paint)

            paint.textSize = 6.5f
            paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.RIGHT
            paint.color = Color.rgb(185, 192, 196)
            c.drawText("actividad baja", x + w - 55f, y + h - 12f, paint)
            paint.textAlign = Paint.Align.LEFT
            for (i in 0..4) {
                val t = i / 4f
                val rr = 2f + 3.5f * t
                paint.color = Color.rgb(65 + (190f * t).toInt(), 90 + (120f * t).toInt(), 210 - (70f * t).toInt())
                c.drawCircle(x + w - 48f + i * 9f, y + h - 13f, rr, paint)
            }
            paint.color = Color.rgb(240, 240, 240)
            paint.textAlign = Paint.Align.LEFT
            c.drawText("alta", x + w - 8f, y + h - 12f, paint)

            paint.color = Color.rgb(190, 198, 202)
            paint.textSize = 7f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            c.drawText("ÓPTICO", x + w * .16f, y + h * .88f, paint)
            c.drawText("ÓPTICO", x + w * .84f, y + h * .88f, paint)
            c.drawText("CENTRAL", cx, y + h * .18f, paint)
            c.drawText("VNC", cx, y + h * .97f, paint)
            paint.textAlign = Paint.Align.LEFT
        }


        private fun drawFly(c: Canvas, px: Float, py: Float, angle: Float) {
            // UI-only rendering. Coordinates remain tied to flyX/flyY/heading;
            // this method does not modify simulation state or physics.
            c.save()
            c.rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f, px, py)
            val s = (min(width.toFloat(), sceneBottom()) / 520f).coerceIn(.92f, 1.35f)
            c.scale(s, s, px, py)

            val dark = Color.rgb(42, 34, 30)
            val brown = Color.rgb(102, 70, 48)
            val amber = Color.rgb(177, 126, 78)
            val amberHi = Color.rgb(213, 164, 103)

            // Ground shadow.
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(34, 0, 0, 0)
            c.drawOval(px - 42f, py + 108f, px + 42f, py + 121f, paint)

            // Transparent wings, posterior to the thorax, with real-looking veins.
            val wingVisual = max(wingActivityCache, jumpActivityCache())
            val wingBeat = sin(wingBeatPhase) * (2.5f + 9f * wingVisual)
            val wingAlpha = (62f + 38f * wingVisual).toInt().coerceIn(55, 105)
            val wingColor = Color.argb(wingAlpha, 175, 202, 218)
            val wingStroke = Color.argb(175, 92, 117, 132)

            fun drawWing(sign: Float) {
                val attachX = px + sign * 14f
                val path = android.graphics.Path().apply {
                    moveTo(attachX, py + 8f)
                    cubicTo(px + sign * 54f, py + 22f, px + sign * 92f, py + 68f, px + sign * 112f, py + 92f)
                    cubicTo(px + sign * 125f, py + 108f, px + sign * 116f, py + 122f, px + sign * 92f, py + 119f)
                    cubicTo(px + sign * 56f, py + 114f, px + sign * 30f, py + 70f, px + sign * 5f, py + 22f)
                    close()
                }
                c.save()
                c.rotate(sign * wingBeat, attachX, py + 8f)
                paint.style = Paint.Style.FILL
                paint.color = wingColor
                c.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.15f
                paint.color = wingStroke
                c.drawPath(path, paint)
                c.drawLine(attachX, py + 12f, px + sign * 105f, py + 103f, paint)
                c.drawLine(attachX, py + 13f, px + sign * 89f, py + 73f, paint)
                c.drawLine(attachX, py + 14f, px + sign * 72f, py + 48f, paint)
                c.drawLine(px + sign * 52f, py + 35f, px + sign * 104f, py + 106f, paint)
                c.restore()
            }
            drawWing(-1f)
            drawWing(1f)

            // Six legs: coxa, femur, tibia and a small tarsus tip.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 3.0f
            paint.color = dark
            val legData = arrayOf(
                floatArrayOf(-20f, -18f, -46f, -38f, -68f, -54f),
                floatArrayOf(-23f, 2f, -53f, 4f, -80f, -4f),
                floatArrayOf(-19f, 23f, -43f, 46f, -68f, 57f),
                floatArrayOf(20f, -18f, 46f, -38f, 68f, -54f),
                floatArrayOf(23f, 2f, 53f, 4f, 80f, -4f),
                floatArrayOf(19f, 23f, 43f, 46f, 68f, 57f)
            )
            for (v in legData) {
                c.drawLine(px + v[0], py + v[1], px + v[2], py + v[3], paint)
                c.drawLine(px + v[2], py + v[3], px + v[4], py + v[5], paint)
                c.drawLine(px + v[4], py + v[5], px + v[4] + if (v[4] < 0f) -8f else 8f, py + v[5] + 2f, paint)
            }

            // Halteres behind the thorax.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(213, 164, 103)
            c.drawCircle(px - 37f, py + 42f, 4.5f, paint)
            c.drawCircle(px + 37f, py + 42f, 4.5f, paint)
            paint.strokeWidth = 1.4f
            paint.style = Paint.Style.STROKE
            c.drawLine(px - 34f, py + 39f, px - 25f, py + 24f, paint)
            c.drawLine(px + 34f, py + 39f, px + 25f, py + 24f, paint)

            // Tapered, strongly segmented abdomen of a male Drosophila.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(48, 40, 36)
            val abdomen = android.graphics.Path().apply {
                moveTo(px - 23f, py + 28f)
                cubicTo(px - 30f, py + 52f, px - 25f, py + 101f, px, py + 123f)
                cubicTo(px + 25f, py + 101f, px + 30f, py + 52f, px + 23f, py + 28f)
                close()
            }
            c.drawPath(abdomen, paint)
            val segY = floatArrayOf(42f, 56f, 70f, 84f, 97f, 109f)
            for (i in segY.indices) {
                paint.color = if (i % 2 == 0) Color.rgb(92, 66, 49) else Color.rgb(57, 48, 43)
                val half = 22f - i * 2.1f
                c.drawRoundRect(px - half, py + segY[i], px + half, py + segY[i] + 9f, 4f, 4f, paint)
            }
            // Dark posterior tip characteristic of the male.
            paint.color = Color.rgb(34, 28, 27)
            c.drawOval(px - 10f, py + 104f, px + 10f, py + 123f, paint)

            // Thorax: broad, hairy and slightly lighter dorsally.
            paint.color = Color.rgb(72, 55, 45)
            c.drawOval(px - 34f, py - 38f, px + 34f, py + 37f, paint)
            paint.color = Color.rgb(116, 82, 54)
            c.drawOval(px - 27f, py - 31f, px + 27f, py + 25f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = Color.argb(150, 46, 36, 31)
            for (i in -2..2) c.drawLine(px + i * 7f, py - 25f, px + i * 6f, py + 18f, paint)

            // Fine thoracic bristles.
            paint.strokeWidth = 1.1f
            paint.color = Color.rgb(62, 47, 40)
            for (i in -3..3) {
                c.drawLine(px + i * 7f, py - 27f, px + i * 8f, py - 36f, paint)
                c.drawLine(px + i * 7f, py + 24f, px + i * 9f, py + 32f, paint)
            }

            // Head and large red compound eyes.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(63, 49, 43)
            c.drawOval(px - 28f, py - 76f, px + 28f, py - 25f, paint)
            paint.color = Color.rgb(125, 27, 25)
            c.drawOval(px - 48f, py - 73f, px - 8f, py - 34f, paint)
            c.drawOval(px + 8f, py - 73f, px + 48f, py - 34f, paint)
            // Eye facet highlights.
            paint.color = Color.rgb(205, 59, 45)
            for (yy in -64..-43 step 9) {
                c.drawCircle(px - 28f, py + yy, 2.1f, paint)
                c.drawCircle(px - 17f, py + yy + 3f, 1.7f, paint)
                c.drawCircle(px + 28f, py + yy, 2.1f, paint)
                c.drawCircle(px + 17f, py + yy + 3f, 1.7f, paint)
            }

            // Antennae with aristae.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.7f
            paint.color = dark
            c.drawLine(px - 12f, py - 69f, px - 30f, py - 94f, paint)
            c.drawLine(px + 12f, py - 69f, px + 30f, py - 94f, paint)
            c.drawLine(px - 30f, py - 94f, px - 46f, py - 108f, paint)
            c.drawLine(px + 30f, py - 94f, px + 46f, py - 108f, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(px - 47f, py - 109f, 2f, paint)
            c.drawCircle(px + 47f, py - 109f, 2f, paint)

            // Proboscis/mouthparts.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(70, 48, 40)
            c.drawLine(px - 8f, py - 29f, px - 15f, py - 20f, paint)
            c.drawLine(px + 8f, py - 29f, px + 15f, py - 20f, paint)
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            // Explicit environment manipulation: the currently selected
            // stimulus is the object the user is placing. A tap places it and a
            // drag moves it continuously. This input path is independent from
            // the neural update loop.
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (e.y < sceneBottom()) {
                        draggingStimulus = true
                        moveStimulus(e.x / width.toFloat(), e.y / sceneBottom())
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingStimulus && e.y < sceneBottom()) {
                        moveStimulus(e.x / width.toFloat(), e.y / sceneBottom())
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingStimulus = false
                    return true
                }
            }
            return true
        }

        private fun setFoodPosition(x: Float, y: Float) {
            foodX = x.coerceIn(.06f, .94f)
            foodY = y.coerceIn(.10f, .82f)
            // Repositioning the food is an environment event. Clear the contact
            // latch so placing food onto the fly can legitimately create a new
            // contact event on the next simulation tick.
            foodContactLatched = false
            invalidate()
        }

        private fun moveStimulus(xr: Float, yr: Float) {
            val x = xr.coerceIn(.06f, .94f)
            val y = yr.coerceIn(.10f, .82f)
            when (selectedStimulus) {
                0 -> setFoodPosition(x, y)
                1 -> { lightX = x; lightY = y; invalidate() }
                else -> { dangerX = x; dangerY = y; invalidate() }
            }
        }
    }
}

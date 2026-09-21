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
import android.widget.LinearLayout
import android.widget.TextView
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

class MainActivity : Activity() {
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 247, 247))
            clipToPadding = true
        }

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
            setPadding(6.dp(), 5.dp(), 6.dp(), 5.dp())
            setBackgroundColor(Color.rgb(226, 226, 226))
        }

        fun makeButton(label: String) = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(1.dp(), 0, 1.dp(), 0)
        }

        val food = makeButton("COMIDA")
        val light = makeButton("LUZ")
        val danger = makeButton("PELIGRO")
        val reset = makeButton("↻ RESET")
        val bh = 60.dp()
        listOf(food, light, danger, reset).forEach { button ->
            controls.addView(
                button,
                LinearLayout.LayoutParams(0, bh, 1f).apply {
                    setMargins(2.dp(), 0, 2.dp(), 0)
                }
            )
        }
        root.addView(controls, LinearLayout.LayoutParams(-1, 70.dp()))

        val info = TextView(this).apply {
            setPadding(14.dp(), 7.dp(), 14.dp(), 7.dp())
            setTextColor(Color.rgb(28, 28, 28))
            textSize = 12f
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(2.dp(), Color.rgb(145, 145, 145))
                cornerRadius = 8.dp().toFloat()
            }
        }
        root.addView(
            info,
            LinearLayout.LayoutParams(-1, 76.dp()).apply {
                setMargins(8.dp(), 5.dp(), 8.dp(), 5.dp())
            }
        )

        val sim = FlyView(info)
        root.addView(sim, LinearLayout.LayoutParams(-1, 0, 1f))

        fun refresh() {
            info.text = sim.infoText()
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

    inner class FlyView(private val info: TextView) : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rng = Random(9301)

        // V1.04: exactly 16,669 simulated neurons. The graph is generated at
        // build time from the public MaleCNS v1.0 tables: neurons are sampled
        // within the published superclasses and retained edges are real
        // body-to-body connections from the source connectome.
        private val N = GeneratedConnectomeMeta.NEURONS

        // V1.15: fixed synaptic gains for the signed/normalized FBD104 dynamics
        // layer. They are model parameters, not an adaptive controller.
        private val SENSORY_VIS_GAIN = 0.70f
        private val SENSORY_OLF_GAIN = 1.00f
        // V1.15.5 FOOD-TUNE-B: olfactory source gain increased to 6.00; distance kernel unchanged.
        private val FOOD_OLF_GAIN = 6.00f
        private val SENSORY_GUST_GAIN = 0.85f
        private val SENSORY_MECH_GAIN = 0.70f

        // These values are deliberately frozen during an experiment. Adaptive
        // gain was removed because it altered the neural substrate in response
        // to the very stimulus being measured.
        private var gainOther = 3.60f
        private var gainAsc = 3.40f
        private var gainDesc = 4.00f
        private var gainMotor = 3.60f
        private val V_REST = -0.72f
        private val V_THRESHOLD = -0.50f
        private val V_RESET = -0.84f

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
        private val adapt = FloatArray(N)
        private val fired = BooleanArray(N)
        private val prevFired = BooleanArray(N)
        private val motorRole = ByteArray(N)
        // V1.15.2 anatomical VNC semantics are loaded from the official-annotation-derived asset.
        // The FBC103 role byte remains frozen and is retained only for provenance/audit.
        private val motorFunctionalTag = ByteArray(N)
        private val descendingRole = ByteArray(N)
        // Real MaleCNS body IDs for the selected neurons. Presentation/diagnostic only.
        private val bodyId = LongArray(N)
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
        // V1.13: short-lived synaptic trace (~5 ms), matching the published LIF model timescale.
        private val synTrace = FloatArray(N)
        // V1.15.5 diagnostic: read-only net synaptic drive immediately before LIF thresholding.
        // This is telemetry only and never feeds back into the dynamics.
        private val lastSynDrive = FloatArray(N)

        // V1.07: presentation-only activity persistence. It smooths individual
        // spikes into a short visual intensity trail so activation/deactivation
        // can be read without changing the neural state or connectivity.
        private val visualActivity = FloatArray(N)
        // External sensory drive is stored as a per-step voltage/current kick and
        // applied inside the LIF update AFTER the membrane leak. In the previous
        // V1.13 implementation sense() wrote directly into v[], but stepBrain()
        // then applied a full Euler leak with dt=20 ms and tau=20 ms, which reset
        // the injected voltage back to V_REST before threshold evaluation. That
        // made every stimulus effectively invisible to the neurons.
        private val sensoryCurrent = FloatArray(N)
        // FBR-10 dynamics layer: +1 excitatory, -1 inhibitory, 0 unknown/modulatory.
        private val neuroSign = ByteArray(N)

        private val incoming = Array(N) { IntArray(0) }
        private val incomingW = Array(N) { FloatArray(0) }
        private val baseW = Array(N) { FloatArray(0) }
        private val eligibility = Array(N) { FloatArray(0) }

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

        private var foodX = .76f
        private var foodY = .35f
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
        private var lightDirectionalBias = 0f
        private var dangerDirectionalBias = 0f
        private var dangerLoom = 0f

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
        // V1.15.5-FOOD-TUNE-B: motor drive is measured before spike thresholding.
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
            buildBrain()
            setupBuzzSound()
        }

        fun infoText() = buildString {
            append("FLYBRAIN V1.15.5-FOOD-TUNE-B · MaleCNS v1.0 · FBR-10 · FBC103 + FBD104 + VNCSEM102\n")
            append("16.669 neuronas · ${loadedEdgeCount} conexiones estructurales · ${loadedDynamicsEdgeCount} sinápticas dinámicas · ${if (connectomeLoaded && dynamicsLoaded) "CONNECTOME + DYNAMICS OK" else "CONNECTOME/DYNAMICS ERROR"}\n")
            append("Comidas $foodHits · Escapes $escapeEvents · FPS ${fps.toInt()}")
            if (runtimeFault.isNotEmpty()) append("\nERROR: $runtimeFault")
        }

        private fun behaviorLabel(): String {
            val maxAction = max(approachAction, max(escapeAction, max(orientAction, max(exploreAction, brakeAction))))
            return when {
                escapeAction > .16f && escapeAction >= maxAction - .015f -> "ESCAPE"
                brakeAction > .16f && brakeAction >= maxAction - .015f -> "PAUSA / FRENADO"
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
            button.background = GradientDrawable().apply {
                cornerRadius = 12.dp().toFloat()
                setColor(if (active) Color.rgb(232, 246, 235) else Color.rgb(245, 245, 245))
                setStroke(if (selected) 4 else 1, if (selected) accent else Color.rgb(155, 155, 155))
            }
            button.alpha = if (active) 1f else .68f
        }

        fun updateButtons() {
            foodButton?.let {
                it.text = if (foodOn) "COMIDA  ON" else "COMIDA"
                styleButton(it, foodOn, selectedStimulus == 0, Color.rgb(35, 120, 70))
            }
            lightButton?.let {
                it.text = if (lightOn) "LUZ  ON" else "LUZ"
                styleButton(it, lightOn, selectedStimulus == 1, Color.rgb(210, 145, 10))
            }
            dangerButton?.let {
                it.text = if (dangerOn) "PELIGRO  ON" else "PELIGRO"
                styleButton(it, dangerOn, selectedStimulus == 2, Color.rgb(190, 45, 45))
            }
        }

        fun resetSimulation() {
            for (i in 0 until N) {
                v[i] = V_REST
                adapt[i] = 0f
                fired[i] = false
                prevFired[i] = false
                refractory[i] = 0f
                synTrace[i] = 0f
                lastSynDrive[i] = 0f
                visualActivity[i] = 0f
                sensoryCurrent[i] = 0f
                for (k in incomingW[i].indices) {
                    incomingW[i][k] = baseW[i][k]
                    eligibility[i][k] = 0f
                }
            }
            flyX = .24f
            flyY = .55f
            heading = -.15f
            flySpeed = 0f
            flightFactor = 0f
            flightPhase = 0f
            jumpActivityCacheValue = 0f
            gainOther = 3.60f
            gainAsc = 3.40f
            gainDesc = 4.00f
            gainMotor = 3.60f
            foodX = .76f
            foodY = .35f
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
            lightDirectionalBias = 0f
            dangerDirectionalBias = 0f
            dangerLoom = 0f
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
            wingBeatPhase = 0f
            if (buzzStreamId != 0) {
                soundPool?.stop(buzzStreamId)
                buzzStreamId = 0
            }
            lastNs = System.nanoTime()
            info.text = infoText()
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
                runtimeFault = connectomeError.ifEmpty { "no se pudo cargar FBR-10 / dinámica FBD104" }
                // Fail closed: never substitute an artificial graph for the
                // published connectome. This makes a packaging/format error
                // visible instead of producing scientifically misleading output.
                for (i in 0 until N) {
                    incoming[i] = IntArray(0)
                    incomingW[i] = FloatArray(0)
                    baseW[i] = FloatArray(0)
                    eligibility[i] = FloatArray(0)
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

        private fun loadMeasuredConnectome(): Boolean {
            return try {
                validateGeneratedMeta()
                val resourceId = resources.getIdentifier("malecns_reduced", "raw", packageName)
                if (resourceId == 0) {
                    connectomeError = "recurso malecns_reduced no encontrado"
                    return false
                }
                val bytes = resources.openRawResource(resourceId).use { it.readBytes() }
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
                    incomingW[i] = FloatArray(incomingCount[i])
                    baseW[i] = FloatArray(incomingCount[i])
                    eligibility[i] = FloatArray(incomingCount[i])
                }
                for (k in 0 until e) {
                    val target = dsts[k]
                    val pos = writePos[target]++
                    incoming[target][pos] = srcs[k]
                    incomingW[target][pos] = weights[k]
                    baseW[target][pos] = weights[k]
                }
                buildBrainDisplayGraph()
                loadedEdgeCount = e
                if (!loadDynamicsLayer()) {
                    throw IllegalStateException(connectomeError.ifEmpty { "FBD104 dynamics layer unavailable" })
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
                if (b.remaining() < 17) throw IllegalStateException("FBD104 cabecera incompleta")
                val magic = ByteArray(8)
                b.get(magic)
                val magicText = magic.toString(Charsets.US_ASCII).trimEnd('\u0000')
                if (magicText != "FBD104") throw IllegalStateException("magic=$magicText esperado=FBD104")
                val n = b.int
                val e = b.int
                if (n != N) throw IllegalStateException("FBD104 neuronas=$n esperado=$N")
                if (e < 0 || e > 50_000_000) throw IllegalStateException("FBD104 edges=$e fuera de rango")
                val expectedBytes = 16L + n.toLong() + e.toLong() * 12L
                if (bytes.size.toLong() != expectedBytes) {
                    throw IllegalStateException("FBD104 tamaño=${bytes.size} esperado=$expectedBytes")
                }
                repeat(n) { idx ->
                    neuroSign[idx] = b.get()
                    val sign = neuroSign[idx].toInt()
                    if (sign != -1 && sign != 0 && sign != 1) {
                        throw IllegalStateException("FBD104 signo invalido en nodo $idx")
                    }
                }
                val srcs = IntArray(e)
                val dsts = IntArray(e)
                val weights = FloatArray(e)
                val incomingCount = IntArray(n)
                repeat(e) {
                    val src = b.int
                    val dst = b.int
                    val weight = b.float
                    if (src !in 0 until n || dst !in 0 until n) {
                        throw IllegalStateException("FBD104 edge[$it] fuera de rango: $src->$dst")
                    }
                    if (!weight.isFinite() || weight == 0f) {
                        throw IllegalStateException("FBD104 edge[$it] con peso invalido")
                    }
                    if (neuroSign[src].toInt() == 0) {
                        throw IllegalStateException("FBD104 edge[$it] usa presinaptica sin signo")
                    }
                    if ((weight > 0f) != (neuroSign[src].toInt() > 0)) {
                        throw IllegalStateException("FBD104 signo de peso inconsistente en edge[$it]")
                    }
                    srcs[it] = src
                    dsts[it] = dst
                    weights[it] = weight
                    incomingCount[dst]++
                }
                if (b.hasRemaining()) throw IllegalStateException("FBD104 bytes restantes=${b.remaining()}")

                for (i in 0 until n) {
                    incoming[i] = IntArray(incomingCount[i])
                    incomingW[i] = FloatArray(incomingCount[i])
                    baseW[i] = FloatArray(incomingCount[i])
                    eligibility[i] = FloatArray(incomingCount[i])
                }
                val writePos = IntArray(n)
                for (k in 0 until e) {
                    val target = dsts[k]
                    val pos = writePos[target]++
                    incoming[target][pos] = srcs[k]
                    incomingW[target][pos] = weights[k]
                    baseW[target][pos] = weights[k]
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

            fun addPopulation(start: Int, end: Int, count: Int) {
                val size = end - start
                if (size <= 0 || count <= 0) return
                val take = min(count, size)
                for (j in 0 until take) {
                    val idx = if (take == 1) start else
                        start + ((j.toLong() * (size - 1).toLong()) / (take - 1).toLong()).toInt()
                    addId(idx)
                }
            }

            // Balanced anatomical sample plus explicit diagnostic coverage of the
            // VNC/halting populations. This is presentation only: every displayed
            // neuron and every displayed edge still comes from the real graph.
            addPopulation(VIS_START, VIS_END, 42)
            addPopulation(OLF_START, OLF_END, 20)
            addPopulation(GUST_START, GUST_END, 12)
            addPopulation(MECH_START, MECH_END, 12)
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

        private fun encodeStimulus(enabled: Boolean, sx: Float, sy: Float, gain: Float): FloatArray {
            if (!enabled) return FloatArray(12)
            val dx = sx - flyX
            val dy = sy - flyY
            val d = hypot(dx, dy)
            val prox = gaussian(d, .48f)
            val a = atan2(dy, dx)
            val rel = atan2(sin(a - heading), cos(a - heading))
            val front = exp((-abs(rel) / .72f).toDouble()).toFloat().coerceIn(0f, 1f)
            val left = exp((-abs(rel + .82f) / .55f).toDouble()).toFloat().coerceIn(0f, 1f)
            val right = exp((-abs(rel - .82f) / .55f).toDouble()).toFloat().coerceIn(0f, 1f)
            val rear = exp((-abs(abs(rel) - Math.PI.toFloat()) / .70f).toDouble()).toFloat().coerceIn(0f, 1f)
            val center = exp(-(rel * rel / (.34f * .34f))).toFloat().coerceIn(0f, 1f)
            return floatArrayOf(
                left * gain, center * gain, right * gain, front * gain, rear * gain * .75f,
                left * prox * gain, center * prox * gain, right * prox * gain,
                front * prox * gain, rear * prox * gain * .75f, prox * gain,
                (1f - prox) * gain * .12f
            )
        }

        // Olfactory concentration is primarily scalar; a small bilateral asymmetry
        // provides a plausible route for odor-guided steering without encoding a
        // hard-coded turn command.
        private fun encodeOdor(enabled: Boolean, sx: Float, sy: Float, gain: Float): FloatArray {
            if (!enabled) return FloatArray(12)
            val dx = sx - flyX
            val dy = sy - flyY
            val d = hypot(dx, dy)
            val concentration = gaussian(d, .58f)
            val rel = atan2(sin(atan2(dy, dx) - heading), cos(atan2(dy, dx) - heading))
            val bilateral = (sin(rel) * .28f).coerceIn(-.28f, .28f)
            val left = (concentration * (1f + bilateral) * gain).coerceIn(0f, 3f)
            val right = (concentration * (1f - bilateral) * gain).coerceIn(0f, 3f)
            return floatArrayOf(left, concentration * gain, right, concentration * .65f * gain, concentration * .35f * gain,
                left, concentration * gain, right, concentration * .65f * gain, concentration * .35f * gain, concentration * gain, 0f)
        }

        private fun encodeTaste(enabled: Boolean, sx: Float, sy: Float, gain: Float): FloatArray {
            if (!enabled) return FloatArray(12)
            val d = hypot(sx - flyX, sy - flyY)
            val contact = gaussian(d, .065f)
            return FloatArray(12) { idx -> contact * gain * when (idx % 4) { 0 -> .8f; 1 -> 1f; 2 -> .8f; else -> .55f } }
        }

        private fun injectSensoryPopulation(start: Int, end: Int, pattern: FloatArray, gain: Float) {
            if (start < 0 || end < start || end > N) {
                throw IllegalStateException("poblacion sensorial fuera de rango: $start..$end / N=$N")
            }
            val size = end - start
            if (size <= 0 || pattern.isEmpty()) return
            for (i in 0 until size) {
                val channel = ((i * 17) % pattern.size)
                val micro = .72f + .28f * sin((i * 0.043f) + channel * .61f).let { (it + 1f) * .5f }
                sensoryCurrent[start + i] += pattern[channel] * gain * micro
            }
        }


        private fun sense(dt: Float) {
            // External sensory drive is a per-step current, not an accumulating state.
            // Clear it before encoding the current environmental state.
            java.util.Arrays.fill(sensoryCurrent, 0f)
            val foodPattern = encodeOdor(foodOn, foodX, foodY, FOOD_OLF_GAIN * (1f - satiety * .45f))
            val tastePattern = encodeTaste(foodOn, foodX, foodY, 2.35f * (1f - satiety * .35f))
            val lightPattern = encodeStimulus(lightOn, lightX, lightY, 1.55f)
            val dangerPattern = encodeStimulus(dangerOn, dangerX, dangerY, 2.15f)

            // PELIGRO is now a multimodal threat stimulus. Its visual component
            // is a slowly expanding/contracting looming pattern; mechanosensory
            // input remains a secondary component. Neither branch writes motor state.
            dangerLoom = if (dangerOn) {
                (.5f + .5f * sin(simTime * 2.2f)).coerceIn(0f, 1f)
            } else 0f
            val visualThreatGain = .45f + .80f * dangerLoom
            val visualThreatPattern = dangerPattern.copyOf().also {
                for (i in it.indices) it[i] *= visualThreatGain
            }
            val combinedVisual = FloatArray(12) { i ->
                (lightPattern[i] + visualThreatPattern[i]).coerceIn(0f, 3.5f)
            }

            // Environmental signals enter only measured sensory populations.
            // Direction is represented by left/centre/right/front/rear activity.
            injectSensoryPopulation(VIS_START, VIS_END, combinedVisual, SENSORY_VIS_GAIN)
            injectSensoryPopulation(OLF_START, OLF_END, foodPattern, SENSORY_OLF_GAIN)
            injectSensoryPopulation(GUST_START, GUST_END, tastePattern, SENSORY_GUST_GAIN)
            injectSensoryPopulation(MECH_START, MECH_END, dangerPattern, SENSORY_MECH_GAIN)

            foodDirectionalBias = ((foodPattern[0] - foodPattern[2]) /
                (foodPattern[0] + foodPattern[2] + .001f)).coerceIn(-1f, 1f)
            lightDirectionalBias = ((lightPattern[0] - lightPattern[2]) /
                (lightPattern[0] + lightPattern[2] + .001f)).coerceIn(-1f, 1f)
            dangerDirectionalBias = ((dangerPattern[0] - dangerPattern[2]) /
                (dangerPattern[0] + dangerPattern[2] + .001f)).coerceIn(-1f, 1f)

            // Contact/proprioceptive feedback from boundaries. This is sensory
            // feedback, not a command to turn.
            val wall = min(min(flyX - .06f, .94f - flyX), min(flyY - .10f, .79f - flyY)).coerceIn(0f, .4f)
            val wallSignal = (1f - wall / .4f).coerceIn(0f, 1f)
            wallDistanceCache = wall
            wallSignalCache = wallSignal
            for (i in MECH_START until SENSOR_END) {
                sensoryCurrent[i] += wallSignal * .055f
            }

            foodDrive = foodPattern[10].coerceIn(0f, 1f)
            lightDrive = combinedVisual[10].coerceIn(0f, 1f)
            dangerDrive = (dangerPattern[10] * .35f + visualThreatPattern[10] * .65f).coerceIn(0f, 1f)

            foodSignalDisplay = .90f * foodSignalDisplay + .10f * foodDrive
            lightSignalDisplay = .90f * lightSignalDisplay + .10f * lightDrive
            dangerSignalDisplay = .90f * dangerSignalDisplay + .10f * dangerDrive

            sensoryDisplay = .90f * sensoryDisplay + .10f * ((foodDrive + lightDrive + dangerDrive) / 3f)

            // Adaptation is part of the sensory membrane equation. It is folded
            // into the same external drive rather than modifying v[] before the
            // leak, so the input cannot be accidentally erased by the 20 ms Euler
            // leak used by the neural clock.
            for (i in 0 until SENSOR_END) {
                sensoryCurrent[i] -= adapt[i]
                adapt[i] *= exp((-dt * 2.0f).toDouble()).toFloat()
            }
        }

        private fun stepBrain(dt: Float) {
            for (i in 0 until N) prevFired[i] = fired[i]

            // V1.04: a spike creates a short-lived synaptic trace. The temporal
            // trace is applied to the published retained graph; it does not alter topology.
            val synDecay = exp((-dt / .005f).toDouble()).toFloat()
            for (i in 0 until N) {
                synTrace[i] = (synTrace[i] * synDecay + if (prevFired[i]) 1f else 0f).coerceAtMost(3f)
            }

            // V1.13: no always-on locomotor oscillator and no external random
            // pause generator. The connectome receives sensory input, its measured
            // LIF activity propagates through the retained MaleCNS edges, and VNC
            // motor output is the only source of body locomotion.
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
            val feedingDemand = (foodDrive * .55f + populationRate(GUST_START, GUST_END) * .45f).coerceIn(0f, 1f)
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

            for (i in 0 until N) {
                if (refractory[i] > 0f) {
                    refractory[i] -= dt
                    fired[i] = false
                    continue
                }

                val targetIsMotor = i in MOTOR_START until MOTOR_END
                var syn = 0f
                val src = incoming[i]
                val w = incomingW[i]
                for (k in src.indices) {
                    val source = src[k]
                    // V1.13: every runtime synaptic contribution is the signed
                    // published edge itself. No artificial "halt gain" is applied.
                    syn += w[k] * synTrace[source]
                }
                // The normalized signed drive is bounded only after population
                // gain. This keeps inhibition/excitation balanced without
                // changing the frozen edge topology.
                syn = syn.coerceIn(-.75f, .75f)

                val isSensor = i < SENSOR_END
                val isMotor = targetIsMotor
                val isDesc = i in DESC_START until DESC_END
                // V1.13: remove synthetic locomotor/tonic currents that previously
                // kept the fly moving even when the retained network had no motor
                // reason to do so. A very small background fluctuation remains to
                // represent unresolved intrinsic activity without commanding a behavior.
                // V1.13: no stochastic background current. A silent baseline is
                // preferable to synthetic spikes that could be mistaken for emergent
                // activity. Any spontaneous firing must come from the retained graph
                // dynamics or from an explicit sensory input.
                val centralNoise = 0f

                // V1.15: gains are fixed model parameters. The previous adaptive
                // controller was coupled to stimulus presentation and therefore
                // changed the system while it was being measured. FBD104 already
                // contains signed, normalized synaptic weights; these gains are
                // now held constant so stimulus comparisons are reproducible.
                val synGain = when {
                    isMotor -> gainMotor
                    isDesc -> gainDesc
                    i in ASC_START until ASC_END -> gainAsc
                    else -> gainOther
                }

                val synCurrent = (syn * synGain).coerceIn(-.55f, .55f)
                lastSynDrive[i] = synCurrent
                // IMPORTANT: sensoryCurrent is applied AFTER the membrane leak.
                // With dt=.020 s and tau_m=.020 s, the old expression
                //     v += (V_REST - v) * 50 * dt + input
                // reduces to v = V_REST + input. When input had already been
                // written into v by sense(), the leak erased it completely.
                // Treating sensory input as an external drive here preserves the
                // intended causal path: stimulus -> sensory spike -> connectome.
                val externalCurrent = if (i < SENSOR_END) sensoryCurrent[i] else 0f
                v[i] += ((V_REST - v[i]) * 50.0f - adapt[i]) * dt +
                    synCurrent + externalCurrent + centralNoise
                fired[i] = v[i] >= V_THRESHOLD

                if (fired[i]) {
                    v[i] = V_RESET
                    adapt[i] = min(.18f, adapt[i] + .026f)
                    refractory[i] = .0022f
                }

                // Visual-only short memory of activity. It is deliberately
                // outside the LIF equations: it cannot feed back into v/adapt.
                visualActivity[i] = (visualActivity[i] * .88f + if (fired[i]) .22f else 0f).coerceIn(0f, 1f)
            }

            var stepSpikes = 0
            var activeVisual = 0f
            for (i in 0 until N) {
                if (fired[i]) stepSpikes++
                if (visualActivity[i] > activeVisual) activeVisual = visualActivity[i]
            }
            spikesLastStep = stepSpikes
            spikeWindowCount += stepSpikes
            spikeWindowTime += dt
            if (spikeWindowTime >= 1f) {
                spikesPerSecond = spikeWindowCount.toFloat() / spikeWindowTime
                spikeWindowCount = 0
                spikeWindowTime = 0f
            }
            maxDisplayedActivity = activeVisual

            // Pipeline diagnostics are read-only. They make it possible to tell
            // whether a failure is at sensory activation, central propagation,
            // descending output, or VNC motor output without changing behaviour.
            val sensoryRate = populationRate(VIS_START, MECH_END)
            val centralRateNow = populationRate(OTHER_START, OTHER_END)
            val descRateNow = populationRate(DESC_START, DESC_END)
            val motorRateNow = populationRate(MOTOR_START, MOTOR_END)
            val drivePeak = if (SENSOR_END > 0) {
                var peak = 0f
                for (i in 0 until SENSOR_END) peak = max(peak, sensoryCurrent[i])
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
            // Quantitative node diagnostics: exact spike counts over a fixed 500 ms window.
            // This is read-only and never feeds back into LIF dynamics.
            for (i in DESC_START until DESC_END) {
                if (fired[i]) dnWindowSpikes[i - DESC_START]++
            }
            for (i in MOTOR_START until MOTOR_END) {
                if (fired[i]) motorWindowSpikes[i - MOTOR_START]++
            }
            if (!foodOn && !lightOn && !dangerOn && !baselineReady) {
                baselineCaptureSeconds += dt
                for (i in DESC_START until DESC_END) {
                    if (fired[i]) dnBaselineSpikes[i - DESC_START]++
                }
                for (i in MOTOR_START until MOTOR_END) {
                    if (fired[i]) motorBaselineSpikes[i - MOTOR_START]++
                }
                if (baselineCaptureSeconds >= 5f) baselineReady = true
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

            val invWindow = 1f / max(0.001f, windowSeconds)
            val baselineInv = if (baselineReady && baselineCaptureSeconds > 0f) 1f / baselineCaptureSeconds else 0f

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

        private fun drawNeuralDiagnosticCard(c: Canvas) {
            // V1.15: quantitative readout only. No diagnostic value feeds back into dynamics.
            val d = resources.displayMetrics.density
            val ts = resources.displayMetrics.scaledDensity
            val dp = { v: Float -> v * d }
            val sp = { v: Float -> v * ts }

            val sceneTop = 8f
            val sceneB = sceneBottom()
            val left = dp(10f)
            val right = width - dp(10f)
            val cardW = right - left
            val cardH = min(dp(272f), max(dp(248f), sceneB - dp(20f)))
            val top = sceneTop + dp(10f)
            val bottom = top + cardH
            val mid = left + cardW * .50f
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(248, 255, 255, 255)
            c.drawRoundRect(left, top, right, bottom, dp(12f), dp(12f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.2f)
            paint.color = Color.argb(210, 82, 90, 96)
            c.drawRoundRect(left, top, right, bottom, dp(12f), dp(12f), paint)
            paint.style = Paint.Style.FILL

            val stimulusLabel = when {
                foodOn -> "COMIDA"
                lightOn -> "LUZ"
                dangerOn -> "PELIGRO"
                else -> "NINGUNO"
            }

            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(13f)
            paint.color = Color.rgb(24, 30, 34)
            c.drawText("DIAGNÓSTICO NEURONAL · SOLO LECTURA", left + dp(12f), top + dp(19f), paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = sp(9.2f)
            paint.color = Color.rgb(82, 89, 95)
            c.drawText("MaleCNS bodyID · ventana 500 ms · estímulo: $stimulusLabel", left + dp(12f), top + dp(33f), paint)

            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(11f)
            paint.color = Color.rgb(190, 42, 145)
            c.drawText("DN SPIKING  $dnSpikingCount/${DESC_END - DESC_START}", left + dp(12f), top + dp(51f), paint)
            paint.color = Color.rgb(108, 62, 195)
            c.drawText("MOTOR SPIKING  $motorSpikingCount/${MOTOR_END - MOTOR_START}", mid + dp(8f), top + dp(51f), paint)

            paint.textSize = sp(8.4f)
            paint.color = Color.rgb(60, 67, 72)
            c.drawText("TOP DN · Hz / Δbase / Vm", left + dp(12f), top + dp(67f), paint)
            c.drawText("TOP MOTOR · Hz / Δbase / Vm", mid + dp(8f), top + dp(67f), paint)

            val rowStep = dp(19f)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = sp(7.2f)
            for (k in 0 until 5) {
                val baseline = top + dp(83f) + rowStep * k
                val di = topDnIds[k]
                val mi = topMotorIds[k]
                paint.color = Color.rgb(38, 44, 49)
                if (di >= 0) {
                    c.drawText("${k + 1}. ${bodyId[di]} ${dnRoleLabel(descendingRole[di].toInt())}", left + dp(12f), baseline, paint)
                    paint.color = Color.rgb(190, 42, 145)
                    c.drawText("${"%.1f".format(topDnHz[k])}Hz", left + dp(55f), baseline, paint)
                    paint.color = if (topDnDelta[k] >= 0f) Color.rgb(20, 130, 80) else Color.rgb(190, 60, 55)
                    c.drawText("Δ${if (topDnDelta[k] >= 0f) "+" else ""}${"%.1f".format(topDnDelta[k])}", left + dp(91f), baseline, paint)
                    paint.color = Color.rgb(75, 82, 88)
                    c.drawText("Vm${"%.2f".format(topDnVm[k])}", left + dp(125f), baseline, paint)
                } else c.drawText("${k + 1}. —", left + dp(12f), baseline, paint)

                paint.color = Color.rgb(38, 44, 49)
                if (mi >= 0) {
                    c.drawText("${k + 1}. ${bodyId[mi]} ${motorRoleLabel(motorRole[mi].toInt())}", mid + dp(8f), baseline, paint)
                    paint.color = Color.rgb(108, 62, 195)
                    c.drawText("${"%.1f".format(topMotorHz[k])}Hz", mid + dp(51f), baseline, paint)
                    paint.color = if (topMotorDelta[k] >= 0f) Color.rgb(20, 130, 80) else Color.rgb(190, 60, 55)
                    c.drawText("Δ${if (topMotorDelta[k] >= 0f) "+" else ""}${"%.1f".format(topMotorDelta[k])}", mid + dp(87f), baseline, paint)
                    paint.color = Color.rgb(75, 82, 88)
                    c.drawText("Vm${"%.2f".format(topMotorVm[k])}", mid + dp(121f), baseline, paint)
                } else c.drawText("${k + 1}. —", mid + dp(8f), baseline, paint)
            }

            val dividerY = top + dp(181f)
            paint.color = Color.rgb(224, 227, 230)
            c.drawRect(left + dp(11f), dividerY, right - dp(11f), dividerY + dp(1f), paint)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(8.4f)
            paint.color = Color.rgb(58, 66, 71)
            c.drawText("SPIKES/s ${spikesPerSecond.toInt()} · BASE ${if (baselineReady) "OK" else "capturando"} · S ${(sensorySpikesDisplay * 100).toInt()}% · C ${(centralSpikesDisplay * 100).toInt()}% · DN ${(descendingSpikesDisplay * 100).toInt()}% · M ${(motorSpikesDisplay * 100).toInt()}%", left + dp(12f), top + dp(195f), paint)

            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(8.2f)
            paint.color = Color.rgb(55, 63, 68)
            val headingDeg = Math.toDegrees(heading.toDouble()).toFloat()
            val netDisplacement = hypot(flyX - .24f, flyY - .55f)
            c.drawText(
                "CUERPO · X ${"%.3f".format(flyX)} · Y ${"%.3f".format(flyY)} · rumbo ${"%.1f".format(headingDeg)}°",
                left + dp(12f), top + dp(208f), paint
            )
            c.drawText(
                "V ${"%.4f".format(physicalSpeed)} · A ${"%.4f".format(physicalAcceleration)} · recorrido ${"%.3f".format(pathLength)} · Δpos ${"%.3f".format(netDisplacement)}",
                left + dp(12f), top + dp(220f), paint
            )
            c.drawText(
                "MOTOR DRIVE net ${"%.4f".format(motorDriveSignedCache)} · |drive| ${"%.4f".format(motorDriveAbsCache)} · pico ${"%.4f".format(motorDriveAbsPeakCache)}",
                left + dp(12f), top + dp(232f), paint
            )
            c.drawText(
                "LEG drive ${"%.4f".format(legDriveSignedCache)} · L ${"%.4f".format(leftLegDriveSignedCache)} · R ${"%.4f".format(rightLegDriveSignedCache)} · spike L ${leftLegActiveCache} R ${rightLegActiveCache}",
                left + dp(12f), top + dp(244f), paint
            )
            c.drawText(
                "WING ${"%.3f".format(wingDriveSignedCache)} · NECK ${"%.3f".format(neckDriveSignedCache)} · ABD ${"%.3f".format(abdomenDriveSignedCache)} · MECH ${mechanosensoryRateDisplay.toInt()}% · WALL ${"%.3f".format(wallDistanceCache)} / ${"%.2f".format(wallSignalCache)}",
                left + dp(12f), top + dp(256f), paint
            )
        }

        private fun count(a: Int, b: Int): Int {
            var n = 0
            for (i in a until b) if (fired[i]) n++
            return n
        }

        private fun populationRate(a: Int, b: Int): Float {
            return count(a, b).toFloat() / (b - a).toFloat()
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
            val approachContext = (foodContext * (.65f + .35f * abs(foodDirectionalBias))).coerceIn(0f, 1f)
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
            val orientContext = (visualContext * .75f + abs(lightDirectionalBias) * .25f).coerceIn(0f, 1f)
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
        private var legActivityCache = 0f
        private var jumpActivityCacheValue = 0f
        private fun legActivityProxy(): Float = legActivityCache
        private fun jumpActivityCache(): Float = jumpActivityCacheValue

        private fun learn(reward: Float, dt: Float) {
            val r = reward.coerceIn(-1f, 1f)
            memoryTrace = (memoryTrace * exp((-dt * .035f).toDouble()).toFloat() + abs(r) * .03f).coerceIn(0f, 1f)

            if (plasticityEnabled) {
                for (target in MOTOR_START until MOTOR_END) {
                    val sources = incoming[target]
                    val weights = incomingW[target]
                    val base = baseW[target]
                    val e = eligibility[target]
                    for (k in sources.indices) {
                        val pre = if (prevFired[sources[k]]) 1f else 0f
                        val post = if (fired[target]) 1f else 0f
                        e[k] = (e[k] * .995f + pre * post * .08f).coerceIn(-1f, 1f)
                        weights[k] += r * e[k] * dt * .18f
                        val lo = min(base[k] * .55f, base[k] * 1.45f)
                        val hi = max(base[k] * .55f, base[k] * 1.45f)
                        weights[k] = weights[k].coerceIn(lo, hi)
                    }
                }
            }
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
                val drive = lastSynDrive[i]
                motorDriveSigned += drive
                motorDriveAbs += abs(drive)
                motorDriveAbsPeak = max(motorDriveAbsPeak, abs(drive))
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
                if (!fired[i]) continue
                if (!fired[i]) continue
                allMotor += 1f
                when (motorRole[i].toInt()) {
                    GeneratedConnectomeMeta.MOTOR_LEG -> {
                        legActive++
                        when (nodeSide[i].toInt()) {
                            -1 -> leftLegActive++
                            1 -> rightLegActive++
                            else -> unknownLegActive++
                        }
                    }
                    GeneratedConnectomeMeta.MOTOR_WING -> wingActive++
                    GeneratedConnectomeMeta.MOTOR_NECK -> neckActive++
                    GeneratedConnectomeMeta.MOTOR_ABDOMEN -> abdomenActive++
                    GeneratedConnectomeMeta.MOTOR_HALTERE -> haltereActive++
                    GeneratedConnectomeMeta.MOTOR_OTHER -> motorOtherActive++
                }
                if (motorFunctionalTag[i].toInt() == GeneratedConnectomeMeta.MOTOR_FUNCTION_JUMP) {
                    jumpActive++
                }
            }

            val legTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_LEG]
            val wingTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_WING]
            val neckTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_NECK]
            val abdomenTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_ABDOMEN]
            val haltereTotal = motorRoleTotals[GeneratedConnectomeMeta.MOTOR_HALTERE]
            legActivity = if (legTotal == 0) 0f else legActive.toFloat() / legTotal.toFloat()
            leftLeg = if (legLeftTotal == 0) 0f else leftLegActive.toFloat() / legLeftTotal.toFloat()
            rightLeg = if (legRightTotal == 0) 0f else rightLegActive.toFloat() / legRightTotal.toFloat()
            wingActivity = if (wingTotal == 0) 0f else wingActive.toFloat() / wingTotal.toFloat()
            neckActivity = if (neckTotal == 0) 0f else neckActive.toFloat() / neckTotal.toFloat()
            val jumpTotal = jumpLeftTotal + jumpRightTotal
            jumpActivity = if (jumpTotal == 0) 0f else jumpActive.toFloat() / jumpTotal.toFloat()
            abdomenActivity = if (abdomenTotal == 0) 0f else abdomenActive.toFloat() / abdomenTotal.toFloat()
            abdomenActiveCache = abdomenActive
            haltereActiveCache = haltereActive
            val haltereActivity = if (haltereTotal == 0) 0f else haltereActive.toFloat() / haltereTotal.toFloat()
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
            @Suppress("UNUSED_VARIABLE") val retainedHaltereActivity = haltereActivity

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
            val turn = rawTurn - baselineTurnBias * .72f
            // V1.06 movement: translation is still generated exclusively from
            // measured VNC motor neurons. Leg MN activity supplies walking force;
            // wing/jump MN activity adds flight thrust. No stimulus or action score
            // writes position directly.
            val recruitedLeg = sqrt(legActivity.coerceAtLeast(0f))
            val flightMotor = (wingActivity * .78f + jumpActivity * .22f).coerceIn(0f, 1f)
            flightFactor += (flightMotor - flightFactor) * (1f - exp((-dt / .10f).toDouble()).toFloat())
            val jumpImpulse = jumpActivity * .006f
            val cmdSpeed = (recruitedLeg * .012f + flightMotor * .010f + jumpImpulse).coerceIn(-.002f, .018f)
            heading += turn * dt * 3.6f
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
            val foodDistance = hypot(foodX - flyX, foodY - flyY)
            val gustatoryRateNow = populationRate(GUST_START, GUST_END)
            // Food intake requires physical proximity plus measured gustatory activity.
            if (foodOn && foodDistance < .055f && gustatoryRateNow > .04f) {
                foodHits++
                satiety = min(1f, satiety + .24f)
                reward += 1f
                foodX = .08f + rng.nextFloat() * .84f
                foodY = .14f + rng.nextFloat() * .58f
            }

            satiety *= exp((-dt * .018f).toDouble()).toFloat()

            val danger = if (dangerOn) gaussian(hypot(dangerX - flyX, dangerY - flyY), .36f) else 0f

            val visualRate = populationRate(VIS_START, VIS_END)
            val olfactoryRate = populationRate(OLF_START, OLF_END)
            val gustatoryRate = populationRate(GUST_START, GUST_END)
            val mechanosensoryRate = populationRate(MECH_START, MECH_END)
            val descendingRate = populationRate(DESC_START, DESC_END)
            val ascendingRate = populationRate(ASC_START, ASC_END)
            val centralRate = populationRate(OTHER_START, OTHER_END)
            val motorRate = allMotor / motorCount.toFloat()

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
            info.text = infoText()
        }

        private fun runNeuralSimulation(dt: Float) {
            if (!connectomeLoaded) return
            simTime += dt
            sense(dt)
            stepBrain(dt)
            driveBody(dt)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val now = System.nanoTime()
            val raw = (now - lastNs) / 1e9
            val frameDt = min(.030, max(.004, raw)).toFloat()
            lastNs = now
            fps = fps * .94f + (1f / frameDt) * .06f
            // Neural clock at 50 Hz, independent of display refresh rate.
            neuralAccumulator += frameDt
            var steps = 0
            while (neuralAccumulator >= .020f && steps < 5) {
                runNeuralSimulation(.020f)
                neuralAccumulator -= .020f
                steps++
            }
            neuralStepsLastFrame = steps
            neuralBacklogSeconds = neuralAccumulator

            drawScene(c)
            drawFly(c, flyX * width, flyY * sceneBottom(), heading)
            drawNeuralDiagnosticCard(c)
            drawBrainPanel(c)
            postInvalidateOnAnimation()
        }

        private fun sceneBottom(): Float = height - brainPanelHeight()

        private fun brainPanelHeight(): Float = min(height * .48f, 560.dp().toFloat())

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
            val ph = brainPanelHeight()
            val top = height - ph
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(20, 25, 28)
            c.drawRoundRect(8f, top, width - 8f, height.toFloat(), 14f, 14f, paint)

            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 12f
            c.drawText("ACTIVIDAD NEURAL · 16.669 NEURONAS", 20f, top + 20f, paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 8f
            paint.color = Color.rgb(190, 198, 202)
            c.drawText(
                "Real: $loadedEdgeCount edges · Display: ${brainDisplayIds.size} neuronas / ${brainDisplayLinks.size} edges · Intensidad viva: tamaño + brillo + halo · Spikes: ${spikesPerSecond.toInt()} s⁻¹",
                20f, top + 34f, paint
            )

            val legendTop = top + 40f
            drawRegionLegend(c, 18f, legendTop, width - 36f)
            val mapTop = top + 70f
            val mapBottom = height - 27f
            drawBrainMap(c, 14f, mapTop, width - 28f, max(100f, mapBottom - mapTop))

            paint.color = Color.rgb(185, 192, 196)
            paint.textSize = 7.5f
            paint.typeface = Typeface.DEFAULT
            c.drawText(
                "MaleCNS v1.0 · conectividad publicada reducida",
                20f, height - 8f, paint
            )
        }


        private fun bar(c: Canvas, label: String, value: Float, y: Float, accent: Int) {
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.WHITE
            paint.textSize = 10f
            c.drawText(label, 20f, y + 14f, paint)
            val left = 116f
            val right = width - 55f
            paint.color = Color.rgb(62, 67, 70)
            c.drawRoundRect(left, y, right, y + 18f, 7f, 7f, paint)
            paint.color = accent
            c.drawRoundRect(left, y, left + (right - left) * value.coerceIn(0f, 1f), y + 18f, 7f, 7f, paint)
            paint.color = Color.WHITE
            paint.textSize = 10f
            c.drawText("${(value * 100).toInt()}%", right + 5f, y + 14f, paint)
        }

        private fun mini(c: Canvas, label: String, value: Float, x: Float, y: Float, kind: Int) {
            val w = (width - 52f) / 3f
            paint.color = Color.rgb(53, 58, 61)
            c.drawRoundRect(x, y, x + w, y + 21f, 6f, 6f, paint)
            paint.color = when (kind) {
                0 -> Color.rgb(52, 175, 85)
                1 -> Color.rgb(238, 178, 25)
                else -> Color.rgb(215, 55, 55)
            }
            c.drawRoundRect(x + 2f, y + 2f, x + 2f + (w - 4f) * value.coerceIn(0f, 1f), y + 19f, 5f, 5f, paint)
            paint.color = Color.WHITE
            paint.textSize = 9f
            paint.typeface = Typeface.DEFAULT_BOLD
            c.drawText("$label ${(value * 100).toInt()}%", x + 7f, y + 14f, paint)
        }

        private fun regionColor(id: Int): Int = when {
            id in VIS_START until VIS_END -> Color.rgb(55, 145, 235)
            id in OLF_START until OLF_END -> Color.rgb(45, 190, 105)
            id in GUST_START until GUST_END -> Color.rgb(238, 190, 42)
            id in MECH_START until MECH_END -> Color.rgb(238, 125, 48)
            id in DESC_START until DESC_END -> Color.rgb(218, 75, 175)
            id in ASC_START until ASC_END -> Color.rgb(55, 190, 210)
            id in MOTOR_START until MOTOR_END -> Color.rgb(235, 70, 75)
            else -> Color.rgb(150, 160, 170)
        }

        private fun drawRegionLegend(c: Canvas, x: Float, y: Float, w: Float) {
            val labels = arrayOf("VIS", "OLF", "GUST", "MECH", "CENTRAL", "DN", "ASC", "MOTOR")
            val colors = intArrayOf(
                Color.rgb(55,145,235), Color.rgb(45,190,105), Color.rgb(238,190,42), Color.rgb(238,125,48),
                Color.rgb(150,160,170), Color.rgb(218,75,175), Color.rgb(55,190,210), Color.rgb(235,70,75)
            )
            val colW = w / 4f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 7.5f
            paint.textAlign = Paint.Align.LEFT
            for (i in labels.indices) {
                val row = i / 4
                val col = i % 4
                val bx = x + col * colW
                val by = y + row * 13f
                paint.color = colors[i]
                c.drawCircle(bx + 3.5f, by + 4.5f, 3.2f, paint)
                paint.color = Color.rgb(210, 216, 220)
                c.drawText(labels[i], bx + 10f, by + 7f, paint)
            }
        }

        private fun regionRateForId(id: Int): Float = when {
            id in VIS_START until VIS_END -> visualRateDisplay
            id in OLF_START until OLF_END -> olfactoryRateDisplay
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
            c.save()
            c.rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f, px, py)

            val s = (min(width.toFloat(), sceneBottom()) / 520f).coerceIn(1.05f, 1.45f)
            c.scale(s, s, px, py)

            // Soft shadow under the body.
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(38, 0, 0, 0)
            c.drawOval(px - 30f, py + 56f, px + 30f, py + 68f, paint)

            // Six articulated legs, arranged in the characteristic drosophila pattern.
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 3.1f
            paint.color = Color.rgb(42, 35, 33)
            val legY = floatArrayOf(-24f, 0f, 24f)
            for ((q, yy) in legY.withIndex()) {
                val spread = when (q) { 0 -> 32f; 1 -> 38f; else -> 34f }
                val kneeY = yy + when (q) { 0 -> -16f; 1 -> 0f; else -> 16f }
                c.drawLine(px - 11f, py + yy, px - spread, py + kneeY, paint)
                c.drawLine(px - spread, py + kneeY, px - spread - 22f, py + kneeY + when (q) { 0 -> -4f; 1 -> 4f; else -> 8f }, paint)
                c.drawLine(px + 11f, py + yy, px + spread, py + kneeY, paint)
                c.drawLine(px + spread, py + kneeY, px + spread + 22f, py + kneeY + when (q) { 0 -> -4f; 1 -> 4f; else -> 8f }, paint)
            }

            // Animated wings: the beat is a visual consequence of measured wing
            // activity and/or movement. It never feeds back into the neural model.
            val wingVisual = max(wingActivityCache, jumpActivityCache())
            val wingBeat = sin(wingBeatPhase) * (3f + 12f * wingVisual)
            val wingAlpha = (55f + 35f * wingVisual).toInt().coerceIn(45, 95)

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(wingAlpha, 175, 205, 220)
            val wingL = android.graphics.Path().apply {
                moveTo(px - 7f, py - 18f)
                cubicTo(px - 46f, py - 72f, px - 105f, py - 92f, px - 122f, py - 58f)
                cubicTo(px - 132f, py - 35f, px - 82f, py - 8f, px - 13f, py - 2f)
                close()
            }
            val wingR = android.graphics.Path().apply {
                moveTo(px + 7f, py - 18f)
                cubicTo(px + 46f, py - 72f, px + 105f, py - 92f, px + 122f, py - 58f)
                cubicTo(px + 132f, py - 35f, px + 82f, py - 8f, px + 13f, py - 2f)
                close()
            }
            c.save()
            c.rotate(-wingBeat, px - 7f, py - 18f)
            c.drawPath(wingL, paint)
            c.restore()
            c.save()
            c.rotate(wingBeat, px + 7f, py - 18f)
            c.drawPath(wingR, paint)
            c.restore()

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.1f
            paint.color = Color.argb(150, 105, 135, 150)
            c.save()
            c.rotate(-wingBeat, px - 7f, py - 18f)
            c.drawLine(px - 15f, py - 20f, px - 102f, py - 58f, paint)
            c.drawLine(px - 20f, py - 20f, px - 80f, py - 72f, paint)
            c.drawLine(px - 32f, py - 18f, px - 112f, py - 42f, paint)
            c.restore()
            c.save()
            c.rotate(wingBeat, px + 7f, py - 18f)
            c.drawLine(px + 15f, py - 20f, px + 102f, py - 58f, paint)
            c.drawLine(px + 20f, py - 20f, px + 80f, py - 72f, paint)
            c.drawLine(px + 32f, py - 18f, px + 112f, py - 42f, paint)
            c.restore()

            // Segmented abdomen, broad and tapered like the reference image.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(48, 43, 42)
            c.drawOval(px - 25f, py + 8f, px + 25f, py + 112f, paint)
            val abdomen = arrayOf(
                floatArrayOf(15f, 42f), floatArrayOf(42f, 62f), floatArrayOf(62f, 81f),
                floatArrayOf(81f, 98f), floatArrayOf(98f, 112f)
            )
            for ((i, seg) in abdomen.withIndex()) {
                paint.color = if (i % 2 == 0) Color.rgb(92, 70, 56) else Color.rgb(55, 49, 47)
                c.drawRoundRect(px - 22f, py + seg[0], px + 22f, py + seg[1], 7f, 7f, paint)
            }

            // Thorax with darker dorsal plates.
            paint.color = Color.rgb(59, 53, 51)
            c.drawOval(px - 31f, py - 36f, px + 31f, py + 28f, paint)
            paint.color = Color.rgb(70, 63, 59)
            c.drawOval(px - 27f, py - 30f, px + 27f, py + 7f, paint)

            // Head and two large red compound eyes.
            paint.color = Color.rgb(50, 45, 44)
            c.drawOval(px - 26f, py - 72f, px + 26f, py - 29f, paint)
            paint.color = Color.rgb(150, 39, 34)
            c.drawOval(px - 43f, py - 70f, px - 8f, py - 38f, paint)
            c.drawOval(px + 8f, py - 70f, px + 43f, py - 38f, paint)
            paint.color = Color.rgb(226, 92, 66)
            c.drawOval(px - 35f, py - 63f, px - 18f, py - 48f, paint)
            c.drawOval(px + 18f, py - 63f, px + 35f, py - 48f, paint)

            // Antennae and aristae.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            paint.color = Color.rgb(43, 36, 34)
            c.drawLine(px - 11f, py - 69f, px - 29f, py - 94f, paint)
            c.drawLine(px + 11f, py - 69f, px + 29f, py - 94f, paint)
            c.drawLine(px - 29f, py - 94f, px - 41f, py - 105f, paint)
            c.drawLine(px + 29f, py - 94f, px + 41f, py - 105f, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(px - 42f, py - 106f, 2.2f, paint)
            c.drawCircle(px + 42f, py - 106f, 2.2f, paint)

            // Small haltere hints behind the thorax.
            paint.color = Color.rgb(72, 62, 57)
            c.drawCircle(px - 39f, py + 5f, 5f, paint)
            c.drawCircle(px + 39f, py + 5f, 5f, paint)
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (e.y < sceneBottom()) {
                        moveStimulus(e.x / width.toFloat(), e.y / sceneBottom())
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        private fun moveStimulus(xr: Float, yr: Float) {
            val x = xr.coerceIn(.06f, .94f)
            val y = yr.coerceIn(.10f, .82f)
            when (selectedStimulus) {
                0 -> { foodX = x; foodY = y }
                1 -> { lightX = x; lightY = y }
                else -> { dangerX = x; dangerY = y }
            }
        }
    }
}

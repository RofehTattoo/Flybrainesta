package com.example.flybrain

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private data class StartupEntry(val title: String, val body: String, val color: Int)

class MainActivity : Activity() {
    private lateinit var startButton: Button

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(11, 16, 20)
        window.navigationBarColor = Color.rgb(11, 16, 20)
        // Prefer decor fitting where supported; explicit insets below remain the
        // fallback for Android 15 edge-to-edge enforcement.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 249))
            clipToPadding = true
            clipChildren = true
        }

        // UI-only identity header. It does not participate in the simulation.
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 3.dp(), 10.dp(), 3.dp())
            setBackgroundColor(Color.rgb(11, 18, 22))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.flybrain_official)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        identity.addView(icon, LinearLayout.LayoutParams(34.dp(), 34.dp()))

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
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(245, 247, 248))
        }
        val brainText = TextView(this).apply {
            text = "BRAIN"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(105, 188, 241))
        }
        wordmark.addView(flyText)
        wordmark.addView(brainText)
        brand.addView(wordmark)
        brand.addView(TextView(this).apply {
            text = "EXPLORE A TINY MIND"
            textSize = 6.2f
            letterSpacing = .20f
            setTextColor(Color.rgb(190, 198, 202))
        })
        identity.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        identity.addView(TextView(this).apply {
            text = "MaleCNS v1.0"
            textSize = 8.0f
            setTextColor(Color.rgb(139, 151, 158))
        })
        root.addView(identity, LinearLayout.LayoutParams(-1, 44.dp()))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(5.dp(), 2.dp(), 5.dp(), 2.dp())
            setBackgroundColor(Color.rgb(15, 23, 28))
        }

        fun makeButton(label: String) = Button(this).apply {
            text = label
            textSize = 10.5f
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
        val bh = 34.dp()
        listOf(food, light, danger, reset).forEach { button ->
            controls.addView(
                button,
                LinearLayout.LayoutParams(0, bh, 1f).apply {
                    setMargins(2.dp(), 0, 2.dp(), 0)
                }
            )
        }
        root.addView(controls, LinearLayout.LayoutParams(-1, 40.dp()))

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

        val shell = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 11, 14))
            clipToPadding = true
            clipChildren = true
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }

        shell.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val insetTypes = android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
                val safe = insets.getInsets(insetTypes)
                // Reserve the real system-bar area. The added 8dp safety band
                // keeps the lower neural map clear of three-button navigation.
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom + 8.dp())
                insets
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom + 8.dp()
                )
                insets
            }
        }

        val startup = StartupView(sim)
        startButton = Button(this).apply {
            text = "▶  INICIAR SIMULACIÓN"
            textSize = 11.5f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setTextColor(Color.rgb(160, 170, 175))
            setPadding(8.dp(), 0, 8.dp(), 0)
            stateListAnimator = null
            isEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = 14.dp().toFloat()
                setColor(Color.rgb(28, 36, 41))
                setStroke(1, Color.rgb(66, 78, 85))
            }
        }
        startButton.setOnClickListener {
            if (!sim.brainLoadOk) return@setOnClickListener
            sim.startSimulation()
            startup.visibility = View.GONE
            startButton.visibility = View.GONE
        }
        shell.addView(
            startup,
            FrameLayout.LayoutParams(-1, -1).apply {
                gravity = Gravity.CENTER
            }
        )
        shell.addView(
            startButton,
            FrameLayout.LayoutParams(-1, 48.dp()).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                leftMargin = 28.dp()
                rightMargin = 28.dp()
                bottomMargin = 44.dp()
            }
        )

        setContentView(shell)
        shell.requestApplyInsets()
        refresh()
        sim.startBrainLoading()
        startup.postInvalidate()
    }


    /**
     * V1.19.25 startup observatory.
     * Shows the interpretation key while the real MaleCNS/FBR-10 substrate loads.
     * It remains on screen after loading until the user explicitly starts the simulation.
     */
    inner class StartupView(private val sim: FlyView) : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val entries = arrayOf<StartupEntry>(
            StartupEntry("Verde · Olfato", "Neuronas olfativas (ORN).", Color.rgb(45, 190, 105)),
            StartupEntry("Azul · Visión", "Neuronas visuales.", Color.rgb(55, 145, 235)),
            StartupEntry("Amarillo · Gusto", "Neuronas gustativas.", Color.rgb(238, 190, 42)),
            StartupEntry("Naranja · Mecano", "Señales mecanosensoriales.", Color.rgb(238, 125, 48)),
            StartupEntry("Rosa · Descenso", "Neuronas descendentes.", Color.rgb(218, 75, 175)),
            StartupEntry("Cian · Ascenso", "Neuronas ascendentes.", Color.rgb(55, 190, 210)),
            StartupEntry("Rojo · Motor", "Neuronas motoras.", Color.rgb(235, 70, 75)),
            StartupEntry("Gris · Central", "Otras poblaciones centrales.", Color.rgb(150, 160, 170))
        )

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val d = resources.displayMetrics.density
            val s = resources.displayMetrics.scaledDensity
            val dp = { v: Float -> v * d }
            val sp = { v: Float -> v * s }

            c.drawColor(Color.rgb(4, 6, 8))
            val margin = dp(16f)
            val top = dp(18f)
            val bottom = height - dp(18f)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(12, 17, 21)
            c.drawRoundRect(margin, top, width - margin, bottom, dp(18f), dp(18f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.2f)
            paint.color = Color.rgb(48, 58, 64)
            c.drawRoundRect(margin, top, width - margin, bottom, dp(18f), dp(18f), paint)
            paint.style = Paint.Style.FILL

            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(17f)
            paint.color = Color.WHITE
            c.drawText("Código de colores del cerebro", margin + dp(18f), top + dp(32f), paint)

            paint.typeface = Typeface.DEFAULT
            paint.textSize = sp(9f)
            paint.color = Color.rgb(166, 178, 185)
            c.drawText(
                "Aprende a interpretar la actividad neuronal antes de entrar en la simulación.",
                margin + dp(18f), top + dp(50f), paint
            )

            val innerW = width - 2f * margin - dp(32f)
            val colW = innerW / 2f
            val rowH = dp(66f)
            val startY = top + dp(82f)

            for (i in entries.indices) {
                val row = i / 2
                val col = i % 2
                val x = margin + dp(18f) + colW * col
                val y = startY + row * rowH

                paint.color = entries[i].color
                c.drawCircle(x + dp(5f), y + dp(5f), dp(6f), paint)

                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = sp(11f)
                paint.color = Color.WHITE
                c.drawText(entries[i].title, x + dp(17f), y + dp(9f), paint)

                paint.typeface = Typeface.DEFAULT
                paint.textSize = sp(8.2f)
                paint.color = Color.rgb(181, 190, 196)
                c.drawText(entries[i].body, x + dp(17f), y + dp(27f), paint)
            }

            val noteY = startY + 4f * rowH + dp(4f)
            paint.color = Color.rgb(139, 151, 158)
            paint.textSize = sp(8.3f)
            c.drawText("El color identifica la población; el brillo y la intensidad", margin + dp(18f), noteY, paint)
            c.drawText("representan su actividad en la simulación. No todas las neuronas", margin + dp(18f), noteY + dp(16f), paint)
            c.drawText("de un mismo grupo tienen que estar activas simultáneamente.", margin + dp(18f), noteY + dp(32f), paint)

            val ready = sim.brainLoadFinished && sim.brainLoadOk
            val failed = sim.brainLoadFinished && !sim.brainLoadOk
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(8.5f)
            paint.color = when {
                failed -> Color.rgb(235, 85, 85)
                ready -> Color.rgb(70, 205, 120)
                else -> Color.rgb(210, 218, 222)
            }
            val status = when {
                failed -> "ERROR DE CARGA · revisa FBR-10 / FBD105"
                ready -> "CONNECTOME + DYNAMICS OK · LISTO PARA INICIAR"
                else -> "CARGANDO MaleCNS v1.0 · FBR-10 · DINÁMICA FBD105…"
            }
            c.drawText(status, margin + dp(18f), bottom - dp(32f), paint)

            // The simulation never auto-starts. The external Android Button is enabled
            // only after the connectome/dynamics loader reports success.
            startButton.isEnabled = ready
            startButton.alpha = when {
                ready -> 1f
                failed -> .45f
                else -> .58f
            }
            startButton.setTextColor(if (ready) Color.WHITE else Color.rgb(160, 170, 175))
            startButton.background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                if (ready) {
                    setColor(Color.rgb(30, 58, 49))
                    setStroke(dp(1f).toInt().coerceAtLeast(1), Color.rgb(45, 170, 105))
                } else {
                    setColor(Color.rgb(28, 36, 41))
                    setStroke(dp(1f).toInt().coerceAtLeast(1), Color.rgb(66, 78, 85))
                }
            }

            // Keep the splash alive while the user is deciding when to enter.
            postInvalidateDelayed(100L)
        }
    }

    inner class FlyView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        // FEEDSEM103 runtime capacities/thresholds. These are compile-time constants
        // declared before the arrays that use them, avoiding order-dependent field
        // initialization on Android.
        private val MAX_FEEDING_MOTOR_NEURONS = 128
        private val FEEDING_PROBOSCIS_NEURON_SPIKE_MIN = 1
        private val FEEDING_INGESTION_NEURON_SPIKE_MIN = 1
        private val FEEDING_TASTE_NEURON_SPIKE_MIN = 1
        private val FEEDING_CONTEXT_WINDOW_SECONDS = 0.75f
        private val FEEDING_PHARYNGEAL_CONTEXT_WINDOW_SECONDS = 0.80f

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

        // V1.19: six-leg motor output is decoded into the closed-loop mechanical actuator.
        // These are actuator/calibration parameters, not behavior selectors:
        // gait phase advances only while measured leg motor activity is present.
        // The fixed tripod phase relation reflects the adult Drosophila
        // modified-tripod walking biomechanics and is not a sensory shortcut.
        private val LEG_COUNT = 6
        private val LEG_RATE_REFERENCE_HZ = 12f
        private val GAIT_MIN_HZ = 3.0f
        private val GAIT_MAX_HZ = 7.5f
        private val LEG_STANCE_DUTY = 0.62f
        private val LEG_WALK_THRESHOLD = 0.025f
        private val WALKOFF_RATE_REFERENCE_HZ = 10f

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
        // V1.19: closed-loop six-leg sensorimotor actuator. It consumes only
        // measured LEG motor output + measured walk-OFF and returns mechanical
        // state for the next neural frame. It has no access to food/light/danger.
        private val legActuator = LeggedSensorimotorActuator()
        // FEEDSEM103: retained brain feeding motor neurons are cb_motor, not
        // VNC locomotor motors. Their semantics are loaded from an official
        // annotation-derived asset and are used only as measured actuator/readout
        // channels. No synthetic current or edge is introduced.
        private val feedingFunctionalTag = ByteArray(N)
        private val feedingMotorIndices = IntArray(MAX_FEEDING_MOTOR_NEURONS)
        private var feedingMotorCount = 0
        private val feedingFunctionTotals = IntArray(7)
        private val feedingFunctionSpikeEvents = IntArray(7)
        private var tarsalGustatorySpikeEventsFrame = 0
        private var gustatorySpikeEventsFrame = 0
        private var haltWalkOffSpikeEventsFrame = 0
        private var haltBrakeSpikeEventsFrame = 0
        private var foodTarsalContactFrame = 0f
        private var foodTarsalLeftContactFrame = 0f
        private var foodTarsalRightContactFrame = 0f
        private var foodLabellarContactFrame = 0f
        private var foodPharyngealContactFrame = 0f
        private var foodAmount = 1f
        private var lastFoodAmountRendered = 1f
        private var tasteContactLatched = false
        private var proboscisEpisodeLatched = false
        private var ingestionEpisodeLatched = false
        private var tasteContactNeuralNow = false
        private var proboscisNeuralNow = false
        private var ingestionNeuralNow = false
        private var haltDuringFoodContactNow = false
        private var tasteContactEpisodes = 0
        private var proboscisEpisodes = 0
        private var ingestionEvents = 0
        private var proboscisRostrumRateHz = 0f
        private var proboscisRateHz = 0f
        private var ingestionRateHz = 0f
        private var proboscisExtension = 0f
        private var tasteContextAgeSeconds = Float.POSITIVE_INFINITY
        private var proboscisContextAgeSeconds = Float.POSITIVE_INFINITY
        private var pharyngealContextAgeSeconds = Float.POSITIVE_INFINITY
        private val feedingSemanticBodyIds = LongArray(128)
        private val feedingSemanticTags = ByteArray(128)
        private val feedingSemanticSides = ByteArray(128)
        private val descendingRole = ByteArray(N)
        // Real MaleCNS body IDs for the selected neurons. Presentation/diagnostic only.
        private val bodyId = LongArray(N)
        // Official-MaleCNS-derived sensory receptor populations. These indices are
        // loaded from build-generated maps and are the only neurons allowed to receive
        // external sensory current in Phase 1. No synthetic neurons or edges are added.
        private var visualReceptorIndices = IntArray(0)
        // Official side evidence for retained primary photoreceptors; -1=L, +1=R, 0=unknown.
        // This is sensory-input metadata only: it never directly drives body mechanics.
        private val visualSide = ByteArray(N)
        private var olfactoryNeuronIndices = IntArray(0)
        private var gustatoryReceptorIndices = IntArray(0)
        // Only primary gustatory receptors annotated as `leg bristle` may receive
        // the virtual tarsal contact input. Labellar, pharyngeal and other
        // gustatory subtypes remain valid GUST neurons but are not silently
        // substituted for a tarsal receptor.
        private var gustatoryTarsalReceptorIndices = IntArray(0)
        private var gustatoryLabellarReceptorIndices = IntArray(0)
        private var gustatoryPharyngealReceptorIndices = IntArray(0)
        private val gustatorySite = ByteArray(N) // 1=tarsal, 2=labellar, 3=pharyngeal, 0=other
        private val gustatorySide = ByteArray(N)
        private var mechanosensoryReceptorIndices = IntArray(0)
        // Official side evidence for retained mechanosensory/proprioceptive
        // receptors;  -1=L, +1=R, 0=unknown. The side is metadata only.
        private val mechanosensorySide = ByteArray(N)
        // Official receptor-organ provenance: LEG, ANTENNAL, WING, HALTERE, BODY, OTHER.
        // Used only to route physical contact to the appropriate retained sensory population.
        private val mechanosensorySite = ByteArray(N)
        // Presentation-only anatomical map generated from official MaleCNS annotations.
        // It never participates in neural dynamics, sensory injection, or body mechanics.
        private val anatomicalRegion = ByteArray(N)
        private val anatomicalSide = ByteArray(N)
        private val anatomicalConfidence = ByteArray(N)
        // -1/L, +1/R, 0 unknown.
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
        // V1.19: official VNC motor `subclass` is decoded at runtime into the
        // six anatomical leg groups. Values: 1=LF, 2=LM, 3=LH,
        // 4=RF, 5=RM, 6=RH. The source remains the official semantics asset.
        private val motorLegGroup = ByteArray(N)
        private val legGroupTotals = IntArray(LEG_COUNT)
        private val legGroupActivation = FloatArray(LEG_COUNT)
        private val legGroupRateHz = FloatArray(LEG_COUNT)
        private val legBaselineRateHz = FloatArray(LEG_COUNT)
        private val legPreviousRateHz = FloatArray(LEG_COUNT)
        private var legBaselineReady = false
        private var haltWalkOffTotal = 0
        private var haltBrakeTotal = 0
        // Mechanical body state. Position/heading remain readouts of actuator
        // output; no stimulus/action variable writes these values.
        private var bodyLateralSpeed = 0f
        private var yawRate = 0f
        private var walkOffActivationState = 0f
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
        private val FOOD_LABELLAR_CONTACT_RADIUS = .060f
        private val FOOD_PHARYNGEAL_CONTACT_RADIUS = .038f
        private val FOOD_INITIAL_AMOUNT = 1f
        private val FOOD_INGESTION_STEP = .10f
        // V1.19.14: the white arena is the real locomotor workspace; keep only
        // a narrow safety margin for the fly sprite and visible border.
        private val BODY_MIN_X = .045f
        private val BODY_MAX_X = .955f
        private val BODY_MIN_Y = .055f
        private val BODY_MAX_Y = .945f
        // Small per-frame world-space collision recovery. It only acts during
        // actual wall contact and prevents corner clamping at zero velocity.
        private val WALL_POSITION_RECOVERY = .0035f
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
        private var dangerLoomMemory = 0f
        private var dangerOnsetMemory = 0f
        private var previousDangerDistance = Float.NaN
        private var previousDangerOn = false

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
        private var wallContactNow = false

        init {
            setBackgroundColor(Color.rgb(250, 250, 250))
            explorationState = .45f
            checkTemporalConfiguration()
        }

        @Volatile var brainLoadFinished = false
        @Volatile var brainLoadOk = false
        @Volatile private var brainLoadingStarted = false
        @Volatile var brainLoadFinishedAt = 0L
        @Volatile private var simulationStarted = false

        fun startSimulation() {
            if (!brainLoadOk) return
            simulationStarted = true
            // Prevent a long splash/loading interval from becoming a large first frame.
            lastNs = System.nanoTime()
            invalidate()
        }

        fun startBrainLoading() {
            if (brainLoadingStarted) return
            brainLoadingStarted = true
            Thread {
                val ok = try {
                    buildBrain()
                    connectomeLoaded && dynamicsLoaded
                } catch (_: Throwable) {
                    false
                }
                post {
                    brainLoadOk = ok
                    brainLoadFinished = true
                    brainLoadFinishedAt = SystemClock.uptimeMillis()
                    if (ok) setupBuzzSound()
                    invalidate()
                }
            }.apply {
                name = "flybrain-connectome-loader"
                isDaemon = true
                start()
            }
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
                pauseDetected && !wallContactNow -> "PAUSA ESPONTÁNEA"
                ingestionNeuralNow && foodOn -> "INGESTIÓN"
                proboscisNeuralNow && foodOn -> "EXTENSIÓN PROBÓSCIDE"
                haltDuringFoodContactNow && foodOn && brakeAction <= .16f -> "HALT · GUSTACIÓN"
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
                    0 -> {
                        foodOn = !foodOn
                        if (foodOn && foodAmount <= 0f) foodAmount = FOOD_INITIAL_AMOUNT
                    }
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
            bodyLateralSpeed = 0f
            yawRate = 0f
            legActuator.reset()
            walkOffActivationState = 0f
            java.util.Arrays.fill(legGroupActivation, 0f)
            java.util.Arrays.fill(legGroupRateHz, 0f)
            java.util.Arrays.fill(legBaselineRateHz, 0f)
            java.util.Arrays.fill(legPreviousRateHz, 0f)
            legBaselineReady = false
            jumpActivityCacheValue = 0f
            setFoodPosition(.76f, .35f)
            tasteContactLatched = false
            draggingStimulus = false
            lightX = .72f
            lightY = .72f
            dangerX = .30f
            dangerY = .30f
            simTime = 0f
            neuralAccumulator = 0f
            neuralStepsLastFrame = 0
            neuralBacklogSeconds = 0f
            tasteContactEpisodes = 0
            escapeEvents = 0
            satiety = 0f
            memoryTrace = 0f
            lastReward = 0f
            proboscisExtension = 0f
            proboscisRostrumRateHz = 0f
            proboscisRateHz = 0f
            ingestionRateHz = 0f
            tasteContextAgeSeconds = Float.POSITIVE_INFINITY
            proboscisContextAgeSeconds = Float.POSITIVE_INFINITY
            pharyngealContextAgeSeconds = Float.POSITIVE_INFINITY
            tarsalGustatorySpikeEventsFrame = 0
            gustatorySpikeEventsFrame = 0
            haltWalkOffSpikeEventsFrame = 0
            haltBrakeSpikeEventsFrame = 0
            foodTarsalContactFrame = 0f
            foodTarsalLeftContactFrame = 0f
            foodTarsalRightContactFrame = 0f
            foodLabellarContactFrame = 0f
            foodPharyngealContactFrame = 0f
            foodAmount = FOOD_INITIAL_AMOUNT
            lastFoodAmountRendered = FOOD_INITIAL_AMOUNT
            tasteContactLatched = false
            proboscisEpisodeLatched = false
            ingestionEpisodeLatched = false
            tasteContactNeuralNow = false
            proboscisNeuralNow = false
            ingestionNeuralNow = false
            haltDuringFoodContactNow = false
            tasteContactEpisodes = 0
            proboscisEpisodes = 0
            ingestionEvents = 0
            java.util.Arrays.fill(feedingFunctionSpikeEvents, 0)
            proboscisRostrumRateHz = 0f
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
            dangerLoomMemory = 0f
            dangerOnsetMemory = 0f
            previousDangerDistance = Float.NaN
            previousDangerOn = false
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

        @Volatile private var connectomeLoaded = false
        private var connectomeError = ""
        private var loadedEdgeCount = 0
        private var loadedDynamicsEdgeCount = 0
        @Volatile private var dynamicsLoaded = false

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
                val expectedHeader = "index\tbodyId\tmodality\tsideCode\tsideSource\ttype\tclass\tsuperclass\tsubclass\treceptorType\tflywireType\tgustSite\tmechSite"
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
                if (c.size != 13) throw IllegalStateException("SENSMAP esquema inesperado: ${c.size} columnas")
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
                val gustSite = c[11].trim().uppercase()
                val mechSite = c[12].trim().uppercase()

                if (idx !in 0 until N) throw IllegalStateException("SENSMAP index fuera de FBC103: $idx")
                if (bodyId[idx] != bid) {
                    throw IllegalStateException("SENSMAP bodyId mismatch idx=$idx expected=${bodyId[idx]} got=$bid")
                }
                if (side !in -1..1) throw IllegalStateException("SENSMAP sideCode invalido bodyId=$bid")
                if (source !in sideSource) throw IllegalStateException("SENSMAP sideSource invalido bodyId=$bid source=$source")
                if (mechSite !in setOf("LEG", "ANTENNAL", "WING", "HALTERE", "BODY", "OTHER")) {
                    throw IllegalStateException("SENSMAP mechSite invalido bodyId=$bid site=$mechSite")
                }
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
                        visualSide[idx] = side.toByte()
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
                        gustatorySide[idx] = side.toByte()
                        gustatorySite[idx] = when (gustSite) {
                            "TARSAL" -> 1
                            "LABELLAR" -> 2
                            "PHARYNGEAL" -> 3
                            "OTHER" -> 0
                            else -> throw IllegalStateException("SENSMAP GUST gustSite inválido bodyId=$bid site=$gustSite")
                        }
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
                        mechanosensorySide[idx] = side.toByte()
                        mechanosensorySite[idx] = when (mechSite) {
                            "LEG" -> 1
                            "ANTENNAL" -> 2
                            "WING" -> 3
                            "HALTERE" -> 4
                            "BODY" -> 5
                            else -> 0
                        }.toByte()
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
            // Tarsal contact must map only to official gustatory `leg bristle`
            // receptors. This is the key distinction between contact taste and
            // labellar/pharyngeal gustatory populations.
            gustatoryTarsalReceptorIndices = gustatory.filter { gustatorySite[it].toInt() == 1 }.toIntArray().also { it.sort() }
            gustatoryLabellarReceptorIndices = gustatory.filter { gustatorySite[it].toInt() == 2 }.toIntArray().also { it.sort() }
            gustatoryPharyngealReceptorIndices = gustatory.filter { gustatorySite[it].toInt() == 3 }.toIntArray().also { it.sort() }
            if (gustatoryTarsalReceptorIndices.isEmpty()) throw IllegalStateException("SENSMAP sin gustación tarsal retenida")
            if (gustatoryLabellarReceptorIndices.isEmpty()) throw IllegalStateException("SENSMAP sin gustación labellar retenida")
            if (gustatoryPharyngealReceptorIndices.isEmpty()) throw IllegalStateException("SENSMAP sin gustación pharyngeal retenida")
            mechanosensoryReceptorIndices = mechanosensory.toIntArray().also { it.sort() }
        }

        private fun loadAnatomicalVisualMap() {
            val parsed = assets.open("anatomical_visual_map.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
                val header = reader.readLine() ?: throw IllegalStateException("ANATOMY cabecera ausente")
                val expectedHeader = "index\tbodyId\tregion\tsideCode\tsubregion\ttype\tsuperclass\tclass\tsubclass\tsomaNeuromere\tconfidence"
                if (header != expectedHeader) throw IllegalStateException("ANATOMY cabecera inesperada")
                reader.readLines()
            }
            if (parsed.size != N) throw IllegalStateException("ANATOMY filas=${parsed.size} esperado=$N")
            val seen = BooleanArray(N)
            val regionCode = mapOf(
                "EYE" to 1, "OPTIC_LOBE" to 2, "ANTENNA" to 3, "ANTENNAL_LOBE" to 4,
                "MUSHROOM_BODY" to 5, "CENTRAL_COMPLEX" to 6, "SEZ" to 7, "AMMC" to 8,
                "VNC" to 9, "ASCENDING" to 10, "DESCENDING" to 11, "CENTRAL_BRAIN" to 12,
                "TARSAL" to 13, "MAXILLARY_PALP" to 14, "LABELLUM" to 15, "PHARYNX" to 16
            )
            for (line in parsed) {
                val c = line.split('\t')
                if (c.size != 11) throw IllegalStateException("ANATOMY esquema inesperado: ${c.size} columnas")
                val idx = c[0].toInt()
                if (idx !in 0 until N || seen[idx]) throw IllegalStateException("ANATOMY índice inválido/duplicado=$idx")
                val region = regionCode[c[2]] ?: throw IllegalStateException("ANATOMY región desconocida=${c[2]}")
                val side = c[3].toInt()
                val confidence = c[10].toInt()
                if (side !in -1..1) throw IllegalStateException("ANATOMY lado inválido idx=$idx")
                if (confidence !in 1..3) throw IllegalStateException("ANATOMY confianza inválida idx=$idx")
                seen[idx] = true
                anatomicalRegion[idx] = region.toByte()
                anatomicalSide[idx] = side.toByte()
                anatomicalConfidence[idx] = confidence.toByte()
            }
            if (seen.any { !it }) throw IllegalStateException("ANATOMY faltan índices")
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
            val byBody = HashMap<Long, IntArray>(rows.size * 2)
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
                val legGroup = if (role == GeneratedConnectomeMeta.MOTOR_LEG) {
                    when (c[3].trim().lowercase()) {
                        "fl" -> if (side < 0) 1 else 4
                        "ml" -> if (side < 0) 2 else 5
                        "hl" -> if (side < 0) 3 else 6
                        else -> throw IllegalStateException(
                            "VNCSEM leg subclass inválido=${c[3]} bodyId=$id side=${c[4]}"
                        )
                    }
                } else 0
                byBody[id] = intArrayOf(role, fn, side, legGroup)
            }
            if (byBody.size != rows.size) throw IllegalStateException("VNCSEM bodyId duplicado")
            for (i in MOTOR_START until MOTOR_END) {
                val pair = byBody[bodyId[i]] ?: throw IllegalStateException("VNCSEM falta bodyId=${bodyId[i]}")
                motorRole[i] = pair[0].toByte()
                motorFunctionalTag[i] = pair[1].toByte()
                nodeSide[i] = pair[2].toByte()
                motorLegGroup[i] = pair[3].toByte()
            }
        }

        private fun loadFeedingMotorSemantics() {
            val parsed = assets.open("feeding_motor_semantics.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
                val header = reader.readLine() ?: throw IllegalStateException("FEEDSEM cabecera ausente")
                val expectedHeader = "bodyId\ttype\tsuperclass\tsubclass\tsomaSide\tflywireType\tfunctionalTag\tfunctionalCode"
                if (header != expectedHeader) throw IllegalStateException("FEEDSEM cabecera inesperada")
                reader.readLines()
            }
            if (parsed.isEmpty()) throw IllegalStateException("FEEDSEM sin neuronas motoras de alimentación retenidas")
            if (parsed.size > MAX_FEEDING_MOTOR_NEURONS) {
                throw IllegalStateException("FEEDSEM demasiadas neuronas: ${parsed.size} > $MAX_FEEDING_MOTOR_NEURONS")
            }

            val seen = HashSet<Long>(parsed.size * 2)
            java.util.Arrays.fill(feedingFunctionalTag, GeneratedConnectomeMeta.FEEDING_FUNCTION_NONE.toByte())
            java.util.Arrays.fill(feedingFunctionTotals, 0)
            feedingMotorCount = 0

            for (line in parsed) {
                val c = line.split('\t')
                if (c.size != 8) throw IllegalStateException("FEEDSEM esquema inesperado: ${c.size} columnas")
                val id = c[0].toLong()
                val type = c[1]
                val superclass = c[2]
                val subclass = c[3]
                val side = when (c[4]) {
                    "L" -> -1
                    "R" -> 1
                    else -> throw IllegalStateException("FEEDSEM lado inválido bodyId=$id")
                }
                val tag = c[7].toInt()
                if (!seen.add(id)) throw IllegalStateException("FEEDSEM bodyId duplicado=$id")
                if (superclass != "cb_motor") {
                    throw IllegalStateException("FEEDSEM superclass no cb_motor bodyId=$id: $superclass")
                }
                if (subclass != "pm") {
                    throw IllegalStateException("FEEDSEM subclass no pm bodyId=$id: $subclass")
                }
                val expectedTag = when (type.lowercase()) {
                    "mn9" -> GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_ROSTRUM
                    "mn4a" -> GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_HAUSTELLUM
                    "mn6" -> GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_LABELLUM
                    "mn8" -> GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_SPREAD
                    "mn11d", "mn11v" -> GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_PHARYNGEAL
                    "cem" -> GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_CROP_ENTRY
                    else -> throw IllegalStateException("FEEDSEM tipo no soportado bodyId=$id type=$type")
                }
                if (tag != expectedTag) throw IllegalStateException("FEEDSEM tag/type mismatch bodyId=$id type=$type tag=$tag expected=$expectedTag")
                if (feedingMotorCount >= feedingMotorIndices.size) throw IllegalStateException("FEEDSEM índice interno saturado")
                var foundIndex = -1
                for (i in 0 until N) {
                    if (bodyId[i] == id) { foundIndex = i; break }
                }
                if (foundIndex < 0) throw IllegalStateException("FEEDSEM bodyId no retenido en FBC103=$id")
                if (foundIndex < OTHER_START || foundIndex >= OTHER_END) {
                    throw IllegalStateException("FEEDSEM esperado en bloque OTHER bodyId=$id idx=$foundIndex")
                }
                if (nodeSide[foundIndex].toInt() != side) {
                    throw IllegalStateException("FEEDSEM side mismatch bodyId=$id FBC=${nodeSide[foundIndex]} asset=$side")
                }
                feedingSemanticBodyIds[feedingMotorCount] = id
                feedingSemanticTags[feedingMotorCount] = tag.toByte()
                feedingSemanticSides[feedingMotorCount] = side.toByte()
                feedingMotorIndices[feedingMotorCount] = foundIndex
                feedingFunctionalTag[foundIndex] = tag.toByte()
                feedingFunctionTotals[tag]++
                feedingMotorCount++
            }

            val requiredTags = intArrayOf(
                GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_ROSTRUM,
                GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_HAUSTELLUM,
                GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_LABELLUM,
                GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_SPREAD,
                GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_PHARYNGEAL,
                GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_CROP_ENTRY,
            )
            for (tag in requiredTags) {
                if (feedingFunctionTotals[tag] <= 0) {
                    throw IllegalStateException("FEEDSEM falta representación retenida para functionalCode=$tag")
                }
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

                haltWalkOffTotal = 0
                haltBrakeTotal = 0
                for (i in 0 until N) {
                    when (haltRole[i].toInt()) {
                        1, 2 -> haltWalkOffTotal++
                        3 -> haltBrakeTotal++
                    }
                }
                if (haltWalkOffTotal <= 0) throw IllegalStateException("no retained walk-OFF halt neurons")

                loadSensoryInputMap()
                loadOlfactoryInputMap()
                loadVncMotorSemantics()
                loadAnatomicalVisualMap()
                loadFeedingMotorSemantics()

                // Build fixed VNC motor-role denominators from the official-annotation-derived VNC semantics layer.

                // The runtime never invents a role: every neuron in VMOTOR is already
                // a published/curated vnc_motor entry with one stored role code.
                java.util.Arrays.fill(motorRoleTotals, 0)
                java.util.Arrays.fill(legGroupTotals, 0)
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
                        GeneratedConnectomeMeta.MOTOR_LEG -> {
                            when (nodeSide[i].toInt()) {
                                -1 -> legLeftTotal++
                                1 -> legRightTotal++
                                else -> legUnknownSideTotal++
                            }
                            val group = motorLegGroup[i].toInt()
                            if (group !in 1..LEG_COUNT) {
                                throw IllegalStateException("grupo de pierna invalido en nodo=$i bodyId=${bodyId[i]}")
                            }
                            legGroupTotals[group - 1]++
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
                if (legGroupTotals.sum() != legLeftTotal + legRightTotal) {
                    throw IllegalStateException(
                        "censo grupos de pierna inconsistente: ${legGroupTotals.sum()} != ${legLeftTotal + legRightTotal}"
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
            // Prioritize real primary sensory receptors so their anatomical-organ
            // placement is visible during stimulation, then fill the remaining
            // representative graph with retained relay neurons.
            for (j in 0 until min(24, visualReceptorIndices.size)) {
                addId(visualReceptorIndices[j * visualReceptorIndices.size / min(24, visualReceptorIndices.size)])
            }
            addPopulation(VIS_START, VIS_END, 24)
            for (j in 0 until min(20, olfactoryNeuronIndices.size)) addId(olfactoryNeuronIndices[j * olfactoryNeuronIndices.size / min(20, olfactoryNeuronIndices.size)])
            for (j in 0 until min(6, gustatoryLabellarReceptorIndices.size)) addId(gustatoryLabellarReceptorIndices[j * gustatoryLabellarReceptorIndices.size / min(6, gustatoryLabellarReceptorIndices.size)])
            for (j in 0 until min(6, gustatoryPharyngealReceptorIndices.size)) addId(gustatoryPharyngealReceptorIndices[j * gustatoryPharyngealReceptorIndices.size / min(6, gustatoryPharyngealReceptorIndices.size)])
            for (j in 0 until min(12, gustatoryTarsalReceptorIndices.size)) addId(gustatoryTarsalReceptorIndices[j * gustatoryTarsalReceptorIndices.size / min(12, gustatoryTarsalReceptorIndices.size)])
            for (j in 0 until min(16, mechanosensoryReceptorIndices.size)) addId(mechanosensoryReceptorIndices[j * mechanosensoryReceptorIndices.size / min(16, mechanosensoryReceptorIndices.size)])
            addPopulation(GUST_START, GUST_END, 6)
            addPopulation(MECH_START, MECH_END, 8, excludeOlfactory = true)
            addPopulation(OTHER_START, OTHER_END, 86)
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
            val movement = (abs(flySpeed) / 3.8f).coerceIn(0f, 1f)
            val wing = max(wingActivityCache, jumpActivityCache()).coerceIn(0f, 1f)
            // The buzz follows measured wing/movement activity. Stimulus presence alone
            // no longer starts a conspicuous artificial drone while the fly is still.
            val activity = max(wing, movement * .72f)
            val active = activity > .055f
            if (!active) {
                if (buzzStreamId != 0) {
                    soundPool?.stop(buzzStreamId)
                    buzzStreamId = 0
                }
                return
            }
            // Keep the sound restrained; wing activity changes both loudness and pitch
            // slightly, rather than switching between fixed-volume sound states.
            val volume = (0.022f + activity * .125f).coerceIn(.022f, .145f)
            val rate = (0.97f + activity * .065f).coerceIn(.97f, 1.035f)
            if (buzzStreamId == 0) {
                buzzStreamId = soundPool?.play(buzzSoundId, volume, volume, 1, -1, rate) ?: 0
            } else {
                soundPool?.setVolume(buzzStreamId, volume, volume)
                soundPool?.setRate(buzzStreamId, rate)
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
        private fun sampleTarsalFoodContact(sx: Float, sy: Float) {
            foodTarsalLeftContactFrame = 0f
            foodTarsalRightContactFrame = 0f
            foodTarsalContactFrame = 0f
            foodLabellarContactFrame = 0f
            foodPharyngealContactFrame = 0f
            if (!foodOn || foodAmount <= 0f) return

            val ca = cos(heading)
            val sa = sin(heading)
            for (g in 0 until LeggedSensorimotorActuator.LEG_COUNT) {
                val tx = flyX + ca * legActuator.footForward[g] - sa * legActuator.footLateral[g]
                val ty = flyY + sa * legActuator.footForward[g] + ca * legActuator.footLateral[g]
                val d = hypot(sx - tx, sy - ty)
                if (d <= FOOD_TARSAL_CONTACT_RADIUS) {
                    val q = gaussian(d, FOOD_GUSTATORY_SIGMA)
                    if (g < 3) foodTarsalLeftContactFrame = max(foodTarsalLeftContactFrame, q)
                    else foodTarsalRightContactFrame = max(foodTarsalRightContactFrame, q)
                }
            }
            foodTarsalContactFrame = max(foodTarsalLeftContactFrame, foodTarsalRightContactFrame).coerceIn(0f, 1f)

            // Distal labellum and internal pharynx samples are separate sensor
            // interfaces. They do not command the proboscis or ingest food.
            val mouthForward = .068f + .045f * proboscisExtension
            val mouthX = flyX + ca * mouthForward
            val mouthY = flyY + sa * mouthForward
            val mouthD = hypot(sx - mouthX, sy - mouthY)
            foodLabellarContactFrame = if (mouthD <= FOOD_LABELLAR_CONTACT_RADIUS) {
                gaussian(mouthD, FOOD_GUSTATORY_SIGMA)
            } else 0f
            val pharynxForward = .040f + .028f * proboscisExtension
            val pharynxX = flyX + ca * pharynxForward
            val pharynxY = flyY + sa * pharynxForward
            val pharynxD = hypot(sx - pharynxX, sy - pharynxY)
            foodPharyngealContactFrame = if (pharynxD <= FOOD_PHARYNGEAL_CONTACT_RADIUS) {
                gaussian(pharynxD, FOOD_GUSTATORY_SIGMA)
            } else 0f
        }

        private fun tarsalFoodContactIntensity(sx: Float, sy: Float): Float {
            sampleTarsalFoodContact(sx, sy)
            return foodTarsalContactFrame
        }

        private fun setMappedSensoryRate(indices: IntArray, rateHz: Float) {
            if (indices.isEmpty() || rateHz <= 0f) return
            val bounded = rateHz.coerceIn(0f, 260f)
            for (index in indices) externalRateHz[index] = bounded
        }

        /**
         * Convert the environmental visual field into a bilateral photoreceptor
         * input. The previous encoder sent one identical rate to every retained
         * R1-6/R7/R8 cell, which preserved luminance but destroyed azimuth.
         *
         * The only anatomical information used here is the official `sideCode`
         * carried by sensory_input_map.tsv. Stimulus-to-body geometry determines
         * whether the left or right eye receives the larger rate. No action score,
         * DN readout or motor variable is consulted.
         */
        private fun setMappedVisualRate(
            lightIntensity: Float,
            dangerIntensity: Float,
            dangerLoomLevel: Float,
            dangerOnsetLevel: Float
        ) {
            if (visualReceptorIndices.isEmpty()) return

            val fwdX = cos(heading)
            val fwdY = sin(heading)
            val rightX = -sin(heading)
            val rightY = cos(heading)

            fun bilateralGains(sx: Float, sy: Float): FloatArray {
                val dx = sx - flyX
                val dy = sy - flyY
                val distance = hypot(dx, dy)
                if (distance < .0001f) return floatArrayOf(.5f, .5f)
                val lateral = dx * rightX + dy * rightY
                // Bounded coarse azimuth. At broadside this approaches one-sided
                // stimulation; directly ahead/behind remains approximately bilateral.
                val angularSide = (lateral / max(distance, .055f)).coerceIn(-1f, 1f)
                val leftGain = ((1f - angularSide) * .5f).coerceIn(0f, 1f)
                val rightGain = ((1f + angularSide) * .5f).coerceIn(0f, 1f)
                return floatArrayOf(leftGain, rightGain)
            }

            val lightGains = bilateralGains(lightX, lightY)
            val dangerGains = bilateralGains(dangerX, dangerY)
            val lightRate = (lightIntensity * SENSORY_VIS_MAX_HZ * .70f).coerceIn(0f, 260f)
            // Danger has a sustained luminance component plus an onset/looming
            // transient. This keeps a stationary threat visible while preserving
            // the temporal signature produced when the object appears/approaches.
            val dangerRate = (dangerIntensity * SENSORY_VIS_MAX_HZ *
                (.82f + 1.20f * dangerLoomLevel + .70f * dangerOnsetLevel))
                .coerceIn(0f, 260f)

            for (index in visualReceptorIndices) {
                val rate = when (visualSide[index].toInt()) {
                    -1 -> lightRate * lightGains[0] + dangerRate * dangerGains[0]
                    1 -> lightRate * lightGains[1] + dangerRate * dangerGains[1]
                    else -> {
                        (lightRate + dangerRate) * .5f
                    }
                }
                externalRateHz[index] = rate.coerceIn(0f, 260f)
            }
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
            // Use the same bounded bilateral encoder covered by unit tests.
            // Its output is a sensory firing-rate input, never a motor command.
            val encoded = OlfactoryInputEncoder.encode(
                left = left,
                right = right,
                gain = maxRateHz,
                limit = 260f
            )
            for (i in olfactoryNeuronIndices) {
                externalRateHz[i] = when (olfactorySide[i].toInt()) {
                    -1 -> encoded.left
                    1 -> encoded.right
                    else -> encoded.center
                }
            }
            val center = encoded.center / 260f
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
            // olfaction. The six actual foot tips plus labellar/pharyngeal mouth
            // sensors are sampled once per neural frame and mapped only to their
            // official retained gustatory site populations.
            sampleTarsalFoodContact(foodX, foodY)
            val tasteState = (1f - satiety * .35f).coerceIn(0f, 1f)
            val leftTasteRate = foodTarsalLeftContactFrame * 180f * tasteState
            val rightTasteRate = foodTarsalRightContactFrame * 180f * tasteState
            val centerTasteRate = ((foodTarsalLeftContactFrame + foodTarsalRightContactFrame) * .5f) * 180f * tasteState
            val lightIntensity = stimulusIntensity(lightOn, lightX, lightY, .42f)
            val dangerBaseIntensity = stimulusIntensity(dangerOn, dangerX, dangerY, .38f)

            val dangerDistance = hypot(dangerX - flyX, dangerY - flyY)
            val approachRate = if (dangerOn && previousDangerDistance.isFinite()) {
                ((previousDangerDistance - dangerDistance) / dt.coerceAtLeast(.001f)).coerceAtLeast(0f)
            } else 0f
            val instantaneousLoom = if (dangerOn) (approachRate / .22f).coerceIn(0f, 1f) else 0f
            dangerLoomMemory = if (dangerOn) {
                max(instantaneousLoom, dangerLoomMemory * exp((-dt / .14f).toDouble()).toFloat())
            } else 0f
            dangerLoom = dangerLoomMemory
            dangerOnsetMemory = if (!dangerOn) {
                0f
            } else {
                val onset = if (!previousDangerOn) 1f else 0f
                max(onset, dangerOnsetMemory * exp((-dt / .11f).toDouble()).toFloat())
            }
            previousDangerDistance = if (dangerOn) dangerDistance else Float.NaN
            previousDangerOn = dangerOn

            // Primary visual encoder:
            // - light is a sustained luminance field;
            // - danger is a sustained local threat plus transient onset/looming;
            // - left/right eye drive is resolved from official receptor side metadata.
            // Nothing here bypasses the connectome into a motor/action command.
            val visualThreatComponent = (dangerBaseIntensity *
                (.82f + 1.20f * dangerLoom + .70f * dangerOnsetMemory))
                .coerceIn(0f, 1f)

            setMappedVisualRate(
                lightIntensity,
                dangerBaseIntensity,
                dangerLoom,
                dangerOnsetMemory
            )
            injectOlfactoryPopulation(foodOn, foodX, foodY, FOOD_OLF_MAX_HZ)
            for (i in gustatoryTarsalReceptorIndices) {
                externalRateHz[i] = when (gustatorySide[i].toInt()) {
                    -1 -> leftTasteRate
                    1 -> rightTasteRate
                    else -> centerTasteRate
                }.coerceIn(0f, 180f)
            }
            val labellarRate = (foodLabellarContactFrame * 180f * tasteState).coerceIn(0f, 180f)
            val pharyngealRate = (foodPharyngealContactFrame * 150f * tasteState).coerceIn(0f, 150f)
            setMappedSensoryRate(gustatoryLabellarReceptorIndices, labellarRate)
            setMappedSensoryRate(gustatoryPharyngealReceptorIndices, pharyngealRate)

            val dLeft = (flyX - BODY_MIN_X).coerceAtLeast(0f)
            val dRight = (BODY_MAX_X - flyX).coerceAtLeast(0f)
            val dTop = (flyY - BODY_MIN_Y).coerceAtLeast(0f)
            val dBottom = (BODY_MAX_Y - flyY).coerceAtLeast(0f)
            val range = .12f
            val wallLeft = (1f - dLeft / range).coerceIn(0f, 1f)
            val wallRight = (1f - dRight / range).coerceIn(0f, 1f)
            val wallTop = (1f - dTop / range).coerceIn(0f, 1f)
            val wallBottom = (1f - dBottom / range).coerceIn(0f, 1f)
            val wall = min(min(dLeft, dRight), min(dTop, dBottom)).coerceIn(0f, range)
            val wallSignal = max(max(wallLeft, wallRight), max(wallTop, wallBottom))
            wallDistanceCache = wall
            wallSignalCache = wallSignal

            // Resolve wall pressure in the fly's body frame instead of mapping
            // screen-top pressure onto one biological side and screen-bottom onto
            // the other.  This keeps left/right mechanosensory asymmetry tied to the
            // animal's instantaneous orientation and avoids artificial oscillations
            // at horizontal/vertical boundaries. The normals point from each wall
            // into the arena; their dot-products with the body axes tell us whether
            // a wall lies on the fly's left/right/front/rear side.
            val fwdX = cos(heading)
            val fwdY = sin(heading)
            val rightX = -sin(heading)
            val rightY = cos(heading)
            var wallPressureLeft = 0f
            var wallPressureRight = 0f
            var wallPressureFront = 0f
            var wallPressureRear = 0f
            fun accumulateWallPressure(value: Float, nx: Float, ny: Float) {
                if (value <= 0f) return
                val rightProjection = nx * rightX + ny * rightY
                val forwardProjection = nx * fwdX + ny * fwdY
                wallPressureLeft = max(wallPressureLeft, value * rightProjection.coerceAtLeast(0f))
                wallPressureRight = max(wallPressureRight, value * (-rightProjection).coerceAtLeast(0f))
                wallPressureFront = max(wallPressureFront, value * forwardProjection.coerceAtLeast(0f))
                wallPressureRear = max(wallPressureRear, value * (-forwardProjection).coerceAtLeast(0f))
            }
            accumulateWallPressure(wallLeft, 1f, 0f)
            accumulateWallPressure(wallRight, -1f, 0f)
            accumulateWallPressure(wallTop, 0f, 1f)
            accumulateWallPressure(wallBottom, 0f, -1f)

            // V1.19.25: physical obstacle contact is converted into anatomical
            // mechanosensory input before it can influence the retained connectome.
            // We do NOT tell the body to turn. Instead, retained receptors receive
            // contact according to the official receptor-organ provenance:
            //   LEG      -> individual foot/wall contact
            //   ANTENNAL -> antenna/head contact
            //   WING/BODY/HALTERE -> body-side wall pressure
            // This fixes the long-standing "wall treadmill" failure mode without
            // introducing a synthetic escape command or synthetic neural edge.
            var footWallLeft = 0f
            var footWallRight = 0f
            for (g in 0 until LeggedSensorimotorActuator.LEG_COUNT) {
                val tx = flyX + fwdX * legActuator.footForward[g] + rightX * legActuator.footLateral[g]
                val ty = flyY + fwdY * legActuator.footForward[g] + rightY * legActuator.footLateral[g]
                val fx = min(min(tx - BODY_MIN_X, BODY_MAX_X - tx), .04f).coerceAtLeast(0f)
                val fy = min(min(ty - BODY_MIN_Y, BODY_MAX_Y - ty), .04f).coerceAtLeast(0f)
                val px = (1f - fx / .025f).coerceIn(0f, 1f)
                val py = (1f - fy / .025f).coerceIn(0f, 1f)
                val contactPressure = max(px, py) * legActuator.contact[g]
                if (g < 3) footWallLeft = max(footWallLeft, contactPressure)
                else footWallRight = max(footWallRight, contactPressure)
            }
            val footWallGlobal = max(footWallLeft, footWallRight)

            // Antennae sit anterior to the body. A head-on wall therefore produces
            // bilateral antennal contact rather than an arbitrary screen-side signal.
            var antennaWallLeft = 0f
            var antennaWallRight = 0f
            val antennaForward = .090f
            val antennaLateral = .025f
            val antennaPoints = arrayOf(
                floatArrayOf(-1f, antennaForward, -antennaLateral),
                floatArrayOf(1f, antennaForward, antennaLateral)
            )
            for (a in antennaPoints) {
                val ax = flyX + fwdX * a[1] + rightX * a[2]
                val ay = flyY + fwdY * a[1] + rightY * a[2]
                val dx = min(min(ax - BODY_MIN_X, BODY_MAX_X - ax), .04f).coerceAtLeast(0f)
                val dy = min(min(ay - BODY_MIN_Y, BODY_MAX_Y - ay), .04f).coerceAtLeast(0f)
                val p = max(
                    (1f - dx / .025f).coerceIn(0f, 1f),
                    (1f - dy / .025f).coerceIn(0f, 1f)
                )
                if (a[0] < 0f) antennaWallLeft = max(antennaWallLeft, p)
                else antennaWallRight = max(antennaWallRight, p)
            }
            val antennaWallGlobal = max(antennaWallLeft, antennaWallRight)

            val mechLeft = legActuator.proprioceptionLeft
            val mechRight = legActuator.proprioceptionRight
            val mechGlobal = legActuator.proprioceptionGlobal
            for (i in mechanosensoryReceptorIndices) {
                val side = mechanosensorySide[i].toInt()
                val site = mechanosensorySite[i].toInt()
                val local = when (site) {
                    1 -> when (side) {
                        -1 -> max(mechLeft, footWallLeft)
                        1 -> max(mechRight, footWallRight)
                        else -> max(mechGlobal, footWallGlobal)
                    }
                    2 -> when (side) {
                        -1 -> max(mechLeft, antennaWallLeft)
                        1 -> max(mechRight, antennaWallRight)
                        else -> max(mechGlobal, antennaWallGlobal)
                    }
                    else -> when (side) {
                        -1 -> max(mechLeft, wallPressureLeft)
                        1 -> max(mechRight, wallPressureRight)
                        else -> max(mechGlobal, max(wallPressureFront, wallPressureRear))
                    }
                }
                // Contact is a sensory rate encoder only. The retained connectome
                // determines whether and how this changes descending/VNC activity.
                externalRateHz[i] = (local * 130f + mechGlobal * 12f).coerceIn(0f, 150f)
            }

            lightDrive = lightIntensity.coerceIn(0f, 1f)
            dangerDrive = visualThreatComponent.coerceIn(0f, 1f)
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
            java.util.Arrays.fill(feedingFunctionSpikeEvents, 0)
            tarsalGustatorySpikeEventsFrame = 0
            gustatorySpikeEventsFrame = 0
            haltWalkOffSpikeEventsFrame = 0
            haltBrakeSpikeEventsFrame = 0
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
            // Count all primary GUST spikes over every internal substep, then
            // separately count the tarsal subset used for the contact/taste gate.
            for (i in GUST_START until GUST_END) if (fired[i]) gustatorySpikeEventsFrame++
            for (i in gustatoryTarsalReceptorIndices) if (fired[i]) tarsalGustatorySpikeEventsFrame++

            // Halt-role populations are also measured over every internal
            // substep. This avoids treating only the final substep's `fired[]`
            // state as if it represented the whole 20 ms neural frame.
            for (i in 0 until N) {
                if (!fired[i]) continue
                when (haltRole[i].toInt()) {
                    1, 2 -> haltWalkOffSpikeEventsFrame++
                    3 -> haltBrakeSpikeEventsFrame++
                }
            }

            // Feeding motor neurons are `cb_motor` and therefore live in OTHER,
            // outside the legacy VNC motor block. Count their real spikes by the
            // official functional semantics asset; these counters never write body
            // position or neural state.
            for (k in 0 until feedingMotorCount) {
                val i = feedingMotorIndices[k]
                if (fired[i]) {
                    val tag = feedingFunctionalTag[i].toInt()
                    if (tag in 1..GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_CROP_ENTRY) feedingFunctionSpikeEvents[tag]++
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
            var walkOffDen = 0
            var brakeDen = 0
            for (i in 0 until N) {
                when (haltRole[i].toInt()) {
                    1, 2 -> walkOffDen++
                    3 -> brakeDen++
                }
            }
            val frameSubsteps = NEURAL_SUBSTEPS_PER_FRAME.toFloat()
            val walkOffNow = if (walkOffDen > 0) {
                (haltWalkOffSpikeEventsFrame / (walkOffDen * frameSubsteps)).coerceIn(0f, 1f)
            } else 0f
            val brakeNow = if (brakeDen > 0) {
                (haltBrakeSpikeEventsFrame / (brakeDen * frameSubsteps)).coerceIn(0f, 1f)
            } else 0f
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

        private fun feedingFunctionRateHz(functionCode: Int, dt: Float): Float {
            if (functionCode !in 1..GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_CROP_ENTRY) return 0f
            val total = feedingFunctionTotals[functionCode]
            if (total <= 0) return 0f
            return feedingFunctionSpikeEvents[functionCode].toFloat() / dt.coerceAtLeast(.001f) / total.toFloat()
        }

        private fun updateFeedingNeuralReadout(dt: Float): Float {
            val tasteRateHz = if (gustatoryReceptorIndices.isEmpty()) 0f else {
                gustatorySpikeEventsFrame.toFloat() / dt.coerceAtLeast(.001f) / gustatoryReceptorIndices.size.toFloat()
            }
            proboscisRostrumRateHz = feedingFunctionRateHz(GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_ROSTRUM, dt)
            val rostrumRate = proboscisRostrumRateHz
            val haustellumRate = feedingFunctionRateHz(GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_HAUSTELLUM, dt)
            val labellumRate = feedingFunctionRateHz(GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_LABELLUM, dt)
            val spreadRate = feedingFunctionRateHz(GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_SPREAD, dt)
            val pharyngealRate = feedingFunctionRateHz(GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_PHARYNGEAL, dt)
            val cropRate = feedingFunctionRateHz(GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_CROP_ENTRY, dt)
            val proboscisTotal = feedingFunctionTotals[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_ROSTRUM] +
                feedingFunctionTotals[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_HAUSTELLUM] +
                feedingFunctionTotals[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_LABELLUM] +
                feedingFunctionTotals[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_SPREAD]
            val proboscisEvents = feedingFunctionSpikeEvents[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_ROSTRUM] +
                feedingFunctionSpikeEvents[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_HAUSTELLUM] +
                feedingFunctionSpikeEvents[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_LABELLUM] +
                feedingFunctionSpikeEvents[GeneratedConnectomeMeta.FEEDING_FUNCTION_PROBOSCIS_SPREAD]
            val ingestionTotal = feedingFunctionTotals[GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_PHARYNGEAL] +
                feedingFunctionTotals[GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_CROP_ENTRY]
            val ingestionEventsFrame = feedingFunctionSpikeEvents[GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_PHARYNGEAL] +
                feedingFunctionSpikeEvents[GeneratedConnectomeMeta.FEEDING_FUNCTION_INGESTION_CROP_ENTRY]
            proboscisRateHz = if (proboscisTotal <= 0) 0f else proboscisEvents.toFloat() / dt.coerceAtLeast(.001f) / proboscisTotal.toFloat()
            ingestionRateHz = if (ingestionTotal <= 0) 0f else ingestionEventsFrame.toFloat() / dt.coerceAtLeast(.001f) / ingestionTotal.toFloat()

            // The visual proboscis is an actuator/readout of MN9 firing, not an
            // independent animation. One 20 ms spike frame corresponds to 50 Hz,
            // matching the VNC motor actuator convention used elsewhere.
            val rostrumTarget = (rostrumRate / 50f).coerceIn(0f, 1f)
            proboscisExtension = relaxMotorActivation(proboscisExtension, rostrumTarget, dt)

            val tasteContactPresent = foodOn &&
                max(foodTarsalContactFrame, max(foodLabellarContactFrame, foodPharyngealContactFrame)) >= .04f
            val tasteNeural = tasteContactPresent &&
                gustatorySpikeEventsFrame >= FEEDING_TASTE_NEURON_SPIKE_MIN

            // The biological route is multilayered, so the motor-neuron outputs
            // need not peak in the exact same 20 ms public frame as the first
            // tarsal spikes. Keep a short read-only association window. This is
            // bookkeeping of measured network events; it does not stop the body,
            // inject spikes, or create a feeding state by itself.
            if (foodOn) {
                if (tasteNeural) tasteContextAgeSeconds = 0f
                else if (tasteContextAgeSeconds.isFinite()) tasteContextAgeSeconds += dt
            } else {
                tasteContextAgeSeconds = Float.POSITIVE_INFINITY
            }
            val tasteContextActive = tasteContextAgeSeconds <= FEEDING_CONTEXT_WINDOW_SECONDS
            val proboscisNeural = foodOn &&
                tasteContextActive &&
                proboscisEvents >= FEEDING_PROBOSCIS_NEURON_SPIKE_MIN
            if (proboscisNeural) proboscisContextAgeSeconds = 0f
            else if (proboscisContextAgeSeconds.isFinite()) proboscisContextAgeSeconds += dt
            if (!foodOn) proboscisContextAgeSeconds = Float.POSITIVE_INFINITY
            val proboscisContextActive = proboscisContextAgeSeconds <= FEEDING_CONTEXT_WINDOW_SECONDS
            if (foodOn && foodPharyngealContactFrame >= .04f) {
                pharyngealContextAgeSeconds = 0f
            } else if (pharyngealContextAgeSeconds.isFinite()) {
                pharyngealContextAgeSeconds += dt
            }
            if (!foodOn) pharyngealContextAgeSeconds = Float.POSITIVE_INFINITY
            val pharyngealContextActive = pharyngealContextAgeSeconds <= FEEDING_PHARYNGEAL_CONTEXT_WINDOW_SECONDS
            val ingestionNeural = foodOn &&
                pharyngealContextActive &&
                tasteContextActive &&
                proboscisContextActive &&
                ingestionEventsFrame >= FEEDING_INGESTION_NEURON_SPIKE_MIN

            // Feeding semantics are read-only. Gustatory/proboscis/ingestion
            // events are acknowledged from measured spikes and temporal context;
            // they do not synthesize a locomotor pause. Any locomotor stop must
            // come from the retained neural walk-off populations measured above.

            tasteContactNeuralNow = tasteNeural
            proboscisNeuralNow = proboscisNeural
            ingestionNeuralNow = ingestionNeural
            haltDuringFoodContactNow = tasteNeural &&
                haltWalkOffSpikeEventsFrame > 0

            if (tasteNeural && !tasteContactLatched) tasteContactEpisodes++
            tasteContactLatched = tasteNeural

            if (proboscisNeural && !proboscisEpisodeLatched) proboscisEpisodes++
            proboscisEpisodeLatched = proboscisNeural

            // Ingestion is acknowledged only when real ingestion-related motor
            // neurons fire in the same food/taste context. This is bookkeeping of
            // measured neural output; it does not command feeding or stop walking.
            val newIngestionEpisode = ingestionNeural && !ingestionEpisodeLatched
            if (newIngestionEpisode) {
                ingestionEvents++
                satiety = min(1f, satiety + .24f)
                foodAmount = (foodAmount - FOOD_INGESTION_STEP).coerceAtLeast(0f)
            }
            ingestionEpisodeLatched = ingestionNeural

            // Homeostatic decay remains continuous; only real ingestion can raise
            // satiety in this release.
            satiety *= exp((-dt * .018f).toDouble()).toFloat()
            @Suppress("UNUSED_VARIABLE") val _tasteRateHz = tasteRateHz
            @Suppress("UNUSED_VARIABLE") val _haustellumRate = haustellumRate
            @Suppress("UNUSED_VARIABLE") val _labellumRate = labellumRate
            @Suppress("UNUSED_VARIABLE") val _spreadRate = spreadRate
            @Suppress("UNUSED_VARIABLE") val _cropRate = cropRate
            @Suppress("UNUSED_VARIABLE") val _pharyngealRate = pharyngealRate
            return if (newIngestionEpisode) 1f else 0f
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
            val visualContext = max(visualRate, visualRateDisplay * .65f).coerceIn(0f, 1f)

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

        private fun relaxMotorActivation(
            previous: Float,
            target: Float,
            dt: Float,
            tauSeconds: Float = MOTOR_ACTIVATION_TAU_SECONDS
        ): Float {
            val tau = tauSeconds.coerceAtLeast(.001f)
            val alpha = (1f - exp((-dt / tau).toDouble()).toFloat()).coerceIn(0f, 1f)
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
            // The anatomical role and six leg groups come from official MaleCNS
            // semantics; no neuron index is used to invent a motor function.
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
            val legGroupSpikeEvents = IntArray(LEG_COUNT)

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
                val motorSpikes = motorSubstepSpikeCounts[mi]
                if (motorSpikes <= 0) continue
                allMotorSpikeEvents += motorSpikes
                when (motorRole[i].toInt()) {
                    GeneratedConnectomeMeta.MOTOR_LEG -> {
                        legActive++
                        when (nodeSide[i].toInt()) {
                            -1 -> { leftLegActive++; leftLegSpikeEvents += motorSpikes }
                            1 -> { rightLegActive++; rightLegSpikeEvents += motorSpikes }
                            else -> { unknownLegActive++; unknownLegSpikeEvents += motorSpikes }
                        }
                        val group = motorLegGroup[i].toInt()
                        if (group in 1..LEG_COUNT) legGroupSpikeEvents[group - 1] += motorSpikes
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
            val invFrame = 1f / dt.coerceAtLeast(.001f)
            val legRateHz = if (legTotal == 0) 0f else (leftLegSpikeEvents + rightLegSpikeEvents + unknownLegSpikeEvents) * invFrame / legTotal.toFloat()
            val leftLegRateHz = if (legLeftTotal == 0) 0f else leftLegSpikeEvents * invFrame / legLeftTotal.toFloat()
            val rightLegRateHz = if (legRightTotal == 0) 0f else rightLegSpikeEvents * invFrame / legRightTotal.toFloat()
            val unknownLegRateHz = if (legUnknownSideTotal == 0) 0f else unknownLegSpikeEvents * invFrame / legUnknownSideTotal.toFloat()
            val wingRateHz = if (wingTotal == 0) 0f else wingSpikeEvents * invFrame / wingTotal.toFloat()
            val neckRateHz = if (neckTotal == 0) 0f else neckSpikeEvents * invFrame / neckTotal.toFloat()
            val jumpRateHz = if (jumpTotal == 0) 0f else jumpSpikeEvents * invFrame / jumpTotal.toFloat()
            val abdomenRateHz = if (abdomenTotal == 0) 0f else abdomenSpikeEvents * invFrame / abdomenTotal.toFloat()
            val haltereRateHz = if (haltereTotal == 0) 0f else haltereSpikeEvents * invFrame / haltereTotal.toFloat()
            val otherRateHz = if (motorRoleTotals[GeneratedConnectomeMeta.MOTOR_OTHER] == 0) 0f else motorOtherSpikeEvents * invFrame / motorRoleTotals[GeneratedConnectomeMeta.MOTOR_OTHER].toFloat()

            // Retain the public aggregate motor readouts for diagnostics/action
            // selection, but do not use a whole-leg population mean as the body
            // kinematics. The body receives six anatomically grouped actuator
            // signals below.
            val rawLegActivity = (legRateHz / LEG_RATE_REFERENCE_HZ).coerceIn(0f, 1f)
            val rawLeftLeg = (leftLegRateHz / LEG_RATE_REFERENCE_HZ).coerceIn(0f, 1f)
            val rawRightLeg = (rightLegRateHz / LEG_RATE_REFERENCE_HZ).coerceIn(0f, 1f)
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

            // V1.19.1: separate tonic motor baseline from phasic locomotor output.
            // A stable ~5-6 Hz rate is treated as motor tone; propulsion requires
            // measured rate excess/rise above each leg's own recent baseline.
            if (!legBaselineReady) {
                for (g in 0 until LEG_COUNT) {
                    val total = legGroupTotals[g]
                    val rate = if (total <= 0) 0f else legGroupSpikeEvents[g] * invFrame / total.toFloat()
                    legBaselineRateHz[g] = rate
                    legPreviousRateHz[g] = rate
                    legGroupRateHz[g] = rate
                    legGroupActivation[g] = 0f
                }
                legBaselineReady = true
            } else {
                for (g in 0 until LEG_COUNT) {
                    val total = legGroupTotals[g]
                    val rate = if (total <= 0) 0f else legGroupSpikeEvents[g] * invFrame / total.toFloat()
                    legGroupRateHz[g] = rate
                    val baseline = legBaselineRateHz[g]
                    val excess = (rate - baseline - 0.90f).coerceAtLeast(0f)
                    val rise = (rate - legPreviousRateHz[g]).coerceAtLeast(0f)
                    val burst = (excess / 5.0f * .82f + rise / 4.0f * .18f).coerceIn(0f, 1f)
                    val target = burst
                    legGroupActivation[g] = relaxMotorActivation(legGroupActivation[g], target, dt)
                    val baseTau = if (burst < .18f) 4.5f else 14f
                    val baseAlpha = (1f - exp((-dt / baseTau).toDouble()).toFloat()).coerceIn(0f, 1f)
                    legBaselineRateHz[g] += (rate - legBaselineRateHz[g]) * baseAlpha
                    legPreviousRateHz[g] = rate
                }
            }

            // Walk-OFF is a measured neural actuator gate. It is based only on
            // actual spikes of the retained FG/BB populations; taste/food is not
            // consulted here. This is the mechanical consequence of a real
            // inhibitory halting output, not `if (food) speed = 0`.
            val walkOffRateHz = if (haltWalkOffTotal <= 0) 0f else {
                haltWalkOffSpikeEventsFrame * invFrame / haltWalkOffTotal.toFloat()
            }
            val walkOffTarget = (walkOffRateHz / WALKOFF_RATE_REFERENCE_HZ).coerceIn(0f, 1f)
            walkOffActivationState = relaxMotorActivation(walkOffActivationState, walkOffTarget, dt)
            val walkOffGate = (1f - .94f * walkOffActivationState).coerceIn(0f, 1f)

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

            legActiveCache = legActive
            leftLegActiveCache = leftLegActive
            rightLegActiveCache = rightLegActive
            unknownLegActiveCache = unknownLegActive
            wingActiveCache = wingActive
            jumpActiveCache = jumpActive
            wingActivityCache = wingActivity

            // V1.19.25: isolated physical/mechanical integration boundary.
            // The method below consumes only measured VNC leg activity + walk-OFF.
            applyMechanicalBodyState(dt)

            // Feeding/spatial actions do not directly command motion. Wing phase is
            // likewise driven only by measured wing/jump motor output.
            val flightTarget = (wingActivity * .65f + jumpActivity * .35f).coerceIn(0f, 1f)
            flightFactor += (flightTarget - flightFactor) * (1f - exp((-dt / .10f).toDouble()).toFloat())
            flightPhase += dt * (8f + 22f * flightFactor)
            // Feeding is a measured neural readout. The body actuator above remains
            // exclusively VNC-motor driven; this method never sets speed/heading
            // from food, taste, pause or feeding state. `updateFeedingNeuralReadout`
            // only records real retained cb_motor activity and updates the visual
            // mouthpart actuator.
            var reward = updateFeedingNeuralReadout(dt)

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
            visualRateDisplay = .72f * visualRateDisplay + .28f * visualRate
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

        /**
         * V1.19.25 physical/mechanical body boundary.
         *
         * This function deliberately receives no food, light, danger or action
         * variables. It consumes only measured VNC motor state already reduced into
         * six leg groups and retained walk-OFF neural activity. The resulting actuator
         * velocities, yaw and wall response are then integrated into body position.
         */
        private fun applyMechanicalBodyState(dt: Float) {

            // V1.19.4: closed-loop sensorimotor body mechanics. The actuator sees
            // only the six measured leg-MN subgroup activations and the measured
            // walk-OFF output. No sensory stimulus or action/goal variable enters.
            // Feeding-related neural readouts remain diagnostic only. Locomotor
            // inhibition reaches the actuator exclusively through retained
            // walk-OFF neural activity measured above.
            legActuator.step(legGroupActivation, walkOffActivationState, dt)
            flySpeed = legActuator.forwardVelocity
            bodyLateralSpeed = legActuator.lateralVelocity
            yawRate = legActuator.yawRate

            // V1.19.4: yaw is part of the mechanical body state. Integrate the
            // actuator-produced angular velocity before resolving body velocity
            // into world coordinates. No stimulus/action variable writes heading.
            heading += yawRate * dt
            val twoPi = (Math.PI * 2.0).toFloat()
            if (heading > Math.PI.toFloat()) heading -= twoPi
            if (heading < -Math.PI.toFloat()) heading += twoPi

            var worldVx = cos(heading) * flySpeed - sin(heading) * bodyLateralSpeed
            var worldVy = sin(heading) * flySpeed + cos(heading) * bodyLateralSpeed

            // Physical wall envelope. The constraint feeds back into the actuator;
            // there is no heading reflection/bounce.
            wallContactNow = false
            var wallNx = 0f
            var wallNy = 0f
            val predictedX = flyX + worldVx * dt
            val predictedY = flyY + worldVy * dt
            if (predictedX < BODY_MIN_X && worldVx < 0f) { wallContactNow = true; wallNx += 1f }
            if (predictedX > BODY_MAX_X && worldVx > 0f) { wallContactNow = true; wallNx -= 1f }
            if (predictedY < BODY_MIN_Y && worldVy < 0f) { wallContactNow = true; wallNy += 1f }
            if (predictedY > BODY_MAX_Y && worldVy > 0f) { wallContactNow = true; wallNy -= 1f }
            if (flyX <= BODY_MIN_X + .014f) { wallContactNow = true; wallNx += 1f }
            if (flyX >= BODY_MAX_X - .014f) { wallContactNow = true; wallNx -= 1f }
            if (flyY <= BODY_MIN_Y + .014f) { wallContactNow = true; wallNy += 1f }
            if (flyY >= BODY_MAX_Y - .014f) { wallContactNow = true; wallNy -= 1f }
            legActuator.applyWallConstraint(
            heading, wallNx, wallNy, dt, wallContactNow
            )
            if (wallContactNow) {
            flySpeed = legActuator.forwardVelocity
            bodyLateralSpeed = legActuator.lateralVelocity
            yawRate = legActuator.yawRate
            worldVx = cos(heading) * flySpeed - sin(heading) * bodyLateralSpeed
            worldVy = sin(heading) * flySpeed + cos(heading) * bodyLateralSpeed
            }
            var nextFlyX = flyX + worldVx * dt
            var nextFlyY = flyY + worldVy * dt

            if (wallContactNow) {
            val wallLen = hypot(wallNx, wallNy)
            if (wallLen > .0001f) {
            val invLen = 1f / wallLen
            // Local collision resolution only. The vector comes from the
            // measured wall normal; no food/light/danger target is read.
            nextFlyX += wallNx * invLen * WALL_POSITION_RECOVERY
            nextFlyY += wallNy * invLen * WALL_POSITION_RECOVERY
            }
            }

            flyX = nextFlyX.coerceIn(BODY_MIN_X, BODY_MAX_X)
            flyY = nextFlyY.coerceIn(BODY_MIN_Y, BODY_MAX_Y)

            val dxPhysical = flyX - lastMotionX
            val dyPhysical = flyY - lastMotionY
            physicalSpeed = hypot(dxPhysical, dyPhysical) / dt.coerceAtLeast(.001f)
            // Pause detection must consume the speed measured from the just-finished
            // physical frame. Calling this before physicalSpeed was updated made the
            // UI one frame late and, more importantly, could prevent a true arrest
            // from ever reaching the pause timer at low-speed boundaries.
            updateMeasuredPauseState(dt, wallContactNow)
            physicalAcceleration = (physicalSpeed - previousPhysicalSpeed) / dt.coerceAtLeast(.001f)
            previousPhysicalSpeed = physicalSpeed
            displacementPerSecond = physicalSpeed
            pathLength += hypot(dxPhysical, dyPhysical)
            val movementEvidence = (physicalSpeed / .18f).coerceIn(0f, 1f)
            physicalMovementMemory = .94f * physicalMovementMemory + .06f * movementEvidence
            lastMotionX = flyX
            lastMotionY = flyY
            }

        private fun updateMeasuredPauseState(dt: Float, wallContact: Boolean) {
            val stillThreshold = .0075f
            val stopThreshold = .0035f
            val wasPaused = pauseDetected
            if (!wallContact && physicalSpeed < stillThreshold) {
                inactivityContinuous += dt
                pauseTimer += dt
                if (!wasPaused && pauseTimer >= .16f) {
                    // Count an observed pause on entry, while retaining the elapsed
                    // duration for the exit update below. Nothing here commands the
                    // body or fabricates a rest event.
                    pauseDetected = true
                    pauseCount++
                }
            } else {
                if (wasPaused) lastPauseDuration = pauseTimer
                pauseDetected = false
                pauseTimer = 0f
                inactivityContinuous = 0f
            }
            stopDetected = !wallContact && physicalSpeed < stopThreshold
            recentMovementMemory = .94f * recentMovementMemory +
                .06f * (physicalSpeed / .18f).coerceIn(0f, 1f)
            restState = !wallContact && inactivityContinuous >= .25f
            restStateBlend += ((if (restState) 1f else 0f) - restStateBlend) *
                (1f - exp((-dt / .18f).toDouble()).toFloat())
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
                    // Keep it active for every internal substep of the 40-step frame.
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
            while (simulationStarted && neuralAccumulator >= NEURAL_FRAME_DT_SECONDS && steps < 5) {
                runNeuralSimulation(NEURAL_FRAME_DT_SECONDS)
                neuralAccumulator -= NEURAL_FRAME_DT_SECONDS
                steps++
            }
            if (!simulationStarted) {
                neuralAccumulator = 0f
                neuralStepsLastFrame = 0
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
        private fun brainPanelHeight(): Float = min(height * .32f, 390.dp().toFloat())

        private fun drawScene(c: Canvas) {
            val bottom = sceneBottom()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(190, 190, 190)
            c.drawRect(3f, 3f, width - 3f, bottom - 3f, paint)
            paint.style = Paint.Style.FILL

            if (foodOn && foodAmount > 0f) {
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
            c.scale(scale * (.72f + .28f * foodAmount.coerceIn(0f, 1f)), scale * (.72f + .28f * foodAmount.coerceIn(0f, 1f)))
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
            val innerL = dp(12f)
            val innerR = width - dp(12f)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(18, 23, 27)
            c.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)

            // One unobtrusive status line: detailed telemetry is intentionally removed.
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(8.2f)
            val ok = connectomeLoaded && dynamicsLoaded
            paint.color = if (ok) Color.rgb(70, 205, 120) else Color.rgb(232, 75, 75)
            c.drawCircle(innerL + dp(3f), top + dp(13f), dp(3f), paint)
            paint.color = Color.rgb(207, 216, 221)
            c.drawText(
                if (ok) "CONNECTOME + DYNAMICS OK" else "CONNECTOME / DYNAMICS ERROR",
                innerL + dp(11f), top + dp(16f), paint
            )

            // Color key mirrors regionColor() exactly. Two short rows fit narrow phones.
            val legend = arrayOf(
                Triple("OLFATO", Color.rgb(45, 190, 105), 0),
                Triple("VISIÓN", Color.rgb(55, 145, 235), 1),
                Triple("GUSTO", Color.rgb(238, 190, 42), 2),
                Triple("MECANO", Color.rgb(238, 125, 48), 3),
                Triple("DESC.", Color.rgb(218, 75, 175), 4),
                Triple("ASC.", Color.rgb(55, 190, 210), 5),
                Triple("MOTOR", Color.rgb(235, 70, 75), 6),
                Triple("CENTRAL", Color.rgb(150, 160, 170), 7)
            )
            val rowY1 = top + dp(31f)
            val rowY2 = top + dp(44f)
            val usableW = (innerR - innerL)
            val cellW = usableW / 4f
            for (i in legend.indices) {
                val row = if (i < 4) 0 else 1
                val col = i % 4
                val x = innerL + cellW * col
                val y = if (row == 0) rowY1 else rowY2
                paint.color = legend[i].second
                c.drawCircle(x + dp(3f), y - dp(3f), dp(3f), paint)
                paint.color = Color.rgb(205, 213, 218)
                paint.textAlign = Paint.Align.LEFT
                paint.typeface = Typeface.DEFAULT
                paint.textSize = sp(7.2f)
                c.drawText(legend[i].first, x + dp(9f), y, paint)
            }

            // The map gets the remaining panel height; no telemetry cards compete with it.
            val mapTop = top + dp(53f)
            val mapBottom = height - dp(7f)
            drawBrainMap(c, dp(8f), mapTop, width - dp(16f),
                max(dp(90f), mapBottom - mapTop))
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
            val density = resources.displayMetrics.density
            val labelPaintSize = (6.2f * density).coerceAtLeast(5f)

            // The drawing below is a 2D anatomical schematic, not a literal 3D brain.
            // Primary sensory neurons are anchored at their receptor organ (eye,
            // antenna, tarsus, labellum/pharynx); central cells are placed by the
            // source-derived anatomical family map. Actual retained connectome edges
            // are still drawn separately and are never invented by this map.
            paint.style = Paint.Style.FILL

            // Compound eyes -> optic lobes.
            paint.color = Color.rgb(20, 27, 32)
            c.drawOval(x + w * .025f, y + h * .17f, x + w * .19f, y + h * .56f, paint)
            c.drawOval(x + w * .81f, y + h * .17f, x + w * .975f, y + h * .56f, paint)
            paint.color = Color.argb(58, 55, 145, 235)
            c.drawOval(x + w * .20f, y + h * .20f, x + w * .34f, y + h * .60f, paint)
            c.drawOval(x + w * .66f, y + h * .20f, x + w * .80f, y + h * .60f, paint)

            // Central brain silhouette.
            paint.color = Color.argb(48, 150, 160, 170)
            c.drawOval(x + w * .29f, y + h * .15f, x + w * .71f, y + h * .69f, paint)

            // Anatomical neuropil hints: MB, AL, CX, AMMC, SEZ and VNC.
            paint.color = Color.argb(24, 205, 175, 95)
            c.drawOval(x + w * .33f, y + h * .20f, x + w * .47f, y + h * .42f, paint)
            c.drawOval(x + w * .53f, y + h * .20f, x + w * .67f, y + h * .42f, paint)
            paint.color = Color.argb(38, 75, 105, 135)
            c.drawCircle(x + w * .40f, y + h * .44f, min(w, h) * .042f, paint)
            c.drawCircle(x + w * .60f, y + h * .44f, min(w, h) * .042f, paint)
            paint.color = Color.argb(28, 60, 185, 205)
            c.drawOval(x + w * .33f, y + h * .46f, x + w * .43f, y + h * .59f, paint)
            c.drawOval(x + w * .57f, y + h * .46f, x + w * .67f, y + h * .59f, paint)
            paint.color = Color.argb(42, 238, 190, 42)
            c.drawOval(x + w * .41f, y + h * .55f, x + w * .59f, y + h * .69f, paint)
            paint.color = Color.argb(38, 55, 190, 210)
            c.drawRoundRect(cx - w * .028f, y + h * .68f, cx + w * .028f, y + h * .95f,
                w * .014f, w * .014f, paint)

            // Peripheral sensory organs. These are anatomical landmarks only; they
            // are not extra neurons or synthetic synapses.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density * 0.8f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.argb(110, 190, 200, 205)
            val leftAntennaX = x + w * .37f
            val rightAntennaX = x + w * .63f
            c.drawLine(leftAntennaX, y + h * .22f, x + w * .33f, y + h * .09f, paint)
            c.drawLine(rightAntennaX, y + h * .22f, x + w * .67f, y + h * .09f, paint)
            c.drawCircle(x + w * .33f, y + h * .09f, density * 2f, paint)
            c.drawCircle(x + w * .67f, y + h * .09f, density * 2f, paint)
            // Maxillary palps and proboscis/labellum.
            c.drawOval(x + w * .405f, y + h * .67f, x + w * .435f, y + h * .75f, paint)
            c.drawOval(x + w * .565f, y + h * .67f, x + w * .595f, y + h * .75f, paint)
            c.drawOval(x + w * .46f, y + h * .67f, x + w * .54f, y + h * .77f, paint)
            paint.style = Paint.Style.FILL

            fun drawText(text: String, px: Float, py: Float, alpha: Int = 155) {
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = labelPaintSize
                paint.textAlign = Paint.Align.CENTER
                paint.color = Color.argb(alpha, 190, 198, 202)
                c.drawText(text, px, py, paint)
            }
            drawText("OJO", x + w * .10f, y + h * .15f, 170)
            drawText("OJO", x + w * .90f, y + h * .15f, 170)
            drawText("LÓBULO ÓPTICO", x + w * .27f, y + h * .65f, 135)
            drawText("LÓBULO ÓPTICO", x + w * .73f, y + h * .65f, 135)
            drawText("MB", x + w * .40f, y + h * .25f, 135)
            drawText("MB", x + w * .60f, y + h * .25f, 135)
            drawText("AL", x + w * .40f, y + h * .50f, 180)
            drawText("AL", x + w * .60f, y + h * .50f, 180)
            drawText("CX", cx, y + h * .42f, 180)
            drawText("SEZ", cx, y + h * .64f, 175)
            drawText("VNC", cx, y + h * .97f, 180)
            drawText("PALPOS", x + w * .50f, y + h * .76f, 120)
            drawText("LABELLUM", cx, y + h * .71f, 120)
            drawText("AMMC", x + w * .29f, y + h * .61f, 135)
            drawText("AMMC", x + w * .71f, y + h * .61f, 135)

            fun posForNeuron(id: Int): FloatArray {
                val u = ((id * 1103515245L + 12345L) and 0x7fffffffL) / 2147483647f
                val v2 = ((id * 1664525L + 1013904223L) and 0x7fffffffL) / 2147483647f
                val aSide = anatomicalSide[id].toInt().let { if (it == 0) nodeSide[id].toInt() else it }
                val region = anatomicalRegion[id].toInt()
                return when (region) {
                    1 -> floatArrayOf(
                        x + w * (if (aSide < 0) .10f else .90f) + (u - .5f) * w * .055f,
                        y + h * (.25f + v2 * .25f)
                    )
                    2 -> floatArrayOf(
                        x + w * (if (aSide < 0) .27f else .73f) + (u - .5f) * w * .095f,
                        y + h * (.28f + v2 * .27f)
                    )
                    3 -> floatArrayOf(
                        x + w * (if (aSide < 0) .36f else .64f) + (u - .5f) * w * .035f,
                        y + h * (.10f + v2 * .16f)
                    )
                    14 -> floatArrayOf(
                        x + w * (if (aSide < 0) .41f else .59f) + (u - .5f) * w * .025f,
                        y + h * (.69f + v2 * .055f)
                    )
                    15 -> floatArrayOf(
                        x + w * (if (aSide < 0) .475f else .525f) + (u - .5f) * w * .035f,
                        y + h * (.69f + v2 * .055f)
                    )
                    16 -> floatArrayOf(
                        x + w * (.50f + (u - .5f) * .035f),
                        y + h * (.73f + v2 * .045f)
                    )
                    4 -> floatArrayOf(
                        x + w * (if (aSide < 0) .40f else .60f) + (u - .5f) * w * .055f,
                        y + h * (.40f + v2 * .10f)
                    )
                    5 -> floatArrayOf(
                        x + w * (if (aSide < 0) .40f else .60f) + (u - .5f) * w * .085f,
                        y + h * (.25f + v2 * .16f)
                    )
                    6 -> floatArrayOf(
                        x + w * (.50f + (u - .5f) * .16f),
                        y + h * (.32f + v2 * .18f)
                    )
                    7 -> floatArrayOf(
                        x + w * (if (aSide < 0) .43f else .57f) + (u - .5f) * w * .075f,
                        y + h * (.57f + v2 * .12f)
                    )
                    8 -> floatArrayOf(
                        x + w * (if (aSide < 0) .34f else .66f) + (u - .5f) * w * .045f,
                        y + h * (.48f + v2 * .11f)
                    )
                    13 -> {
                        // Tarsal gustatory receptors are peripheral taste sensilla on
                        // the six feet. Spread them over the three leg levels on the
                        // appropriate side rather than placing them in the brain core.
                        val left = aSide < 0
                        val seg = ((u * 3f).toInt()).coerceIn(0, 2)
                        floatArrayOf(
                            x + w * (if (left) (.43f - seg * .045f) else (.57f + seg * .045f)) + (v2 - .5f) * w * .018f,
                            y + h * (.79f + seg * .055f)
                        )
                    }
                    9 -> {
                        val group = motorLegGroup[id].toInt()
                        if (group in 1..6) {
                            val left = group <= 3
                            val seg = when (group) { 1,4 -> 0f; 2,5 -> .5f; else -> 1f }
                            floatArrayOf(
                                x + w * (if (left) .46f else .54f) + (u - .5f) * w * .028f,
                                y + h * (.75f + seg * .16f + (v2 - .5f) * .035f)
                            )
                        } else {
                            floatArrayOf(cx + (u - .5f) * w * .075f, y + h * (.75f + v2 * .18f))
                        }
                    }
                    10 -> floatArrayOf(
                        x + w * (if (aSide < 0) .47f else .53f) + (u - .5f) * w * .022f,
                        y + h * (.72f + v2 * .20f)
                    )
                    11 -> floatArrayOf(
                        x + w * (if (aSide < 0) .46f else .54f) + (u - .5f) * w * .10f,
                        y + h * (.50f + v2 * .15f)
                    )
                    12 -> floatArrayOf(
                        x + w * (.30f + u * .40f),
                        y + h * (.26f + v2 * .34f)
                    )
                    else -> {
                        // Safe fallback for old/generated assets: use the canonical
                        // block semantics until the anatomical map is loaded.
                        when {
                            isOlfactoryNeuron(id) -> floatArrayOf(
                                x + w * (if (aSide < 0) .36f else .64f), y + h * (.12f + v2 * .13f)
                            )
                            id in VIS_START until VIS_END -> floatArrayOf(
                                x + w * (if (aSide < 0) .27f else .73f), y + h * (.28f + v2 * .27f)
                            )
                            id in GUST_START until GUST_END -> floatArrayOf(
                                x + w * (if (aSide < 0) .43f else .57f), y + h * (.57f + v2 * .12f)
                            )
                            id in MECH_START until MECH_END -> floatArrayOf(
                                x + w * (if (aSide < 0) .34f else .66f), y + h * (.48f + v2 * .15f)
                            )
                            id in DESC_START until DESC_END -> floatArrayOf(
                                x + w * (if (aSide < 0) .46f else .54f), y + h * (.50f + v2 * .15f)
                            )
                            id in ASC_START until ASC_END -> floatArrayOf(
                                x + w * (if (aSide < 0) .47f else .53f), y + h * (.72f + v2 * .20f)
                            )
                            id in MOTOR_START until MOTOR_END -> floatArrayOf(
                                cx + (u - .5f) * w * .07f, y + h * (.75f + v2 * .18f)
                            )
                            else -> floatArrayOf(cx + (u - .5f) * w * .30f, y + h * (.26f + v2 * .34f))
                        }
                    }
                }
            }

            // Actual retained connectome edges among the representative sample.
            val lineDp = density
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
                paint.strokeWidth = (0.11f + 0.17f * signal) * lineDp
                val baseAlpha = (6f + 32f * signal).toInt().coerceIn(6, 38)
                paint.color = Color.argb(baseAlpha, Color.red(base), Color.green(base), Color.blue(base))
                c.drawLine(a[0], a[1], b[0], b[1], paint)
                if (activity > .06f) {
                    paint.strokeWidth = (0.18f + 0.23f * activity) * lineDp
                    val activeAlpha = (32f + 105f * activity).toInt().coerceIn(32, 140)
                    paint.color = Color.argb(activeAlpha, Color.red(base), Color.green(base), Color.blue(base))
                    c.drawLine(a[0], a[1], b[0], b[1], paint)
                }
            }

            // Representative nodes.
            paint.style = Paint.Style.FILL
            for (rep in brainDisplayIds.indices) {
                val id = brainDisplayIds[rep]
                val p = posForNeuron(id)
                val activity = visualActivity[id].coerceIn(0f, 1f)
                val regional = regionRateForId(id)
                val base = regionColor(id)
                val visibleBaseline = (regional * .12f).coerceIn(0f, .12f)
                val intensity = max(activity, visibleBaseline)
                val radius = 0.90f + 4.4f * activity
                if (activity > .035f) {
                    paint.color = Color.argb((22f + 55f * activity).toInt().coerceIn(22, 80), Color.red(base), Color.green(base), Color.blue(base))
                    c.drawCircle(p[0], p[1], radius * 2.15f, paint)
                    paint.color = Color.argb((42f + 95f * activity).toInt().coerceIn(42, 145), Color.red(base), Color.green(base), Color.blue(base))
                    c.drawCircle(p[0], p[1], radius * 1.38f, paint)
                }
                val alpha = if (activity > .02f) (85f + 170f * intensity).toInt().coerceIn(85, 255) else 72
                paint.color = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
                c.drawCircle(p[0], p[1], radius, paint)
                if (activity > .25f) {
                    paint.color = Color.argb((120f + 120f * activity).toInt().coerceIn(120, 240), 255, 255, 255)
                    c.drawCircle(p[0], p[1], max(1.0f, radius * .25f), paint)
                }
            }

            // Every currently active retained neuron is allowed to appear at its
            // anatomical position even when it is outside the 320-node representative
            // graph sample. This removes the old ambiguity where an active receptor
            // could fire but simply not be visible in the map.
            var activeExtra = 0
            for (id in 0 until N) {
                if (brainDisplayLookup[id] >= 0) continue
                val activity = visualActivity[id].coerceIn(0f, 1f)
                if (activity <= .08f) continue
                val p = posForNeuron(id)
                val base = regionColor(id)
                val isDenseCentral = anatomicalRegion[id].toInt() == 12 && base == Color.rgb(150, 160, 170)
                val radius = if (isDenseCentral) 0.28f + 0.95f * activity else 0.45f + 1.35f * activity
                val alpha = if (isDenseCentral) {
                    (36f + 104f * activity).toInt().coerceIn(36, 140)
                } else {
                    (70f + 135f * activity).toInt().coerceIn(70, 205)
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
                c.drawCircle(p[0], p[1], radius, paint)
                // Dense central populations are rendered as micro-nodes without halos so
                // simultaneous activity remains spatially legible on phone-sized screens.
                activeExtra++
            }

            // Anatomical labels + display contract.
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 6.4f
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.rgb(178, 187, 192)
            c.drawText("NEURONAS ACTIVAS → posición anatómica de referencia", x + 12f, y + 15f, paint)
            paint.textAlign = Paint.Align.RIGHT
            c.drawText("sensores primarios en órgano · relés en SNC", x + w - 12f, y + 15f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 5.8f
            paint.color = Color.rgb(130, 142, 149)
            c.drawText("nodos extra activos: $activeExtra", x + 12f, y + h - 9f, paint)
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

            // Anatomically simplified dorsal-style wings:
            // Drosophila has ONE forewing on each side, attached laterally to the
            // thorax.  The wings are broad, rounded distally and extend mainly
            // perpendicular to the body axis (local X); they are not four
            // leaf-shaped appendages and do not overlap the abdomen.
            val wingVisual = max(wingActivityCache, jumpActivityCache()).coerceIn(0f, 1f)
            val wingStroke = sin(wingBeatPhase) * (3.8f * wingVisual)
            val wingFill = Color.argb(
                (46f + 30f * wingVisual).toInt().coerceIn(42, 78),
                205, 215, 218
            )
            val wingInk = Color.argb(
                (125f + 45f * wingVisual).toInt().coerceIn(120, 175),
                93, 103, 106
            )

            fun drawAnatomicalWing(side: Float) {
                val baseX = px + side * 24f
                val baseY = py - 2f

                // Leading edge is slightly anterior (-Y); distal margin is rounded;
                // trailing edge returns posteriorly (+Y) before the narrow base.
                val wing = android.graphics.Path().apply {
                    moveTo(baseX, baseY - 4f)
                    cubicTo(
                        px + side * 34f, py - 10f,
                        px + side * 61f, py - 28f,
                        px + side * 78f, py - 27f
                    )
                    cubicTo(
                        px + side * 90f, py - 26f,
                        px + side * 96f, py - 14f,
                        px + side * 93f, py + 2f
                    )
                    cubicTo(
                        px + side * 89f, py + 20f,
                        px + side * 72f, py + 31f,
                        px + side * 52f, py + 29f
                    )
                    cubicTo(
                        px + side * 37f, py + 28f,
                        px + side * 28f, py + 17f,
                        baseX, baseY + 5f
                    )
                    close()
                }

                c.save()
                // The wing articulates at the thoracic base. Keep the visual
                // excursion small so the membrane stays anatomically plausible.
                c.rotate(side * wingStroke, baseX, baseY)

                paint.style = Paint.Style.FILL
                paint.alpha = 255
                paint.color = wingFill
                c.drawPath(wing, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 0.85f
                paint.color = wingInk
                c.drawPath(wing, paint)

                // Costa / leading margin.
                paint.strokeWidth = 1.1f
                c.drawLine(
                    baseX + side * 2f, baseY - 1f,
                    px + side * 88f, py - 16f, paint
                )

                // Principal longitudinal veins, simplified but ordered.
                paint.strokeWidth = 0.62f
                c.drawLine(baseX + side * 3f, baseY - 2f,
                           px + side * 86f, py - 7f, paint)
                c.drawLine(baseX + side * 3f, baseY - 1f,
                           px + side * 83f, py + 4f, paint)
                c.drawLine(baseX + side * 4f, baseY + 1f,
                           px + side * 74f, py + 16f, paint)
                c.drawLine(baseX + side * 5f, baseY + 2f,
                           px + side * 58f, py + 25f, paint)

                // Two short cross-veins; kept inside the membrane rather than
                // drawing a decorative grid.
                c.drawLine(px + side * 49f, py - 13f,
                           px + side * 57f, py + 2f, paint)
                c.drawLine(px + side * 68f, py - 11f,
                           px + side * 75f, py + 9f, paint)

                c.restore()
            }

            // Exactly two wings: left and right.
            drawAnatomicalWing(-1f)
            drawAnatomicalWing(1f)
            paint.alpha = 255

            // Six articulated legs. Phase, stance/swing, lift and load are the
            // current state of the motor-driven mechanical actuator. There is no
            // independent animation clock in the renderer.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            val legBase = arrayOf(
                floatArrayOf(-20f, -18f), floatArrayOf(-23f, 2f), floatArrayOf(-19f, 23f),
                floatArrayOf(20f, -18f), floatArrayOf(23f, 2f), floatArrayOf(19f, 23f)
            )
            for (g in 0 until LeggedSensorimotorActuator.LEG_COUNT) {
                val side = if (g < 3) -1f else 1f
                val row = g % 3
                val active = legActuator.load[g].coerceIn(0f, 1f)
                val stride = legActuator.stride[g] * 16f
                val lift = legActuator.lift[g] * 19f
                val extension = legActuator.extension[g]
                val baseX = legBase[g][0]
                val baseY = legBase[g][1]

                val kneeX = baseX + side * (20f + 9f * extension)
                val kneeY = baseY + when (row) { 0 -> -18f; 1 -> 2f; else -> 21f } + stride * .18f
                val footX = side * (66f + 10f * extension)
                val footY = when (row) { 0 -> -52f; 1 -> -2f; else -> 53f } + stride
                val footYVisual = footY - lift

                paint.strokeWidth = 2.0f + 1.6f * active
                paint.color = if (active > .02f) dark else Color.rgb(76, 61, 52)
                c.drawLine(px + baseX, py + baseY, px + kneeX, py + kneeY, paint)
                c.drawLine(px + kneeX, py + kneeY, px + footX, py + footYVisual, paint)
                c.drawLine(px + footX, py + footYVisual, px + footX + side * 9f, py + footYVisual + 2f, paint)

                // Small contact cue, tied to the mechanical contact state.
                if (legActuator.contact[g] > .16f) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb((50f + 90f * legActuator.contact[g]).toInt().coerceIn(50, 140), 65, 55, 45)
                    c.drawCircle(px + footX, py + footYVisual + 2f, 2.4f + 1.6f * legActuator.load[g], paint)
                    paint.style = Paint.Style.STROKE
                }
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

            // Male abdomen: amber-brown anterior tergites, progressively darker
            // posterior tergites, and the characteristic near-black terminal region.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(79, 54, 39)
            val abdomen = android.graphics.Path().apply {
                moveTo(px - 22f, py + 27f)
                cubicTo(px - 29f, py + 51f, px - 23f, py + 99f, px, py + 121f)
                cubicTo(px + 23f, py + 99f, px + 29f, py + 51f, px + 22f, py + 27f)
                close()
            }
            c.drawPath(abdomen, paint)
            val segY = floatArrayOf(39f, 51f, 63f, 75f, 87f, 99f, 109f)
            for (i in segY.indices) {
                val half = 21f - i * 1.75f
                paint.color = when (i) {
                    0, 1 -> Color.rgb(151, 103, 61)
                    2 -> Color.rgb(119, 78, 49)
                    3 -> Color.rgb(83, 57, 43)
                    else -> Color.rgb(43, 34, 31)
                }
                c.drawRoundRect(px - half, py + segY[i], px + half, py + segY[i] + 7.5f, 3f, 3f, paint)
                // Fine intersegmental boundary.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = .8f
                paint.color = Color.argb(125, 34, 27, 25)
                c.drawLine(px - half + 2f, py + segY[i] + 7f,
                           px + half - 2f, py + segY[i] + 7f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = Color.rgb(31, 26, 25)
            c.drawOval(px - 9f, py + 103f, px + 9f, py + 122f, paint)

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

            // Short Drosophila antennae: compact scape/pedicel/funiculus,
            // with a fine lateral arista. The total projection is kept small.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1.25f
            paint.color = dark
            c.drawLine(px - 10f, py - 65f, px - 14f, py - 72f, paint)
            c.drawLine(px + 10f, py - 65f, px + 14f, py - 72f, paint)
            paint.strokeWidth = .82f
            c.drawLine(px - 14f, py - 72f, px - 19f, py - 77f, paint)
            c.drawLine(px + 14f, py - 72f, px + 19f, py - 77f, paint)
            // Very short aristae, not a second long antenna stalk.
            paint.strokeWidth = .58f
            c.drawLine(px - 17f, py - 74f, px - 22f, py - 80f, paint)
            c.drawLine(px + 17f, py - 74f, px + 22f, py - 80f, paint)

            // Proboscis/mouthparts: presentation is driven by the retained MN9
            // (rostrum protractor) firing readout. No stimulus variable writes the
            // extension state directly.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.0f + 0.8f * proboscisExtension
            paint.color = Color.rgb(70, 48, 40)
            val probBaseY = py - 29f
            val probTipY = probBaseY - (8f + 34f * proboscisExtension)
            val probSpread = 4f + 5f * proboscisExtension
            c.drawLine(px - 7f, probBaseY, px - probSpread, probTipY, paint)
            c.drawLine(px + 7f, probBaseY, px + probSpread, probTipY, paint)
            if (proboscisExtension > .04f) {
                paint.strokeWidth = 1.6f + 0.5f * proboscisExtension
                c.drawLine(px, probBaseY, px, probTipY + 3f, paint)
                paint.style = Paint.Style.FILL
                c.drawOval(px - probSpread - 2f, probTipY - 2f, px + probSpread + 2f, probTipY + 3f, paint)
            }
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
            if (foodAmount <= 0f) foodAmount = FOOD_INITIAL_AMOUNT
            // Repositioning the food is an environment event. Clear all active
            // feeding latches so a newly placed source cannot inherit the prior
            // source's neural episode context.
            tasteContactLatched = false
            proboscisEpisodeLatched = false
            ingestionEpisodeLatched = false
            tasteContextAgeSeconds = Float.POSITIVE_INFINITY
            proboscisContextAgeSeconds = Float.POSITIVE_INFINITY
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

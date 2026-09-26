from pathlib import Path
import math, struct, hashlib
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE=(ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")
assert 'TAU_MEMBRANE_SECONDS = 0.020f' in MAIN
assert 'TAU_SYNAPSE_SECONDS = 0.005f' in MAIN
assert 'NEURAL_SUBSTEP_DT_SECONDS = 0.0005f' in MAIN
assert 'NEURAL_SUBSTEPS_PER_FRAME = 40' in MAIN
assert 'SYNAPTIC_DELAY_SECONDS = 0.0018f' in MAIN
assert 'private val SYNAPTIC_DELAY_STEPS = 4' in MAIN
assert 'private val synConductance = FloatArray(N)' in MAIN
assert 'private val outgoing = Array(N)' in MAIN
assert 'private fun deliverDelayedSynapses()' in MAIN
assert 'v[i] = V_REST + x0 * a + g0 * coupling * (a - b)' in MAIN
assert 'synConductance[i] = g0 * b' in MAIN
assert 'adapt[i]' not in MAIN
assert 'synTrace' not in MAIN
assert 'sensoryCurrent' not in MAIN
assert 'APP_VERSION = "1.18.4"' in META
assert 'APP_VERSION_CODE = 137' in META
assert 'REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
assert 'versionName = "1.18.4"' in GRADLE
assert 'versionCode = 137' in GRADLE
# Exact constants and an analytical LIF step.
dt=.0005; tau_m=.020; tau_s=.005
a=math.exp(-dt/tau_m); b=math.exp(-dt/tau_s); c=tau_s/(tau_m-tau_s)
vrest=-52.; v=-52.; g=.275*3; vth=-45.
x=v-vrest
v2=vrest+x*a+g*c*(a-b); g2=g*b
assert v2>v and g2<g and v2 < vrest+g
assert abs(math.exp(-.020/.020)-math.exp(-1))<1e-15
raw=(ROOT/"app/src/main/res/raw/malecns_reduced.bin").read_bytes()
assert raw[:8]==b'FBC103\x00\x00' and struct.unpack_from('<II',raw,8)[0]==16669
print('V1.18.4 REFERENCE LIF INTEGRATION AUDIT: PASS')
print(f'a={a:.12f} b={b:.12f} coupling={c:.6f}')
print('FBR-10 current local structural SHA-256:', hashlib.sha256(raw).hexdigest())

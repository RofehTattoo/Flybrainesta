# Remaining release checks

The source-level corrections in this package are complete to the extent supported by the available audit snapshot and source code. The canonical MaleCNS-derived graph/dynamics/map artifacts are intentionally regenerated in CI from the pinned official Feather sources.

The following artifacts were not present in that snapshot and therefore were not invented:

- `app/src/main/assets/sensory_input_map.tsv`
- `app/src/main/assets/olfactory_input_map.tsv`
- `app/src/main/assets/vnc_motor_semantics.tsv`
- the repository's original Gradle wrapper files
- the original `app/build.gradle.kts`

The canonical build workflow regenerates these from the pinned MaleCNS v1.0 inputs. The FBC103 structural artifact itself is present and verified.

Do not mark the APK as a final scientific release until CI has regenerated FBC103/FBD105 and the runtime sensory/VNC maps from the pinned MaleCNS v1.0 sources, validated their hashes/provenance, and assembled the APK.


## 2026-09-25 — video-driven motor diagnosis

The latest user-tested APK was V1.17.1/FBR-10-OLF1. In the recorded food trial the fly remained effectively fixed in position while sensory and sparse neural activity continued; leg-MN output was near zero and the strongest displayed motor neuron was abdominal. The video therefore does not support a body-mechanics-only diagnosis.

The current source baseline is V1.18.3/FBR-10-OLF2-MOTORROUTE with reference-style LIF/alpha-synapse integration, authoritative VNC motor semantics, signed FBD105 dynamics, explicit multi-hop preservation, and a measured-MN actuator time constant of 40 ms. No direct action→body shortcut is introduced.


## 2026-09-25 — V1.17.1 root-cause finding

Inspection of the actually tested V1.17.1 APK/video established that the old FBD104 dynamics layer normalized the absolute signed synaptic input of every postsynaptic neuron to a total of only 0.42 mV across recognized fast-synaptic contacts. In the reference-style LIF equation, that ceiling is far below the 7 mV rest-to-threshold gap, so ordinary downstream neurons could remain subthreshold even when ORN receptors were externally spiking. This is a concrete dynamics-layer failure, not evidence that `approachAction` should drive the body.

V1.18.3 removes that normalization by using raw retained MaleCNS contact counts multiplied by the published `W_syn = 0.275 mV`, and adds a CI test that inspects the generated FBD105 artifact for signed ORN→DN and DN→LEG paths plus excitatory input reaching LEG motor neurons.

# Remaining release checks

The source-level corrections in this package are complete to the extent supported by the uploaded audit snapshot.

The following artifacts were not present in that snapshot and therefore were not invented:

- `app/src/main/res/raw/malecns_fbr10_dynamics.bin`
- `app/src/main/assets/sensory_input_map.tsv`
- `app/src/main/assets/olfactory_input_map.tsv`
- `app/src/main/assets/vnc_motor_semantics.tsv`
- the repository's original Gradle wrapper files
- the original `app/build.gradle.kts`

The canonical build workflow regenerates these from the pinned MaleCNS v1.0 inputs. The FBC103 structural artifact itself is present and verified.

Do not mark the APK as a final scientific release until the generated FBD104 and runtime maps have been regenerated from the current FBR-10-OLF1 SHA and validated.

**Current development release:** V1.16.2-FOOD-OLFACTORY-TEMPORAL / versionCode 130. Anatomical OLF input map derived from official MaleCNS v1.0 annotations; FBC103/FBD104/VNC semantics remain frozen.

# FlyBrain V1.15.2 — VNC Motor Semantics

**Current development release:** V1.15.2 / versionCode 121. This release corrects only the VNC motor semantics/body translation layer. FBR-10/FBC103 and FBD104 remain frozen/independent.

The runtime anatomical VNC map is generated from the exact official MaleCNS v1.0 annotation Feather (`body-annotations-male-cns-v1.0-minconf-0.5.feather`, SHA-256 `2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2`) and packaged by CI as `vnc_motor_semantics.tsv`.

---

# FlyBrain V1.14 — FBR-10 frozen baseline

**CONNECTOME FROZEN:** MaleCNS v1.0 → FBR-10 → 16,669 neurons → 2,064,951 published edges / 16,783,932 contacts.

See `FBR10_CONNECTOME_FINAL_AUDIT.md`. The CI validates the shipped binary by exact SHA-256 and does not regenerate it with the historical V1.13 reducer.

# FlyBrain V1.13

## Objetivo
V1.07 mantiene exactamente 16.669 neuronas del MaleCNS v1.0 y da prioridad explícita a la preservación de rutas funcionales medidas en el conectoma reducido. La selección ya no depende únicamente de grado/superclase: se conservan preferentemente neuronas intermedias que participan en rutas de dos saltos `sensor -> célula -> DN` y `DN -> célula -> MN`, separando familias de avance, orientación/giro, escape y sensorimotora general.

El movimiento del cuerpo virtual sigue procediendo exclusivamente de actividad medida de neuronas motoras VNC retenidas. Las etiquetas de DN/MN y las puntuaciones de ruta son metadatos derivados del conectoma y no crean conexiones artificiales.

## Fuente científica
MaleCNS v1.0 — Janelia/FlyEM. Dataset CC-BY-4.0.
https://male-cns.janelia.org/download/

Berg et al., *Cell* (2026), “Sexual dimorphism in the complete connectome of the Drosophila male central nervous system”, DOI 10.1016/j.cell.2026.08.015.

## V1.07 — calibración de ganancia y movimiento

V1.07 no añade neuronas ni conexiones. Se centra exclusivamente en dos aspectos:

1. **Ganancia sináptica calibrada:** la eficacia de las conexiones ya retenidas se ajusta lentamente dentro de límites acotados, usando la actividad medida de las poblaciones OTHER/central, ASC, DN y VNC-motor. La calibración solo actúa mientras existe estimulación sensorial; el reposo no dispara una subida indefinida de ganancia. El objetivo es mantener actividad escasa y evitar tanto la red muerta como la saturación.
2. **Movimiento derivado del VNC:** la traslación continúa procediendo únicamente de neuronas motoras VNC medidas. La actividad de piernas aporta locomoción; la actividad motora de alas/salto aporta empuje de vuelo y un pequeño movimiento vertical. No existe ningún comando directo estímulo→posición.

La lógica del conectoma, sus 16.669 neuronas, las conexiones retenidas y el formato FBC103 permanecen intactos.

## Arquitectura V1.07

### Selección connectómica
- 16.669 neuronas exactamente.
- Todos los `descending_neuron` y `vnc_motor` anotados se conservan cuando forman parte del censo trazado.
- Se conserva diversidad de tipos publicados.
- Se añade selección por rutas de dos saltos medidas en el grafo publicado.
- Se priorizan rutas sensorial→forward, visual→turn, visual/mecano→escape y DN→intermedia→MN.
- Las aristas embebidas son exclusivamente aristas publicadas entre neuronas retenidas.

### Metadatos de rutas
El formato FBC103 añade tres valores `Float` por neurona:
- `routeForward`
- `routeTurn`
- `routeEscape`

Son puntuaciones topológicas normalizadas calculadas a partir de rutas de dos saltos del grafo completo. Se utilizan para selección y diagnóstico; no modifican la conectividad.

### Dinámica neuronal
- LIF con paso neural fijo de 20 ms (50 Hz).
- Traza sináptica de corta duración (~45 ms).
- Sin plasticidad experimental por defecto.
- El límite de corriente sináptica se aplica después de la ganancia poblacional para evitar la saturación prematura que se observó en V1.01.
- El estado locomotor interno sigue siendo modulador y no escribe directamente posición, velocidad ni rumbo.

### Sensores
- Visual: codificación espacial y direccional.
- Olfativo: concentración con asimetría bilateral.
- Gustativo: contacto/proximidad muy cercana.
- Mecanosensorial: amenaza/contacto y feedback de límites.
- PELIGRO incorpora una componente visual de expansión lenta (looming) y una componente mecanosensorial; ninguna de ellas escribe una orden motora.

### Selección de acción
La aproximación ya no equivale a “caminar”: necesita evidencia neural de avance y contexto olfativo/gustativo.

El escape utiliza conjuntamente:
- actividad de DN etiquetados como escape;
- actividad de células intermedias con puntuación de ruta de escape;
- actividad motora defensiva medida.

El salto deja de ser la definición exclusiva de escape.

## Integridad
El binario FBC103 tiene:
- cabecera de 8 bytes;
- 16 bytes de cabecera total;
- registros de nodo de 25 bytes;
- registros de arista de 12 bytes;
- validación de tamaño exacto;
- validación de todos los índices;
- validación de todos los pesos finitos y no nulos;
- validación de los metadatos de ruta.

Si el connectome falla al cargar, la aplicación entra en `fail-closed`: no se crea una red neuronal alternativa.

## V1.13 — instrumentación neural y lectura causal del cuerpo

V1.13 corrige un problema detectado en el vídeo de V1.12 FIXED4: la interfaz no permitía distinguir con suficiente claridad entre actividad neuronal, actividad motora y movimiento físico. El panel inferior ahora muestra métricas de spikes reales, conexiones cargadas y conexiones representadas, y RESET limpia también la actividad visual y los estímulos ambientales.

La locomoción se lee desde el desplazamiento físico de la mosca, no desde una mezcla de actividad de patas, cuello o salto. Se elimina además la corriente neuronal estocástica de fondo de V1.12 para evitar confundir ruido artificial con actividad emergente.

No se añaden neuronas ni conexiones sintéticas: la reducción sigue siendo de 16.669 neuronas y utiliza exclusivamente aristas retenidas del MaleCNS v1.0.

## V1.12 — arquitectura homeostática y locomotora revisada

V1.12 corrige el controlador de reposo de las versiones anteriores. El cambio principal no es bajar o subir un umbral, sino separar cuatro fenómenos: presión homeostática, arousal, estado de reposo y pausa locomotora medida.

- `sleepPressure` acumula necesidad de descanso durante la vigilia y se descarga exponencialmente durante el reposo. No existe un valor porcentual que fuerce por sí mismo una transición.
- `arousalDrive` representa activación de vigilia y puede suprimir temporalmente la expresión del descanso sin borrar la presión acumulada.
- La entrada/salida de `REST` sigue siendo probabilística y continua; no utiliza los antiguos umbrales 70%/22%.
- Las pausas locomotoras ya no son creadas por un temporizador aleatorio externo. La aplicación las detecta a partir de la salida motora VNC real.
- La evidencia de neuronas descendentes de halting (rol 3) puede aumentar únicamente la eficacia de las sinapsis DN→MN que ya existen en el conectoma retenido. No se añaden aristas artificiales ni se congela directamente la posición de la mosca.
- Se registra duración de inactividad y número de pausas para poder calibrar el comportamiento con pruebas reproducibles.

Esta separación permite distinguir una pausa locomotora breve de un reposo prolongado y evita que peligro/escape borre artificialmente la necesidad homeostática acumulada. Los parámetros numéricos de la dinámica son de calibración computacional; no deben interpretarse como porcentajes fisiológicos medidos en una mosca real.

## Limitaciones científicas
La reducción a 16.669 neuronas es un subconjunto del MaleCNS completo, no un décimo espacial exacto de cada circuito. Las puntuaciones de ruta son una herramienta de preservación topológica, no una afirmación de que cada neurona tenga una función conductual única. Los roles DN usan nombres de tipos publicados cuando existe evidencia funcional establecida (por ejemplo DNg100/DNg97/DNb08 para marcha y DNp01/Giant Fiber para escape). La selección de escape protege también las entradas visuales hacia el DNp01 (LC4/LPLC2) cuando están presentes en las anotaciones y aristas retenidas. La dinámica LIF, el signo neurotransmisor-resuelto y la mecánica corporal siguen siendo aproximaciones computacionales.

En particular, el glutamato se modela como inhibitorio en esta reducción siguiendo la convención empleada en simulaciones recientes del VNC de Drosophila; no se representan excepciones dependientes del receptor.


## Interfaz V1.07
La interfaz se simplifica deliberadamente para que la simulación sea lo primero que se vea:
- 16.669 neuronas y referencia `MaleCNS v1.0`.
- Solo se muestran comidas, escapes, saciedad, memoria y FPS en el bloque de estado.
- El panel inferior combina una silueta 2D simplificada del SNC de la mosca con una selección pequeña de neuronas realmente retenidas.
- Las líneas del mapa son conexiones reales entre las neuronas representativas elegidas; no son una red decorativa inventada.
- Las alas tienen una animación visual de batido ligada a la actividad motora de alas medida y al desplazamiento.
- Se incluye un zumbido de mosca generado como recurso local. El audio se activa suavemente durante el movimiento o una interacción sensorial y se detiene en reposo.
- La animación y el audio son capas de presentación: no modifican la dinámica neuronal ni crean órdenes motoras.

La versión Android de esta entrega es `1.11` / `versionCode 111`; el formato binario interno continúa siendo `FBC103` para no romper la compatibilidad del lector existente.

## V1.07 — visualización neuronal y estímulo de comida

V1.07 mantiene intacta la simulación de V1.06 y cambia únicamente la presentación visual:

- El estímulo COMIDA se representa como un banano dibujado localmente; sus coordenadas y señales olfativas/gustativas no cambian.
- La visualización neural usa colores por población: VIS azul, OLF verde, GUST amarillo, MECH naranja, DN magenta, ASC cian, MOTOR rojo y OTHER gris.
- Las neuronas representativas conservan sus IDs reales y las conexiones mostradas siguen siendo aristas retenidas del conectoma.
- Una memoria visual corta suaviza los spikes para que la activación no parpadee frame a frame; esta memoria no participa en la dinámica LIF ni retroalimenta la simulación.
- El brillo/tamaño de las neuronas y el ancho/transparencia de las conexiones representan intensidad reciente de actividad.
- Se añade una leyenda de regiones y una escala visual de intensidad.
- No se añaden neuronas, conexiones ni reglas de comportamiento.


## V1.08 — REST ↔ LOCOMOCIÓN

V1.08 mantiene exactamente la reducción connectómica de V1.07: 16.669 neuronas, las mismas aristas publicadas y el mismo formato FBC103. El primer objetivo de esta versión es corregir la principal limitación conductual observada en las pruebas largas: el estado locomotor anterior contenía una oscilación endógena siempre positiva que favorecía que la mosca continuara moviéndose indefinidamente.

La modificación elimina ese oscilador locomotor forzado y lo sustituye por un estado homeostático lento y acotado:
- la actividad motora VNC medida acumula presión de reposo;
- la actividad descendente etiquetada como familia de halting/locomotor-suppression y la actividad ascendente pueden reforzar la transición;
- la amenaza sensorial se opone a entrar en reposo;
- durante el reposo la presión se descarga y un pequeño impulso interno de despertar aumenta progresivamente;
- la salida del reposo no necesita un estímulo externo;
- el cuerpo sigue moviéndose exclusivamente a partir de actividad de neuronas motoras VNC medidas.

Esto no se presenta como un modelo de sueño. Es una primera aproximación a las transiciones espontáneas REST ↔ LOCOMOTION. La literatura experimental muestra que Drosophila presenta episodios espontáneos de marcha y reposo y que existen circuitos específicos de halting que pueden inhibir comandos descendentes de marcha o frenar activamente la marcha en el VNC. Sapkal et al. (Nature 2024) describen los mecanismos walk-OFF y brake; Aimon et al. (eLife 2023) muestran cambios globales de actividad cerebral asociados a los episodios espontáneos de marcha.

La presión de reposo es una abstracción homeostática del modelo, no una neurona nueva ni una conexión añadida. No escribe directamente posición, velocidad, rumbo ni actividad motora.


## V1.12 — dinámica homeostática continua y pausas conductuales

V1.12 cambia el modelo de REST/LOCOMOTION para eliminar los umbrales rígidos que se habían utilizado en V1.08/V1.09 como mecanismo provisional de prueba. Los valores anteriores (~70 % para entrar en REPOSO y ~22 % para salir) **no se consideran parámetros biológicos de Drosophila** y ya no controlan las transiciones.

El nuevo modelo separa tres fenómenos:

- **Impulso homeostático (`homeostaticDrive`)**: aumenta gradualmente con la actividad neuronal/motora medida y disminuye durante REST.
- **Arousal (`arousalDrive`)**: representa una señal continua de recuperación/activación que puede favorecer la salida de REST; las señales de amenaza/luz elevan la demanda de vigilia.
- **Conducta de pausa (`behavioralPause`)**: puede aparecer tanto durante LOCOMOTION como durante REST. No cambia por sí sola el estado homeostático.

### Transiciones sin umbral mágico

La entrada y salida de REST se realizan mediante **hazards estocásticos continuos**. El aumento del impulso homeostático hace progresivamente más probable una transición a REST; la recuperación y el arousal hacen progresivamente más probable volver a LOCOMOTION. No existe una condición del tipo `drive >= 70 %` ni `drive <= 22 %`.

Esto evita convertir un número arbitrario de la interfaz en una supuesta regla fisiológica. Los parámetros temporales de la simulación siguen siendo parámetros del modelo y deberán calibrarse mediante pruebas, no interpretarse como porcentajes biológicos medidos.

### Pausas conductuales

Las pausas son independientes del estado homeostático:

- En **LOCOMOTION** aparecen ocasionalmente pausas cortas y aleatorias.
- En **REST** son más frecuentes y pueden durar algo más.
- La duración y el intervalo se muestrean aleatoriamente, evitando un patrón periódico artificial.
- La pausa se expresa mediante inhibición en las neuronas motoras VNC; no se congela directamente `flyX`, `flyY`, `flySpeed` ni `heading`.
- Una pausa no implica automáticamente sueño.

### Prueba recomendada

Abrir la aplicación sin estímulos y observar durante 10 minutos. Registrar:

1. cuánto tarda en aparecer la primera pausa durante LOCOMOTION;
2. duración aproximada de las pausas;
3. cuándo aparece por primera vez REST;
4. cuánto dura REST;
5. si las transiciones aparecen de forma irregular y no como un ciclo fijo.

La finalidad de esta prueba es **calibrar el comportamiento del modelo**, no comprobar si alcanza un porcentaje predeterminado.



## V1.12 — locomotor emergence and connectome-grounded halting

V1.12 is a behavioral refactor based on the video audit and on published
Drosophila connectome/halting work. It removes the previous artificial
always-on locomotor drive, random pause controller, hard rest-entry/exit
thresholds, and the incorrect use of backward-walking DNs as a generic halt
signal.

The reduced MaleCNS v1.0 graph remains exactly 16,669 traced annotated neurons.
Only published edges between retained neurons are embedded. V1.12 additionally
protects experimentally identified halt populations (FG, BB and BRK) and
measured halt-related intermediate routes during the 10% reduction. Their
metadata is stored in FBC103; no synthetic graph edges are created.

Behavioral state is now a readout of actual VNC motor/body output:
- PAUSA: >=0.25 s of near-zero locomotor output
- STOP: >=1 s
- SUEÑO: >=5 min of continuous immobility (operational label)
These are observations, not commands.

The homeostatic display is also observational: sleep pressure follows
exponential wake/rest dynamics with minute-scale time constants and never
directly moves or stops the fly. Arousal is separate and does not erase
accumulated homeostatic pressure.

The LIF runtime remains connectome-constrained and uses the signed published
neurotransmitter convention. Artificial tonic locomotor currents were removed;
a small background fluctuation remains only as unresolved intrinsic activity.


Build patch: halt-role classification now recognizes MaleCNS v1.0 aliases GNG458/CB0890 (FG), DNg60 (BB), and AN19A018 (BRK).

## FBR-10 V3 source verification

Before running the reducer, verify the exact MaleCNS v1.0 source bundle with:

```powershell
py -3 tools\prepare_malecns.py --verify-only
```

To download missing or mismatched official files and verify them:

```powershell
py -3 tools\prepare_malecns.py --download
```

The pinned source objects are checked by byte size, SHA-256, Feather schema and row count. The FlyBrain neuron census is the audited 166,700 unique body IDs with a non-empty `superclass`; it is not the 165,122 `status == Traced` subset.

For the complete pipeline use `RUN_FBR10.bat`.


## V1.14.1 — Neural Diagnostic Build

V1.14.1 keeps the V1.14 FBR-10 connectome frozen and adds read-only individual DN/motor diagnostics in the white simulation area plus stronger live activity visualization in the brain map (node size, brightness, halo, edge width and brightness). No connectome record, weight, LIF state, or body rule is changed by these presentation layers.


## V1.14.2 — Neural Observatory

V1.14.2 preserves the exact frozen FBR-10 MaleCNS v1.0 connectome and adds presentation-only instrumentation for controlled neural tests. The white simulation area now contains a larger two-column read-only diagnostic card with five individual DN and five motor-neuron leaders, real MaleCNS body IDs, functional role labels, activity bars, population rates, and a rolling 5-second Neural/DN/Motor history. The lower CNS visualization keeps live node size, brightness, halo, and edge intensity. No connectome record, weight, LIF equation, sensory input, or body-control rule is changed by these additions.
## V1.14.3 — Neural Observatory XL

V1.14.3 keeps the exact frozen FBR-10 MaleCNS v1.0 connectome and changes only observability. The white simulation area now uses a density-aware, nearly full-width diagnostic card designed to remain readable during screen recording. The lower CNS map uses thinner, lower-alpha edges so dense blue/gray bundles obscure fewer neurons while activity remains encoded by node size, brightness, halo, and edge brightness.



## V1.15.1 — VNC → body mechanics audit

V1.15.1 keeps the frozen FBR-10/FBC103 structural layer and FBD104 signed dynamics unchanged. It corrects the VNC motor-output normalization and the body/environment interface used for behavioral readouts: LEG/WING/JUMP are normalized by the fixed retained role populations, left/right leg outputs use the frozen side census, stimulus identity is removed from body turn normalization, food intake no longer requires locomotion, and locomotion/rest telemetry follows measured physical movement. CI now audits the frozen VNC motor-role census.

## V1.15.0 — Neural Dynamics Integrity
V1.15.0 keeps the FBR-10/FBC103 structural graph frozen and introduces a separate FBD104 dynamics layer derived from the official MaleCNS v1.0 neurotransmitter table. Fast synaptic edges use the established FlyBrain convention (acetylcholine excitatory; GABA/glutamate inhibitory; other/modulatory/unknown omitted from fast current) and are normalized per postsynaptic target before entering the LIF model. The adaptive synaptic-gain controller is removed from the experimental loop so stimulus tests do not change the model while being measured. Sensory current is reset every neural step, making stimulus OFF actually mean zero external sensory current.

The structural FBR-10 artifact remains byte-for-byte frozen; FBD104 is a derived dynamics artifact and is regenerated in CI from the pinned neurotransmitter source.

## V1.14.4 — Neural Observatory Quantitative
V1.14.4 mantiene el FBR-10 congelado y convierte el diagnóstico individual en una lectura cuantitativa: ventana de 500 ms, Hz por neurona, spikes por ventana, potencial de membrana (Vm) y diferencia respecto al baseline de 5 s tras RESET. La cabecera superior se simplifica para liberar espacio a la escena; el mapa CNS mantiene tamaño/brillo/halo y usa aristas aún más finas y transparentes en regiones densas.

## V1.15.2 — VNC Motor Semantics

V1.15.2 replaces the heuristic VNC motor-role interpretation with a separately generated semantics layer derived from the exact official MaleCNS v1.0 annotation Feather. The frozen FBC103 role byte is not rewritten. Runtime anatomical roles and official motor sides are loaded from `vnc_motor_semantics.tsv`.

The anatomical census gate is 708 motors: 381 LEG, 214 ABDOMEN, 67 WING, 24 NECK, 16 HALTERE, 6 OTHER; 355 L / 353 R. `TTMn` remains WING and receives only an engineering `JUMP` functional tag.

No synthetic neurons/edges or stimulus-to-body command was introduced. VNC semantics are a read-only translation layer between measured motor activity and the existing body mechanics.

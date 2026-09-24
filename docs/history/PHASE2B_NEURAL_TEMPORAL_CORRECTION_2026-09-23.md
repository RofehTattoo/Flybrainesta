# FlyBrain — PHASE 2B
## Corrección temporal del integrador neuronal: sub-stepping interno 4 × 5 ms
**Fecha:** 2026-09-23
**Estado:** CORRECCIÓN DE CÓDIGO PREPARADA / VALIDACIÓN ESTÁTICA COMPLETADA / PENDIENTE CI + APK REAL
**Base:** Phase 1 sensory purification
**FBC103 SHA-256:** `bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60`

---

## 0. OBJETIVO

PHASE 2B corrige únicamente la resolución temporal del runtime neuronal.

La interfaz pública mantiene un frame neural de 20 ms (50 Hz), pero el integrador interno se resuelve mediante cuatro subpasos de 5 ms.

La motivación procede de PHASE 2A: `tau_syn = 5 ms` no podía representarse con resolución intraframe cuando todo el modelo se actualizaba en bloques de 20 ms.

**No se modifica:**

- FBC103.
- FBD104.
- pesos estructurales.
- gains `gainOther/gainAsc/gainDesc/gainMotor`.
- threshold.
- `V_REST`.
- `V_RESET`.
- topología del conectoma.
- selección FBR-10.
- interfaz sensorial de Phase 1.

---

## 1. CORRECCIÓN CONCEPTUAL DERIVADA DE PHASE 2A

La revisión de PHASE 2A demostró que no era correcto describir `exp(-20/5)` como una pérdida del 98,17 % del spike nuevo.

La implementación previa era:

```text
synTrace_next = synTrace_old * exp(-dt/tau_syn) + prevFired
```

Por tanto, el evento nuevo entra con amplitud 1 y lo que cae a 1,83 % es la memoria residual del evento anterior.

PHASE 2B no intenta “recuperar” una señal perdida. Corrige la **resolución temporal** para que la dinámica sináptica de 5 ms pueda evolucionar dentro de cada frame público de 20 ms.

---

## 2. DISEÑO ELEGIDO

```text
FRAME PÚBLICO = 20 ms

sense(20 ms)
       |
       v
+------+-------+-------+-------+
| 5ms | 5ms   | 5ms   | 5ms   |
| S0  | S1    | S2    | S3    |
+------+-------+-------+-------+
       |
       v
 driveBody(20 ms)
```

Parámetros:

```text
NEURAL_FRAME_DT_SECONDS = 0.020
NEURAL_SUBSTEP_DT_SECONDS = 0.005
NEURAL_SUBSTEPS_PER_FRAME = 4
REFRACTORY_SECONDS = 0.0022
```

La selección de 5 ms, en lugar de 1 ms, es deliberada:

1. coincide exactamente con `tau_syn`;
2. mejora sustancialmente la resolución temporal sin introducir 20x el coste de recorrer el grafo;
3. mantiene el frame externo y la arquitectura Android existentes;
4. deja el problema de la resolución exacta del refractory como un residuo explícito para una fase posterior, en vez de ocultarlo.

Con ~2,296,752 edges dinámicos, cuatro recorridos completos equivalen a ~9,187,008 contribuciones de edges por frame público y del orden de **459 millones de contribuciones de edges/s** si el grafo completo se recorre en cada subpaso. Por ello no se adopta aquí 20 × 1 ms sin una optimización estructural/event-driven independiente.

---

## 3. CAMBIOS FUNCIONALES

### 3.1. `stepBrain()` se convierte en `stepBrainSubstep()`

La integración neural pura se ejecuta ahora con `dt = 5 ms`.

Cada subpaso actualiza:

- `prevFired`;
- `synTrace`;
- decaimiento sináptico;
- leak de membrana;
- corriente sináptica;
- entrada sensorial;
- adaptación;
- threshold;
- refractory;
- `fired`;
- latch de spikes de frame (`frameFired[]`) para la memoria visual, cuya actualización de presentación ocurre una sola vez al final del frame de 20 ms.

No se actualizan cuatro veces por frame los estados de telemetría/homeostasis.

---

## 4. SENSORY KICK: CONSERVACIÓN DE MAGNITUD

`sen​soryCurrent` conserva en esta fase el significado histórico de **un kick por frame de 20 ms**.

Por tanto:

```text
substep 0 → aplica sensoryCurrent
substep 1 → 0
substep 2 → 0
substep 3 → 0
```

Esto evita multiplicar accidentalmente por cuatro la amplitud de los estímulos al introducir sub-stepping.

No se han cambiado `FOOD_OLF_GAIN`, `SENSORY_VIS_GAIN` ni `SENSORY_GUST_GAIN`.

---

## 5. PROPAGACIÓN CAUSAL

Con el reloj anterior, una neurona podía producir efecto postsináptico únicamente en el siguiente tick de 20 ms.

Con PHASE 2B:

```text
A spike @ 0 ms
B puede responder @ 5 ms
C puede responder @ 10 ms
D puede responder @ 15 ms
```

Esto no crea conexiones nuevas. Sólo permite que las conexiones existentes del FBD104 se propaguen en una resolución temporal compatible con el estado dinámico.

---

## 6. REFRACTORY

El refractory continúa siendo `2,2 ms` como parámetro del modelo.

A 5 ms de resolución, una neurona que dispara al final de un subpaso no puede volver a evaluarse como libre en el siguiente subpaso; la discretización mínima sigue siendo más gruesa que 2,2 ms.

El intervalo mínimo discretamente representable bajo el esquema actual es aproximadamente 10 ms frente a ~40 ms con el reloj anterior de 20 ms.

**Esto es una mejora, no una representación exacta del refractory biológico.**

La resolución sub-ms del refractory queda conscientemente fuera de PHASE 2B.

---

## 7. MOTOR: NO PERDER SPIKES INTRAFRAME

Un riesgo introducido por sub-stepping sería que una MN disparase durante S0/S1 y dejara de disparar en S3, haciendo que `driveBody()` no viera el evento.

Para evitarlo, PHASE 2B acumula dentro del frame público:

- spikes de cada MN;
- drive sináptico medio;
- drive sináptico pico.

`driveBody(20 ms)` sigue siendo una interfaz corporal única, pero consume la evidencia motora generada durante los cuatro subpasos.

La geometría corporal no se ha rediseñado.

---

## 8. DIAGNÓSTICOS

Los contadores de ventanas de DNs y MNs ahora acumulan los spikes de todos los subpasos.

La ventana temporal sigue expresándose en segundos de simulación pública.

Esto evita infracontar spikes intraframe.

Los estados narrativos y homeostáticos se actualizan una vez por frame público, no cuatro veces.

---

## 9. MEMBRANA

La membrana continúa utilizando la actualización analítica existente:

```text
retención = exp(-dt/tau_m)
```

Con `tau_m = 20 ms`:

- un frame de 20 ms → `exp(-1)`;
- cuatro subpasos de 5 ms → `exp(-0.25)^4 = exp(-1)`.

Por tanto, en ausencia de entradas/spikes, el leak acumulado de un frame público permanece exactamente equivalente dentro del error de coma flotante.

---

## 10. MEMORIA VISUAL

La memoria visual es sólo presentación y no participa en las ecuaciones LIF.

La retención anterior `0.88` por 20 ms se convierte al coeficiente equivalente por 5 ms:

```text
0.88^(5/20) ≈ 0.9685469
```

Así no adquiere accidentalmente una constante temporal cuatro veces más rápida.

---

## 11. VALIDACIÓN REALIZADA

### Tests Python existentes

- FBC103 reader: PASS
- food function audit: PASS
- frozen connectome validator: PASS
- V1.17.0 membrane integration audit: PASS
- olfactory input map: PASS
- olfactory route reduction: PASS
- Phase 1 sensory purity: PASS
- VNC motor semantics logic: PASS

### Nuevo test PHASE 2B

`tools/test_phase2b_temporal_substepping.py`: **PASS**

Comprueba:

- 20 ms = 4 × 5 ms;
- equivalencia exacta del leak de membrana;
- retención sináptica de 5 ms;
- evolución de una traza spike-respuesta;
- propagación de una cadena de tres neuronas por subpasos;
- cuantización del refractory;
- aplicación única del sensory kick;
- agregación motora intraframe.

### Integridad FBC103

El SHA-256 sigue siendo:

`bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60`

### Validación estructural de Kotlin

```text
braces   375 = 375
parens 1858 = 1858
brackets 337 = 337
```

La sintaxis estructural básica del archivo queda balanceada.

La compilación Android real no se declara como validada porque el snapshot fuente no incluye `gradlew/gradlew.bat`. Debe verificarse en CI.

---

## 12. CAMBIO ADICIONAL DE CI

Se añade una etapa explícita:

```text
Validate PHASE 2B temporal substepping
```

que ejecuta el nuevo test estático/numerical golden.

También se elimina una aserción CI obsoleta que rechazaba el parámetro de presentación `excludeOlfactory = true`, porque no corresponde a la antigua inyección sensorial sintética y generaba un falso positivo.

---

## 13. RIESGOS ABIERTOS

PHASE 2B NO resuelve todavía:

1. la representación exacta del refractory de 2,2 ms;
2. la mezcla dimensional entre `sensoryCurrent` y `synCurrent`;
3. la posible pérdida de edges FBD104 por NT indeterminado;
4. la topología FBR-10 y las rutas sensorial → DN;
5. `behaviorLabel()` y la separación definitiva de readout/estado;
6. la procedencia criptográfica en runtime Android;
7. una optimización event-driven del grafo para sub-ms.

No se han tocado en esta fase.

---

## 14. CRITERIO DE ACEPTACIÓN DE PHASE 2B

La fase puede considerarse funcionalmente integrada cuando:

- CI compile la APK;
- los assets congelados mantengan sus hashes;
- el nuevo test PHASE 2B pase;
- el runtime arranque correctamente;
- el backlog neural no crezca de forma sostenida durante ejecución normal;
- los spikes DNs/MNs intraframe aparezcan correctamente en diagnósticos;
- la evidencia de movimiento continúe dependiendo de MN/VNC y no de atajos sensoriales.

Sólo después de esto se debe evaluar experimentalmente el cambio de dinámica.

---

## 15. CONCLUSIÓN

PHASE 2B implementa la corrección temporal mínima que puede justificarse actualmente sin modificar el conectoma congelado ni compensar artificialmente la red.

No se pretende que este cambio “haga que la mosca se comporte mejor”.

Su objetivo es mucho más concreto:

> **hacer que el tiempo interno del modelo represente de forma menos grosera las dinámicas que el propio modelo declara tener, manteniendo intacta la anatomía y la topología de MaleCNS/FBR-10.**

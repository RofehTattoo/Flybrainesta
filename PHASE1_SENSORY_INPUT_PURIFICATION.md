# Phase 1 — Purificación de la entrada sensorial

Estado: implementación validada en la rama `audit/phase1-sensory-purification`; pendiente de revisión cruzada de Gemini y merge posterior a CI.

## Objetivo

La interfaz pasa de:

`estímulo externo -> población sensorial completa`

 a:

`estímulo externo -> receptores retenidos identificados por anotación oficial MaleCNS -> FBR-10`.

## Cambios

- Se elimina el patrón sintético de 12 canales y la asignación `channel = ((i * 17) % pattern.size)`.
- Se elimina la microvariación sinusoidal dependiente del índice neuronal.
- LUZ se aplica sólo a fotorreceptores retenidos R1-6/R7/R8.
- COMIDA mantiene la entrada ORN anatómicamente mapeada; el componente gustativo se aplica sólo a receptores gustativos primarios retenidos.
- PELIGRO conserva una señal visual de entorno, pero deja de inyectarse de forma remota en toda MECH.
- La mecanosensibilidad queda reservada para feedback físico de frontera/propriocepción, aplicado sólo a receptores mecanosensoriales retenidos.
- Se elimina el uso de `lightDirectionalBias` como entrada directa del readout de orientación.

## Criterio de seguridad

El nuevo mapa se genera desde `body-annotations-male-cns-v1.0-minconf-0.5.feather` y el FBC103 ya generado. Si una población receptora queda vacía o un receptor retenido cae fuera del bloque anatómico declarado, CI falla. No existe fallback a la población completa.

## Validación local

Pasaron los audits estáticos existentes y el nuevo `test_sensory_input_purity.py`. El repositorio suministrado no contiene `gradlew`/`gradlew.bat`, por lo que la compilación Android real queda pendiente de CI.

La corrección no modifica FBC103, FBD104, el connectoma ni las ganancias sensoriales; sólo cambia la interfaz de estímulo externo y sus validaciones.

## Próximo paso

1. Ejecutar CI para generar `sensory_input_map.tsv` desde las anotaciones oficiales y verificar las poblaciones reales retenidas.
2. Someter esta rama/diff a revisión cruzada de Gemini sin permitir modificaciones directas del repositorio principal.
3. Auditar la conectividad de VIS/GUST/MECH y, en la Fase 2, corregir la selección FBR-10 si la reducción ha eliminado puentes sensoriales causales.

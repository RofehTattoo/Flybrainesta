# CI correction — V1.19.2

The validation job runs `tools/build_connectome.py` before structural/static tests.
That builder regenerates `GeneratedConnectomeMeta.kt` and the connectome report.
The previous patch updated the checked-in metadata and the neural integration test,
but left the canonical builder on release 1.19.1 / code 142. Consequently, CI
regenerated old metadata immediately before the test compared it with Gradle
(1.19.2 / 143), causing the assertion at line 26.

This correction synchronizes the canonical builder, generated metadata, Gradle,
release manifest, checked-in report, and stale release assertions to 1.19.2 / 143.
No connectome neuron/edge data or locomotor/feeding runtime behavior is changed.

# Food behavior scientific boundary — 2026-09-26

## What the evidence supports

- Food/fermentation odors are an important long-range cue for *Drosophila melanogaster* food finding, including in males.
- Bilateral olfactory input can support odor-gradient tracking and small yaw corrections.
- Gustatory receptors on the tarsi and mouthparts participate in sugar detection and feeding decisions; tarsal stimulation can elicit proboscis extension.
- Food odors can modulate feeding, so olfaction and taste need not be treated as a rigid serial switch.
- Male territorial defense of food resources is a documented social behavior, but it is primarily relevant when other flies/opponents are present.
- Food-associated pheromonal marking by males is documented, but this is a social/chemical context rather than an automatic post-meal animation.

## What is deliberately NOT hard-coded

1. No direct `food -> turn/heading` controller.
2. No direct `food -> locomotion` controller.
3. No long-range gustatory field. Gustatory input is now contact-gated at virtual anterior tarsi.
4. No automatic proboscis-extension animation unless a validated retained neural/motor readout for mouthpart extension is identified. FBR-10's current motor semantic table exposes leg, wing, haltere, neck, abdomen and other motor classes, but no validated proboscis-specific motor class.
5. No automatic territorial patrol for an isolated male. A territorial/aggressive behavior should require a social opponent or validated social cue model.

## Consequence for the simulator

The correct experimental sequence for the current FBR-10 scope is:

`food position -> bilateral olfactory sensing -> FBR-10 neural propagation -> VNC motor output -> locomotion/orientation -> tarsal contact -> gustatory sensing -> measured downstream response`

This is intentionally not a claim that every real fly performs those stages as a rigid sequence. Odor can modulate feeding before contact, and internal nutritional state changes the probability and vigor of feeding responses.

## Current implementation change

`MainActivity.sense()` no longer injects gustatory activity from a Gaussian distance field centered on the banana. It uses `tarsalFoodContactIntensity()`, which samples two body-relative anterior tarsal contact points. `driveBody()` counts a food-contact episode only when the same contact geometry is present and the retained gustatory population shows measured neural activity.

The banana remains environment state: only explicit user placement/drag or RESET may change `foodX/foodY`.

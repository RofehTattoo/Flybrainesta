# Food environment separation

## Decision

The food/banana position is **environment state**. Neural activity, gustatory
contact, reward, satiety, and the legacy `foodHits` counter must not change its
coordinates.

## Runtime contract

`foodX` and `foodY` have exactly two semantic writers:

1. `setFoodPosition(...)`, reached by explicit user placement/drag.
2. `resetSimulation()`, which restores the deterministic default through the
   same environment setter.

The neural simulation only reads the coordinates to compute odor, gustatory
contact, and distance. A feeding event can update `foodHits`, `satiety`, and
`reward`, but it cannot respawn or teleport the banana.

## User interaction

The selected stimulus remains the active placement tool. In the scene, a tap
places the selected stimulus at that location and a drag moves it continuously.
This preserves the interactive behavior for food, light, and danger without
creating a hidden feedback path from the brain back into the environment.

## Food-contact counting

`foodContactLatched` converts the old per-frame proximity counter into a
contact-episode counter: the same physical contact is counted once until the
fly leaves the contact condition. Manual food repositioning clears the latch,
so placing food onto the fly can legitimately create a new contact event.

## Validation invariant

`tools/test_food_environment_separation.py` and
`tools/test_food_function_audit.py` assert that the neural simulation contains
no food-position assignments and no random food respawn.

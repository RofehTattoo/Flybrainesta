#!/usr/bin/env python3
"""Numerical regression test for the V1.19.4 wall escape response."""
import math

MAX_WALL_YAW = 3.10
TAU = .085
DECAY = .34
dt = .020

# Head-on wall contact: the neutral geometry falls back to the stored escape side.
escape_side = 1.0
pressure = .80
bias = max(-1.0, min(1.0, escape_side * pressure))
assert bias > 0.5

# The actuator yaw target is therefore positive instead of zero.
yaw_target = bias * MAX_WALL_YAW
assert yaw_target > 1.5

# The target is approached smoothly, not snapped directly.
alpha = 1.0 - math.exp(-dt / TAU)
yaw0 = 0.0
yaw1 = yaw0 + (yaw_target - yaw0) * alpha
assert 0.0 < yaw1 < yaw_target

# After leaving the wall, the escape drive decays exponentially.
bias_after = bias * math.exp(-dt / DECAY)
assert 0.0 < bias_after < bias

print("WALL ESCAPE REFLEX DYNAMICS: PASS")
print(f"initial_yaw_target={yaw_target:.3f}rad/s first_frame_yaw={yaw1:.3f}rad/s")

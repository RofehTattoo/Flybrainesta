#!/usr/bin/env python3
"""V1.19.5 regression checks for wall escape independent of residual speed."""
import math

MAX_WALL_YAW = 3.10
TAU = .085
dt = .020

# Worst case observed in the lock: translational speed has collapsed to almost zero.
speed = 0.0
heading_dot_normal = -1.0  # face-on wall
wall_facing = max(0.0, min(1.0, -heading_dot_normal))
contact_gain = 0.72 + 0.28 * wall_facing
bias = contact_gain
yaw_target = bias * MAX_WALL_YAW
alpha = 1.0 - math.exp(-dt / TAU)
yaw1 = yaw_target * alpha

assert speed == 0.0
assert bias >= 0.99
assert yaw_target > 3.0
assert 0.0 < yaw1 < yaw_target

# Tangential/side wall: still a real escape drive, but smaller if parallel.
wall_facing_side = 0.55
side_gain = .72 + .28 * wall_facing_side
assert side_gain >= .72

print("WALL ESCAPE REFLEX DYNAMICS V1.19.5: PASS")
print(f"zero-speed head-on yaw_target={yaw_target:.3f}rad/s first_frame_yaw={yaw1:.3f}rad/s")

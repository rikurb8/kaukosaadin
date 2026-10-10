#!/usr/bin/env python3
"""Check the visible Standard remote's navigation touch targets without sending commands.

Run with one adb device (or ANDROID_SERIAL). For the Apple TV remote, pass
--apple-tv to also require every control on screen without scrolling.
Use --self-test for the SDK-free geometry check.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from itertools import combinations


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def bounds(node):
    return tuple(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))


def overlaps(a, b):
    return max(a[0], b[0]) < min(a[2], b[2]) and max(a[1], b[1]) < min(a[3], b[3])


def navigation_targets(root):
    targets = {}
    for label in ("Up", "Down", "Left", "Right", "OK"):
        # Compose can expose a label as a child of the clickable semantics node.
        matches = [node for node in root.iter("node")
                   if node.get("clickable") == "true"
                   and any(label in (child.get("text"), child.get("content-desc"))
                           for child in node.iter("node"))]
        assert len(matches) == 1, f"Expected one visible {label} target; show the whole directional pad"
        targets[label] = bounds(matches[0])
    return targets


def validate(targets, min_pixels):
    for label, (left, top, right, bottom) in targets.items():
        assert min(right - left, bottom - top) >= min_pixels, f"{label} is smaller than 48dp"
    for (a, first), (b, second) in combinations(targets.items(), 2):
        assert not overlaps(first, second), f"{a} and {b} have overlapping touch targets"


def validate_apple_tv(root, min_pixels):
    assert not any(node.get("scrollable") == "true" for node in root.iter("node")), "Apple TV remote must not scroll"
    labels = ("Choose device", "Sleep Apple TV", "Apps", "Settings", "BACK", "HOME", "Play/Pause",
              "Vol −", "Vol +", "Skip backward 10 seconds", "Skip forward 10 seconds")
    screen = bounds(next(root.iter("node")))
    for label in labels:
        matches = [node for node in root.iter("node")
                   if any(label in (child.get("text"), child.get("content-desc")) for child in node.iter("node"))
                   and node.get("clickable") == "true"]
        assert len(matches) == 1, f"Expected one visible {label} control"
        target = bounds(matches[0])
        assert screen[0] <= target[0] < target[2] <= screen[2], f"{label} is outside the screen"
        assert screen[1] <= target[1] < target[3] <= screen[3], f"{label} is outside the screen"
        validate({label: target}, min_pixels)


def self_test():
    assert bounds(ET.fromstring('<node bounds="[0,10][48,58]"/>')) == (0, 10, 48, 58)
    assert not overlaps((0, 0, 48, 48), (48, 0, 96, 48))
    assert overlaps((0, 0, 49, 48), (48, 0, 96, 48))
    validate({"left": (0, 0, 48, 48), "right": (48, 0, 96, 48)}, 48)
    for invalid in ({"tiny": (0, 0, 47, 48)}, {"a": (0, 0, 60, 60), "b": (50, 0, 110, 60)}):
        try:
            validate(invalid, 48)
        except AssertionError:
            continue
        raise AssertionError("Invalid touch targets were accepted")
    labels = ("Choose device", "Sleep Apple TV", "Apps", "Settings", "BACK", "HOME", "Play/Pause",
              "Vol −", "Vol +", "Skip backward 10 seconds", "Skip forward 10 seconds")
    root = ET.Element("hierarchy")
    panel = ET.SubElement(root, "node", bounds="[0,0][320,568]")
    for label in labels:
        ET.SubElement(panel, "node", text=label, clickable="true", bounds="[0,0][48,48]")
    validate_apple_tv(root, 48)
    for attribute, value in (("scrollable", "true"), ("bounds", "[0,550][48,598]")):
        panel[0].set(attribute, value)
        try:
            validate_apple_tv(root, 48)
        except AssertionError:
            panel[0].attrib.pop(attribute)
            panel[0].set("bounds", "[0,0][48,48]")
            continue
        raise AssertionError("Scrolling or clipped controls were accepted")
    print("PASS: touch-target and no-scroll geometry self-check")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    else:
        output = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        root = ET.fromstring(output[output.index("<?xml"):output.index("</hierarchy>") + 12])
        densities = re.findall(r"density:\s*(\d+)", adb("shell", "wm", "density"))
        assert densities, "Could not read device density"
        min_pixels = 48 * int(densities[-1]) / 160 - 1
        validate(navigation_targets(root), min_pixels)
        if "--apple-tv" in sys.argv:
            validate_apple_tv(root, min_pixels)
        print("PASS: navigation targets are at least 48dp and do not overlap; no commands sent")

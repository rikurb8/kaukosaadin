#!/usr/bin/env python3
"""UI regression: open the standard remote with both TVs saved, then run this.

Requires one adb device (or ANDROID_SERIAL). Only switches targets; sends no TV
commands, changes no settings, and restores the initially selected target.
"""
import re
import subprocess
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def screen():
    output = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    return ET.fromstring(output[output.index("<?xml"):output.index("</hierarchy>") + 12])


def tab(root, label):
    node = next((n for n in root.iter("node") if n.get("checkable") == "true"
                 and any(c.get("text") == label for c in n.iter("node"))), None)
    assert node is not None, "Keep the standard remote visible with both TVs saved; dismiss any overlays"
    return node


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def anchors(root):
    # Selected segments overlap at their shared border; compare the whole row.
    selector = next(n for n in root.iter("node")
                    if sum(c.get("checkable") == "true" for c in n) == 2)
    result = {"Device selector": selector.get("bounds")}
    for label in ("Wake TV", "KAUKOSÄÄDIN", "General settings", "OK", "MODEL KS-01 · UNIVERSAL"):
        node = next(n for n in root.iter("node")
                    if label in (n.get("text"), n.get("content-desc")))
        result[label] = node.get("bounds")
    return result


if __name__ == "__main__":
    root = screen()
    original = next(label for label in ("LG TV", "Apple TV") if tab(root, label).get("checked") == "true")
    try:
        snapshots = []
        for label in ("LG TV", "Apple TV", "LG TV"):
            tap(tab(screen(), label))
            root = screen()
            assert tab(root, label).get("checked") == "true", "Both TVs must be saved"
            has_play = any(n.get("content-desc") == "Play/Pause" for n in root.iter("node"))
            assert has_play == (label == "Apple TV"), "Wrong target's media controls"
            snapshots.append(anchors(root))
        for snapshot in snapshots[1:]:
            assert snapshot == snapshots[0], f"Remote moved: {snapshots[0]} -> {snapshot}"
        print("PASS: selector, wake button, dial and casing stay fixed across LG → Apple TV → LG")
    finally:
        root = screen()
        # An external notification/overlay can interrupt the check; don't tap it.
        if any(n.get("text") == "MODEL KS-01 · UNIVERSAL" for n in root.iter("node")):
            tap(tab(root, original))

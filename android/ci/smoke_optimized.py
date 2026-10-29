"""Exercise the installed optimized APK without adding an instrumentation runtime to it."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.dynamicdroid.app"

def adb(*args, timeout=25):
    return subprocess.check_output(["adb", *args], timeout=timeout).decode(errors="replace")

def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/optimized-ui.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/optimized-ui.xml"))

def click(label, scroll=False):
    for attempt in range(8 if scroll else 4):
        root = hierarchy()
        for node in root.iter("node"):
            if node.get("text") == label or node.get("content-desc") == label:
                bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    time.sleep(.5)
                    return
        if scroll:
            width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
            adb("shell", "input", "swipe", str(width // 2), str(height * 3 // 4),
                str(width // 2), str(height // 3), "250")
        time.sleep(.5)
    raise AssertionError(f"Cannot find {label!r} in optimized app UI")

adb("shell", "am", "force-stop", PACKAGE)
adb("logcat", "-c")
adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
click("Playground")
click("Preview SwiftUI morph", scroll=True)
time.sleep(2)
assert "SwiftIslandMorphPreviewActivity" in adb("shell", "dumpsys", "activity", "activities"), "SwiftUI preview did not open"
assert adb("shell", "pidof", PACKAGE).strip(), "Optimized app stopped"
assert "FATAL EXCEPTION" not in adb("logcat", "-d", "-s", "AndroidRuntime:E"), "Optimized app crashed"
root = hierarchy()
assert any("Close preview" in (node.get("text", "") + node.get("content-desc", "")) for node in root.iter("node")), "SwiftUI controls did not render"
output = pathlib.Path("island-screenshots/optimized-swiftui-morph.png")
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"], timeout=25))
print("Optimized APK: MainActivity, SwiftUI composition, and morph startup passed.")

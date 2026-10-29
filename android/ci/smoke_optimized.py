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
    for attempt in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/optimized-ui.xml")
        root = ET.fromstring(adb("exec-out", "cat", "/sdcard/optimized-ui.xml"))
        labels = [node.get("text", "").casefold() for node in root.iter("node")]
        launcher_anr = any(("quickstep" in text or "launcher" in text) and
                           ("isn't responding" in text or "is not responding" in text)
                           for text in labels)
        if not launcher_anr:
            return root
        # Dismiss only the emulator launcher's ANR. Never hide an app ANR.
        closed = False
        for node in root.iter("node"):
            if node.get("text", "").casefold() == "close app":
                bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if len(bounds) == 4:
                    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    closed = True
                    break
        assert closed, "Cannot dismiss emulator launcher ANR"
        time.sleep(.5)
    raise AssertionError("Emulator launcher repeatedly stopped responding")

def click(label, scroll=False):
    for attempt in range(8 if scroll else 4):
        root = hierarchy()
        for node in root.iter("node"):
            if node.get("text", "").casefold() == label.casefold() or node.get("content-desc", "").casefold() == label.casefold():
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

def check_crash():
    assert "FATAL EXCEPTION" not in adb("logcat", "-d", "-s", "AndroidRuntime:E"), "Optimized app crashed"

def main():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("logcat", "-c")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(1)
    check_crash()
    click("Playground")
    click("Preview SwiftUI morph", scroll=True)
    time.sleep(2)
    check_crash()
    assert "SwiftIslandMorphPreviewActivity" in adb("shell", "dumpsys", "activity", "activities"), "SwiftUI preview did not open"
    assert adb("shell", "pidof", PACKAGE).strip(), "Optimized app stopped"
    root = hierarchy()
    assert any("close preview" in (node.get("text", "") + node.get("content-desc", "")).casefold() for node in root.iter("node")), "SwiftUI controls did not render"
    print("Optimized APK: MainActivity, SwiftUI composition, and morph startup passed.")

try:
    main()
finally:
    output = pathlib.Path("island-screenshots")
    output.mkdir(parents=True, exist_ok=True)
    for name, command in [
        ("optimized-swiftui-morph.png", ["adb", "exec-out", "screencap", "-p"]),
        ("optimized-logcat.txt", ["adb", "logcat", "-d", "-s", "AndroidRuntime:E"]),
        ("optimized-activity.txt", ["adb", "shell", "dumpsys", "activity", "activities"]),
    ]:
        try:
            (output / name).write_bytes(subprocess.check_output(command, timeout=25))
        except (subprocess.SubprocessError, OSError) as error:
            print("Diagnostic collection:", error)
    try:
        (output / "optimized-ui.xml").write_text(ET.tostring(hierarchy(), encoding="unicode"))
    except (subprocess.SubprocessError, OSError, ET.ParseError) as error:
        print("UI hierarchy collection:", error)

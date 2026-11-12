"""Exercise the installed optimized APK without adding an instrumentation runtime to it."""
import os
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

# The app ID differs per build (standard or android17); CI passes it in.
PACKAGE = os.environ.get("APP_ID", "io.github.kavinagarwalagarwal.dynamicdroid")
CODE = "com.dynamicdroid.app"
recording = None

def adb(*args, timeout=25):
    return subprocess.check_output(["adb", *args], timeout=timeout).decode(errors="replace")

def hierarchy():
    for attempt in range(3):
        # A busy emulator sometimes writes an empty or partial dump: try again.
        for _ in range(5):
            try:
                adb("shell", "uiautomator", "dump", "/sdcard/optimized-ui.xml")
                root = ET.fromstring(adb("exec-out", "cat", "/sdcard/optimized-ui.xml"))
                break
            except (ET.ParseError, subprocess.SubprocessError):
                time.sleep(1)
        else:
            raise AssertionError("uiautomator could not read the screen")
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

def click_if_present(label, wait=6.0):
    """Tap a label if it appears within `wait` seconds (first-run tour, for example)."""
    end = time.time() + wait
    while time.time() < end:
        for node in hierarchy().iter("node"):
            if node.get("text", "").casefold() == label.casefold() or node.get("content-desc", "").casefold() == label.casefold():
                bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    time.sleep(.8)
                    return True
        time.sleep(.5)
    return False

def check_crash():
    assert "FATAL EXCEPTION" not in adb("logcat", "-d", "-s", "AndroidRuntime:E"), "Optimized app crashed"

def main():
    global recording
    adb("shell", "am", "force-stop", PACKAGE)
    adb("logcat", "-c")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/" + CODE + ".MainActivity")
    # Launch flourish, then the first-run tour on a fresh install.
    time.sleep(2.5)
    check_crash()
    click_if_present("Skip")
    check_crash()
    # The tour, the launch flourish or a stray emulator dialog can still be on screen on a slow
    # runner: wait for the main screen instead of failing on the first look.
    for attempt in range(12):
        labels = [(n.get("text", "") or n.get("content-desc", "")) for n in hierarchy().iter("node")]
        if any(l.casefold() == "playground" for l in labels):
            break
        if any(l.casefold() == "skip" for l in labels):
            click_if_present("Skip", wait=1)
        else:
            adb("shell", "am", "broadcast", "-a", "android.intent.action.CLOSE_SYSTEM_DIALOGS")
            if attempt in (5, 9):
                adb("shell", "am", "start", "-W", "-n", PACKAGE + "/" + CODE + ".MainActivity")
        time.sleep(1)
    else:
        raise AssertionError("Main screen never showed 'Playground'; visible: " + ", ".join(l for l in labels if l)[:600])
    click("Playground")
    time.sleep(1.5)
    check_crash()
    assert adb("shell", "pidof", PACKAGE).strip(), "Optimized app stopped on the Playground"
    adb("shell", "pm", "grant", "com.dynamicdroid.fixture", "android.permission.POST_NOTIFICATIONS")
    adb("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
    adb("shell", "cmd", "notification", "allow_listener", PACKAGE + "/" + CODE + ".IslandNotificationListener")
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services", PACKAGE + "/" + CODE + ".IslandAccessibilityService")
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    # Binding is asynchronous and slow on a busy emulator (Android 17 images especially): wait
    # until the phone reports both services connected instead of trusting a fixed sleep.
    def bound():
        listeners = adb("shell", "dumpsys", "notification", timeout=40)
        a11y = adb("shell", "dumpsys", "accessibility", timeout=40)
        return (PACKAGE + "/" + CODE + ".IslandNotificationListener") in listeners and "Bound services:{Service" in a11y
    end = time.time() + 30
    while time.time() < end and not bound():
        time.sleep(1)
    time.sleep(1)
    fixture = "com.dynamicdroid.fixture/.FixtureActivity"
    adb("shell", "am", "start", "-W", "-n", fixture, "--es", "kind", "custom")
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n", fixture, "--es", "kind", "player")
    time.sleep(1)
    before = adb("shell", "pidof", PACKAGE).strip()
    recording = subprocess.Popen(
        ["adb", "shell", "screenrecord", "--time-limit", "4", "/sdcard/island-return.mp4"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(.3)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    returned = False
    end = time.time() + 8
    while time.time() < end and not returned:
        time.sleep(.5)
        returned = "Native return started" in adb("logcat", "-d", "-s", "IslandHandoff:I")
    check_crash()
    assert adb("shell", "pidof", PACKAGE).strip() == before, "App restarted during the live handoff"
    assert returned, "The island did not grow back after leaving the player"
    print("Optimized standalone APK: Playground and real player-to-Home return passed.")

try:
    main()
except Exception as error:
    # Surface the reason in the GitHub UI (logs aren't always reachable), plus any crash.
    crash = ""
    try:
        crash = adb("logcat", "-d", "-s", "AndroidRuntime:E")[-1500:]
    except Exception:
        pass
    try:
        pid = adb("shell", "pidof", PACKAGE).strip()
        app_log = adb("logcat", "-d", "--pid=" + pid) if pid else ""
        print("---- app log (last 120 lines) ----")
        print("\n".join(app_log.splitlines()[-120:]))
        a11y = adb("shell", "dumpsys", "accessibility")
        print("---- accessibility ----")
        print("\n".join(l for l in a11y.splitlines() if "dynamicdroid" in l or "Bound services" in l or "Enabled services" in l)[:3000])
        print("---- notification listeners ----")
        print("\n".join(l for l in adb("shell", "dumpsys", "notification").splitlines() if "dynamicdroid" in l)[:3000])
    except Exception as diag:
        print("diagnostics:", diag)
    summary = f"{type(error).__name__}: {error} | {crash}".replace("%", "%25").replace("\n", " | ")
    print(f"::error title=Optimized smoke failed::{summary[:3800]}")
    raise
finally:
    output = pathlib.Path("island-screenshots")
    output.mkdir(parents=True, exist_ok=True)
    if recording is not None:
        try:
            recording.wait(timeout=8)
            if recording.returncode == 0:
                adb("pull", "/sdcard/island-return.mp4", str(output / "optimized-island-return.mp4"))
        except (subprocess.SubprocessError, OSError) as error:
            print("Transition recording:", error)
    for name, command in [
        ("optimized-swiftui-morph.png", ["adb", "exec-out", "screencap", "-p"]),
        ("optimized-logcat.txt", ["adb", "logcat", "-d", "-s", "AndroidRuntime:E"]),
        ("optimized-activity.txt", ["adb", "shell", "dumpsys", "activity", "activities"]),
        ("optimized-full-logcat.txt", ["adb", "logcat", "-d", "-t", "4000"]),
        ("optimized-services.txt", ["adb", "shell", "dumpsys", "activity", "services", PACKAGE]),
        ("optimized-a11y.txt", ["adb", "shell", "dumpsys", "accessibility"]),
    ]:
        try:
            (output / name).write_bytes(subprocess.check_output(command, timeout=25))
        except (subprocess.SubprocessError, OSError) as error:
            print("Diagnostic collection:", error)
    try:
        (output / "optimized-ui.xml").write_text(ET.tostring(hierarchy(), encoding="unicode"))
    except (subprocess.SubprocessError, OSError, ET.ParseError) as error:
        print("UI hierarchy collection:", error)

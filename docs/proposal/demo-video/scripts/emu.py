#!/usr/bin/env python3
"""에뮬레이터 화면을 글자로 찾아 누르는 작은 도구 (시연 영상 촬영용).

    emu.py texts                 화면에 보이는 글자와 상태를 한 줄씩
    emu.py tap "정산 모드"        글자(또는 설명)가 들어 있는 요소를 누른다
    emu.py tapxy 1200 800        좌표를 누른다
    emu.py shot name             화면을 raw/work/emu/name.jpg (절반 크기)로 저장
    emu.py paste "서연"           지금 칸의 글자를 지우고 붙여 넣는다 (한글 가능: 호스트 클립보드 공유)
    emu.py wait "여행 종료" 20    글자가 보일 때까지 기다린다(초)
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
# 촬영 중간 산출물(조종 목표·로그·녹화 원본)은 git에서 빠지는 demo-video/raw/work 아래에 둔다.
# 내 컴퓨터의 절대 경로를 쓰면 다른 곳에서 못 돌리고, 공개 저장소에 계정 이름이 남는다.
WORK = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "raw", "work")
OUT = os.path.join(WORK, "emu")


def adb(*args, binary=False, check=True):
    r = subprocess.run([ADB, *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(r.stderr.decode(errors="replace")[:300])
    return r.stdout if binary else r.stdout.decode(errors="replace")


def nodes():
    """화면 요소 목록. 앱 영역(오른쪽)과 계기판을 모두 담는다."""
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        if xml.strip().startswith("<?xml"):
            break
        time.sleep(0.4)
    else:
        return []
    out = []
    for n in ET.fromstring(xml).iter("node"):
        text, desc = n.get("text", ""), n.get("content-desc", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({
            "text": text, "desc": desc, "enabled": n.get("enabled") == "true",
            "clickable": n.get("clickable") == "true", "cls": n.get("class", "").split(".")[-1],
            "pkg": n.get("package", ""), "box": (x1, y1, x2, y2), "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
        })
    return out


def find(label, exact=False, all_nodes=None):
    hits = []
    for n in all_nodes if all_nodes is not None else nodes():
        for value in (n["text"], n["desc"]):
            if value and (value == label if exact else label in value):
                hits.append(n)
                break
    return hits


def tap_xy(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def tap(label, index=0, exact=False):
    hits = find(label, exact)
    if not hits:
        raise SystemExit(f"화면에서 찾지 못함: {label}")
    n = hits[min(index, len(hits) - 1)]
    tap_xy(n["cx"], n["cy"])
    return n


def wait(label, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        if find(label):
            return True
        time.sleep(0.5)
    return False


def shot(name, scale=2):
    from PIL import Image
    import io
    os.makedirs(OUT, exist_ok=True)
    png = adb("exec-out", "screencap", "-p", binary=True)
    im = Image.open(io.BytesIO(png)).convert("RGB")
    if scale != 1:
        im = im.resize((im.width // scale, im.height // scale))
    path = f"{OUT}/{name}.jpg"
    im.save(path, quality=82)
    return path


def paste(text):
    """호스트 클립보드에 넣고 에뮬레이터에서 전체 선택 후 붙여 넣는다."""
    subprocess.run(["pbcopy"], input=text.encode("utf-8"), check=True)
    time.sleep(0.8)
    adb("shell", "input", "keycombination", "113", "29")  # Ctrl+A
    time.sleep(0.2)
    adb("shell", "input", "keyevent", "279")  # PASTE
    time.sleep(0.4)


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "texts":
        only = args[0] if args else "com.drivesplit.app"
        for n in nodes():
            if only != "all" and n["pkg"] != only:
                continue
            label = n["text"] or n["desc"]
            if label:
                flags = ("" if n["enabled"] else " [꺼짐]") + (" [누름]" if n["clickable"] else "")
                print(f'{n["cx"]:>5},{n["cy"]:<5} {label[:70]}{flags}')
    elif cmd == "tap":
        n = tap(args[0], int(args[1]) if len(args) > 1 else 0)
        print("tap", n["cx"], n["cy"], n["text"] or n["desc"])
    elif cmd == "tapxy":
        tap_xy(int(args[0]), int(args[1]))
    elif cmd == "shot":
        print(shot(args[0]))
    elif cmd == "paste":
        paste(args[0])
    elif cmd == "wait":
        print(wait(args[0], float(args[1]) if len(args) > 1 else 20))

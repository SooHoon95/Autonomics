#!/usr/bin/env python3
"""본편(출발 전 → 주행 → 중간 하차 → 도착) 한 번의 여행을 자동으로 몰면서 촬영한다.

- 조작이 있는 장면은 에뮬레이터 녹화(한 파일 최대 180초)로, 긴 주행은 2초마다 화면을 떠서 타임랩스로 남긴다.
- 모든 사건의 시각을 events.jsonl에 적어, 편집 때 정확히 자를 수 있게 한다.
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emu  # noqa: E402

# 촬영본은 raw/work/rec/<이름>에 쌓인다. 편집에 쓸 것만 골라 raw/<이름>으로 옮긴다(build_video.py가 거기서 읽는다).
S = emu.WORK
REC = S + "/rec/" + os.environ.get("REC_NAME", "main")
TARGET = S + "/emu/target.txt"
SEG1_KM, SEG2_KM = 20.0, 12.0
CRUISE = 100
NAMES = {"운전자": "현우", "동승자 1": "유진", "동승자 2": "수진"}
DROP = "수진"
OPEN_PERSON = "유진"

os.makedirs(REC, exist_ok=True)
os.makedirs(emu.OUT, exist_ok=True)
EVENTS = open(REC + "/events.jsonl", "a")


def mark(event, **extra):
    row = {"t": round(time.time(), 2), "event": event, **extra}
    EVENTS.write(json.dumps(row, ensure_ascii=False) + "\n")
    EVENTS.flush()
    print(row, flush=True)


def state():
    with urllib.request.urlopen("http://localhost:8088/vehicle/state", timeout=5) as r:
        return json.load(r)


def odo():
    return float(state()["odometer"]["traveled_distance_km"])


def speed():
    return float(state()["controls"]["speed_kph"])


def target(gear, kmh):
    with open(TARGET, "w") as f:
        f.write(f"{gear} {kmh}\n")
    mark("target", gear=gear, kmh=kmh)


def rec_start(name):
    emu.adb("emu", "screenrecord", "start", "--time-limit", "180", "--fps", "30", "--bit-rate", "16M", f"{REC}/{name}.webm")
    mark("rec_start", clip=name)


def rec_stop():
    emu.adb("emu", "screenrecord", "stop")
    mark("rec_stop")
    time.sleep(1.5)


def full_shot(name):
    png = emu.adb("exec-out", "screencap", "-p", binary=True)
    with open(f"{REC}/{name}.png", "wb") as f:
        f.write(png)
    mark("shot", name=name)


class Timelapse:
    """2초마다 화면을 저장한다."""

    def __init__(self, name):
        self.dir = f"{REC}/{name}"
        os.makedirs(self.dir, exist_ok=True)
        self.on = True
        self.n = 0
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()
        mark("timelapse_start", name=name)

    def run(self):
        while self.on:
            started = time.time()
            try:
                png = emu.adb("exec-out", "screencap", "-p", binary=True)
                with open(f"{self.dir}/f_{self.n:05d}.png", "wb") as f:
                    f.write(png)
                self.n += 1
            except Exception as e:
                print("timelapse error", e, flush=True)
            time.sleep(max(0.1, 2.0 - (time.time() - started)))

    def stop(self):
        self.on = False
        self.thread.join(timeout=10)
        mark("timelapse_stop", frames=self.n)


def say(word):
    subprocess.run(["osascript", "-e", "set volume output volume 55"])
    subprocess.run(["say", "-v", "Yuna", "-r", "150", word])
    subprocess.run(["osascript", "-e", "set volume output volume 100"])


def voice_name(label, name):
    """마이크를 누르고 이름을 말한다. 다르게 들으면 두 번까지 다시 한다."""
    for attempt in range(3):
        emu.tap(f"{label} 말로 입력")
        time.sleep(1.1)
        say(name)
        for _ in range(12):
            time.sleep(0.5)
            if emu.find(name, exact=True):
                mark("voice_ok", label=label, name=name, attempt=attempt + 1)
                return True
        mark("voice_retry", label=label, name=name, attempt=attempt + 1)
        time.sleep(1.0)
    return False


def wait_stopped():
    for _ in range(60):
        if speed() < 0.5:
            break
        time.sleep(0.5)
    time.sleep(1.5)


def drive_until(start_odo, km, tl_name, mid_clip=None):
    """목표 거리까지 타임랩스로 남기며 달린다. 중간에 실시간 12초를 한 번 찍는다."""
    tl = Timelapse(tl_name)
    did_mid = False
    while True:
        gone = odo() - start_odo
        if mid_clip and not did_mid and gone >= km * 0.5:
            rec_start(mid_clip)
            time.sleep(12)
            rec_stop()
            did_mid = True
        if gone >= km:
            break
        time.sleep(2)
    tl.stop()
    mark("distance_reached", km=round(odo() - start_odo, 2))


def main():
    target("Park", 0)
    # 앱을 새로 띄워 이름을 기본값으로 돌리고, 리허설 기록을 지운다.
    emu.adb("shell", "am", "force-stop", "--user", "10", "com.drivesplit.app")
    time.sleep(1)
    emu.adb("shell", "am", "start", "--user", "10", "-n", "com.drivesplit.app/.ui.MainActivity")
    assert emu.wait("정산 모드", 20)
    if emu.find("편집"):
        emu.tap("편집")
        time.sleep(1)
        emu.tap("기록 전체 삭제")
        time.sleep(1)
        emu.tap("한 번 더 누르면")
        time.sleep(1.5)
    assert emu.find("아직 기록이 없어요")

    # ── 1. 출발 전 ──
    rec_start("s1_setup")
    time.sleep(2.5)
    emu.tap("정산 모드")
    assert emu.wait("누가 타나요?", 10)
    time.sleep(2)
    ok = all(voice_name(label, name) for label, name in NAMES.items())
    mark("names", ok=ok)
    time.sleep(3.5)
    emu.tap("출발", exact=True)
    assert emu.wait("지금까지 1인당", 15)
    time.sleep(2.5)
    rec_stop()
    if not ok:
        raise SystemExit("이름 음성 입력 실패")

    # ── 2. 주행 ──
    start = odo()
    rec_start("s2_start")
    time.sleep(1.5)
    target("Drive", CRUISE)
    time.sleep(32)
    rec_stop()
    drive_until(start, SEG1_KM, "tl1", mid_clip="s2_cruise")

    # ── 3. 중간 하차 ──
    rec_start("s3_dropoff")
    time.sleep(2)
    target("Drive", 0)
    wait_stopped()
    target("Park", 0)
    time.sleep(2.5)
    emu.tap(DROP, exact=True)
    assert emu.wait("하차 정산", 10)
    time.sleep(1.5)
    full_shot("dropoff_card")
    time.sleep(6)
    emu.tap("확인", exact=True)
    time.sleep(3.5)
    rec_stop()

    # ── 4. 다시 출발해 도착 ──
    start2 = odo()
    rec_start("s4_restart")
    time.sleep(1.5)
    target("Drive", CRUISE)
    time.sleep(28)
    rec_stop()
    drive_until(start2, SEG2_KM, "tl2")

    rec_start("s4_arrive")
    time.sleep(2)
    target("Drive", 0)
    wait_stopped()
    target("Park", 0)
    time.sleep(2.5)
    emu.tap("여행 종료")
    assert emu.wait("정산 완료", 10)
    time.sleep(2)
    full_shot("result")
    time.sleep(10)
    emu.tap(OPEN_PERSON, exact=True)
    assert emu.wait("님 정산", 10)
    time.sleep(1.5)
    full_shot("person_card")
    time.sleep(5)
    emu.tap("확인", exact=True)
    time.sleep(2.5)
    rec_stop()
    mark("done")


if __name__ == "__main__":
    try:
        main()
    except BaseException as e:
        mark("FAILED", error=repr(e)[:300])
        try:
            emu.adb("emu", "screenrecord", "stop", check=False)
        except Exception:
            pass
        with open(TARGET, "w") as f:
            f.write("Park 0\n")
        raise

#!/usr/bin/env python3
"""Virtual Console의 기어·속도를 목표값으로 붙잡아 두는 조종 스크립트 (시연 영상 촬영용).

목표는 파일 한 줄로 준다:  echo "Drive 100" > $TARGET   /   echo "Park 0" > $TARGET
- 0.5초마다 세션 신호를 보내고(콘솔은 브라우저 세션이 살아 있을 때만 값을 받는다),
  속도를 목표까지 부드럽게 올리고 내린 뒤 계속 다시 써 준다(안 쓰면 콘솔이 저절로 감속한다).
- 기어는 멈춘 뒤에만 바꾼다.
GPS·주행거리·배터리는 tools/emulator_drive.py가 이 속도를 읽어 따로 움직인다.
"""
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emu  # noqa: E402

BASE = "http://localhost:8088"
TARGET = os.path.join(emu.OUT, "target.txt")
LOG = os.path.join(emu.OUT, "pilot.log")
TICK_S = 0.5
ACCEL_KMH_PER_S = 6.0
BRAKE_KMH_PER_S = 9.0


def call(method, path):
    req = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(req, timeout=4) as r:
        return r.read()


def read_target():
    try:
        gear, speed = open(TARGET).read().split()[:2]
        return gear, float(speed)
    except Exception:
        return "Park", 0.0


def main():
    os.makedirs(emu.OUT, exist_ok=True)  # 로그를 쓸 폴더가 없으면 첫 기록에서 죽는다
    speed = 0.0
    gear = json.loads(call("GET", "/vehicle/state"))["gear"]["actual"]["value"]
    last_log = 0
    while True:
        try:
            call("POST", "/browser/session?client=demo-pilot")
            want_gear, want_speed = read_target()
            if want_gear != gear:
                # 기어를 바꾸려면 먼저 선다.
                if speed > 0.5:
                    want_speed = 0.0
                else:
                    call("PUT", f"/vehicle/gear?gear={want_gear}")
                    gear = want_gear
            if gear not in ("Drive", "Reverse"):
                want_speed = 0.0
            step = (ACCEL_KMH_PER_S if want_speed > speed else BRAKE_KMH_PER_S) * TICK_S
            delta = want_speed - speed
            speed = want_speed if abs(delta) <= step else speed + (step if delta > 0 else -step)
            accel = 35 if delta > 1 else (12 if speed > 1 else 0)
            brake = 30 if delta < -1 else 0
            call("PUT", f"/vehicle/controls?speed={int(round(speed))}&accelerator={accel}&brake={brake}")  # 속도는 정수만 받는다
            now = time.time()
            if now - last_log >= 5:
                with open(LOG, "a") as f:
                    f.write(f"{now:.1f} {gear} {speed:.1f}\n")
                last_log = now
        except Exception as e:  # 한 번 실패해도 계속 돈다
            with open(LOG, "a") as f:
                f.write(f"{time.time():.1f} ERROR {e}\n")
        time.sleep(TICK_S)


if __name__ == "__main__":
    sys.exit(main())

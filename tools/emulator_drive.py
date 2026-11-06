#!/usr/bin/env python3
"""
Pleos Connect 에뮬레이터용 주행 보조 스크립트.

Virtual Console에서 엑셀·기어로 운전하면, 에뮬레이터가 스스로 움직여 주지 않는 값을
실제 차처럼 함께 움직인다. 앱은 이 값들을 실차와 똑같이 Pleos SDK·GPS로 읽는다.

- GPS: 강남역 → 경부고속도로 → 대전 실제 도로(OpenStreetMap)를 따라 달린 만큼 이동 (adb emu geo fix)
- 누적 주행거리(Odometer): 달린 만큼 증가
- 배터리 에너지(Battery Energy): 속도(공기저항)·가속·회생제동·공조를 반영해 감소

사용법: 에뮬레이터와 `adb forward tcp:8088 tcp:8088`이 된 상태에서
    python3 tools/emulator_drive.py
Ctrl+C로 멈춘다.
"""
import json
import math
import os
import subprocess
import sys
import time
import urllib.request

CONSOLE = "http://localhost:8088"
ADB = os.environ.get("ADB", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
TICK_S = 1.0

# 아이오닉 5 롱레인지 2WD 근사치
MASS_KG = 2_000
ROLLING_KWH_PER_KM = 0.105   # 구름저항·구동계 손실
AERO_KWH_PER_KM_PER_KMH2 = 0.0000115  # 공기저항, 속도의 제곱에 비례
DRIVETRAIN_EFFICIENCY = 0.88
REGEN_RECOVERY = 0.6
AUX_KW = 0.6                  # 공조·전장
SPEED_JITTER_KMH = 3.0
SMOOTHING = 0.25

# 강남역 → 경부고속도로 → 대전 실제 도로 모양(OpenStreetMap, 50m 간격). 굽은 길이 그대로 지도에 그려진다.
ROUTE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "route_gangnam_daejeon.json")
with open(ROUTE_FILE, encoding="utf-8") as f:
    ROUTE = [tuple(p) for p in json.load(f)["points"]]


def distance_km(a, b):
    mid = math.radians((a[0] + b[0]) / 2)
    dx = math.radians(b[1] - a[1]) * math.cos(mid)
    dy = math.radians(b[0] - a[0])
    return math.hypot(dx, dy) * 6371.0


LEGS = [distance_km(a, b) for a, b in zip(ROUTE, ROUTE[1:])]
TOTAL_KM = sum(LEGS)


def point_at(km):
    """경로를 따라 km만큼 간 지점. 끝에 닿으면 되돌아온다."""
    lap = km % (2 * TOTAL_KM)
    along = lap if lap <= TOTAL_KM else 2 * TOTAL_KM - lap
    for (a, b), leg in zip(zip(ROUTE, ROUTE[1:]), LEGS):
        if along <= leg:
            t = along / leg
            return a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        along -= leg
    return ROUTE[-1]


def call(method, path):
    req = urllib.request.Request(CONSOLE + path, method=method)
    with urllib.request.urlopen(req, timeout=3) as res:
        body = res.read()
        return json.loads(body) if body else {}


def keep_console_active():
    # 콘솔은 브라우저 세션이 살아 있을 때만 값을 받는다.
    call("POST", "/browser/session?client=emulator-drive")


def set_gps(lat, lon):
    subprocess.run([ADB, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}"], capture_output=True)


def energy_kwh(prev_kmh, kmh, dt_s):
    """한 틱 동안 쓴 전기. 음수면 회생제동으로 돌려받은 양."""
    avg = (prev_kmh + kmh) / 2
    km = avg * dt_s / 3600
    cruise = km * (ROLLING_KWH_PER_KM + AERO_KWH_PER_KM_PER_KMH2 * avg ** 2)
    if abs(kmh - prev_kmh) < SPEED_JITTER_KMH:
        # 콘솔 속도의 잔떨림은 가속·감속으로 치지 않는다.
        return cruise + AUX_KW * dt_s / 3600
    v1, v2 = prev_kmh / 3.6, kmh / 3.6
    kinetic = 0.5 * MASS_KG * (v2 ** 2 - v1 ** 2) / 3.6e6
    accel = kinetic / DRIVETRAIN_EFFICIENCY if kinetic > 0 else kinetic * REGEN_RECOVERY
    return cruise + accel + AUX_KW * dt_s / 3600


def main():
    keep_console_active()
    state = call("GET", "/vehicle/state")
    odometer = state["odometer"]["traveled_distance_km"]
    battery = state["ev_battery"]["current_energy_kwh"]
    traveled = 0.0
    prev_kmh = state["controls"]["speed_kph"]
    smooth = prev_kmh
    set_gps(*point_at(0))
    print(f"start: odometer {odometer:.1f}km, battery {battery:.2f}kWh, route {TOTAL_KM:.0f}km")

    last = time.monotonic()
    while True:
        time.sleep(TICK_S)
        now = time.monotonic()
        dt_s, last = now - last, now  # 콘솔·adb 호출 때문에 한 바퀴가 1초보다 길 수 있다.
        keep_console_active()
        state = call("GET", "/vehicle/state")
        kmh = float(state["controls"]["speed_kph"] or 0)
        gear = (state["gear"].get("actual") or {}).get("value")
        if gear not in ("Drive", "Reverse"):
            kmh = 0.0

        km = (prev_kmh + kmh) / 2 * dt_s / 3600
        traveled += km
        odometer += km
        # 콘솔은 입력이 없으면 저절로 감속해서 속도가 출렁인다. 가속 비용은 다듬은 속도로 계산한다.
        next_smooth = smooth + SMOOTHING * (kmh - smooth)
        battery = max(0.0, battery - energy_kwh(smooth, next_smooth, dt_s))
        smooth = next_smooth
        prev_kmh = kmh

        lat, lon = point_at(traveled)
        set_gps(lat, lon)
        call("PUT", f"/vehicle/odometer?traveled_distance_km={odometer:.3f}")
        call("PUT", f"/vehicle/ev-battery?field=battery_level&value={battery:.3f}")
        sys.stdout.write(f"\r{gear or '-':7} {kmh:5.0f}km/h  주행 {traveled:6.2f}km  배터리 {battery:6.2f}kWh  ")
        sys.stdout.flush()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()

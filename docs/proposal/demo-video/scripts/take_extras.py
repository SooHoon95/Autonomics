#!/usr/bin/env python3
"""보너스 장면 촬영: ① 시동 OFF/ON 이어하기 ② 중간 탑승 ③ 솔로 모드 리포트.
본편과 별도의 짧은 여행으로 찍는다. 실행: REC_NAME=extras python3 take_extras.py [reboot|solo|all]
"""
import os
import subprocess
import sys
import time

os.environ.setdefault("REC_NAME", "extras")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emu  # noqa: E402
from take_main import (S, drive_until, full_shot, mark, odo, rec_start, rec_stop, say, target, wait_stopped)  # noqa: E402

# 저장소 루트의 tools/emulator_drive.py. 이 파일에서 네 단계 위(scripts → demo-video → proposal → docs → 루트)다.
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".."))
DRIVE_TOOL = ["python3", "-u", os.path.join(REPO, "tools", "emulator_drive.py")]


def tap_done():
    """결과 화면의 '완료' 버튼(오른쪽 아래). '정산 완료' 제목과 헷갈리지 않게 좌표로 누른다."""
    emu.tap_xy(2298, 1194)
    time.sleep(2)


def restart_drive_tool():
    subprocess.run(["pkill", "-f", "tools/emulator_drive.py"])
    time.sleep(1)
    subprocess.Popen(DRIVE_TOOL, stdout=open(S + "/emu/drive.out", "a"), stderr=subprocess.STDOUT, start_new_session=True)
    time.sleep(3)


def wait_boot():
    emu.adb("wait-for-device")
    for _ in range(240):
        if emu.adb("shell", "getprop", "sys.boot_completed", check=False).strip() == "1":
            break
        time.sleep(2)
    time.sleep(45)  # 차량 화면과 사용자 프로필이 올라올 때까지
    emu.adb("forward", "tcp:8088", "tcp:8088")
    emu.adb("emu", "avd", "hostmicon", check=False)


def go_home():
    if emu.find("정산 모드"):
        return
    if emu.find("여행 종료") or emu.find("운전 종료"):
        raise SystemExit("진행 중인 여행이 있어 시작할 수 없음")
    tap_done()
    assert emu.wait("정산 모드", 10)


def reboot_and_add():
    go_home()
    emu.tap("정산 모드")
    assert emu.wait("누가 타나요?", 10)
    time.sleep(1)
    # 동승자는 유진 한 명만 두고 출발한다. 수진은 중간에 태운다.
    removes = emu.find("빼기", exact=True)
    if len(removes) >= 2:
        emu.tap_xy(removes[1]["cx"], removes[1]["cy"])
        time.sleep(1)
    emu.tap("출발", exact=True)
    assert emu.wait("지금까지 1인당", 15)
    start = odo()
    target("Drive", 100)
    drive_until(start, 2.5, "tl_e1")
    rec_start("e1_before")
    time.sleep(8)
    target("Drive", 0)
    wait_stopped()
    target("Park", 0)
    time.sleep(4)
    full_shot("e1_before")
    rec_stop()

    mark("reboot")
    emu.adb("reboot")
    time.sleep(20)
    wait_boot()
    restart_drive_tool()
    rec_start("e1_after")
    time.sleep(2)
    emu.adb("shell", "am", "start", "--user", "10", "-n", "com.drivesplit.app/.ui.MainActivity")
    ok = emu.wait("지금까지 1인당", 40)
    mark("resumed", ok=ok)
    time.sleep(6)
    full_shot("e1_after")
    rec_stop()
    if not ok:
        raise SystemExit("재부팅 뒤 여행이 이어지지 않음")

    # 중간 탑승
    rec_start("e2_add")
    time.sleep(2)
    emu.tap("+ 동승자 추가")
    assert emu.wait("태우기", 10)
    time.sleep(1.5)
    added = False
    for attempt in range(3):
        emu.tap("이름 말로 입력")
        time.sleep(1.1)
        say("수진")
        for _ in range(10):
            time.sleep(0.5)
            if emu.find("수진", exact=True):
                added = True
                break
        if added:
            break
    mark("add_voice", ok=added)
    time.sleep(2)
    emu.tap("태우기", exact=True)
    time.sleep(3)
    target("Drive", 100)
    time.sleep(45)
    full_shot("e2_driving")
    time.sleep(3)
    rec_stop()
    target("Drive", 0)
    wait_stopped()
    target("Park", 0)
    time.sleep(2)
    emu.tap("여행 종료")
    assert emu.wait("정산 완료", 10)
    time.sleep(2)
    full_shot("e2_result")
    tap_done()


def solo():
    go_home()
    emu.tap("솔로 모드")
    assert emu.wait("솔로 운전 설정", 10)
    time.sleep(1)
    emu.tap("출발", exact=True)
    assert emu.wait("운전 종료", 15)
    # 시내 속도로 2분 넘게, 이어서 고속으로 2분 넘게 달려 구간이 두 개가 되게 한다.
    target("Drive", 40)
    time.sleep(130)
    target("Drive", 100)
    time.sleep(155)
    rec_start("e3_live")
    time.sleep(10)
    full_shot("e3_live")
    target("Drive", 0)
    wait_stopped()
    target("Park", 0)
    time.sleep(2.5)
    emu.tap("운전 종료")
    assert emu.wait("운전 리포트", 10)
    time.sleep(2)
    full_shot("e3_report")
    time.sleep(8)
    rec_stop()
    tap_done()


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    try:
        if which in ("reboot", "all"):
            reboot_and_add()
        if which in ("solo", "all"):
            solo()
        mark("extras_done")
    except BaseException as e:
        mark("FAILED", error=repr(e)[:300])
        emu.adb("emu", "screenrecord", "stop", check=False)
        with open(S + "/emu/target.txt", "w") as f:
            f.write("Park 0\n")
        raise

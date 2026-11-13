#!/usr/bin/env python3
"""본편을 짧게 이어 찍는다: 지금 달리던 여행에서 바로 하차 장면 → 짧은 둘째 구간 → 도착."""
import os
import sys
import time

os.environ["REC_NAME"] = "main"
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emu  # noqa: E402
from take_main import (DROP, OPEN_PERSON, TARGET, drive_until, full_shot, mark, odo, rec_start, rec_stop, target, wait_stopped)  # noqa: E402

SEG2_KM = 6.0


def main():
    mark("continue_short")
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

    start2 = odo()
    rec_start("s4_restart")
    time.sleep(1.5)
    target("Drive", 100)
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
        emu.adb("emu", "screenrecord", "stop", check=False)
        with open(TARGET, "w") as f:
            f.write("Park 0\n")
        raise

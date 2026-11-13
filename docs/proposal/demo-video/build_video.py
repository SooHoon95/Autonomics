#!/usr/bin/env python3
"""촬영 원본(raw/)과 자막(overlays/)으로 시연 영상을 만든다.  →  AUTONOMICS_demo.mp4

    python3 docs/proposal/demo-video/build_video.py

장면 구성은 docs/proposal/demo-video-scenario.md를 따른다. 조각마다 (원본, 시작·끝 초, 배속, 자막)을
아래 표로 적어 두었으므로, 길이나 자막을 바꾸려면 표만 고치면 된다. ffmpeg가 필요하다.
"""
import pathlib
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw

HERE = pathlib.Path(__file__).resolve().parent
RAW, OV = HERE / "raw", HERE / "overlays"
WORK = HERE / "raw" / "_segments"
OUT = HERE / "AUTONOMICS_demo.mp4"
ENC = ["-an", "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p", "-r", "30", "-movflags", "+faststart"]

# 확대해서 보여 줄 영역 (원본 2560x1440 기준 x, y, w, h — 모두 16:9)
ZOOM_PRICE = (1560, 190, 1000, 562)   # 출발 화면 오른쪽: 차종과 충전 단가
ZOOM_METER = (700, 560, 1280, 720)    # 주행 화면의 금액과 "(실측)" 줄


def run(args):
    r = subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *args])
    if r.returncode != 0:
        sys.exit("ffmpeg 실패: " + " ".join(map(str, args))[:300])


def phone_frame(name):
    """폰 화면 캡처에 테두리를 둘러 영상 위에 얹을 그림으로 만든다."""
    shot = Image.open(RAW / "phone" / f"{name}.png").convert("RGB")
    w, h = shot.size
    pad = 16
    frame = Image.new("RGBA", (w + pad * 2, h + pad * 2), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], radius=44, fill=(8, 10, 14, 255), outline=(245, 165, 36, 255), width=4)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=30, fill=255)
    frame.paste(shot, (pad, pad), mask)
    path = WORK / f"{name}.png"
    frame.save(path)
    return path


def clip(n, src, start, end, speed=1.0, caption=None, zoom=None, extra=None, badge=False, timed=()):
    """원본 영상의 한 구간을 잘라 배속·확대·자막을 입힌다. extra=(그림, x, y)로 폰 화면 등을 얹고,
    timed=[(자막 이름, 시작, 끝)]으로 잘린 영상 기준 몇 초 동안만 보이는 표시를 얹는다."""
    out = WORK / f"{n:02d}.mp4"
    inputs = ["-ss", f"{start}", "-t", f"{end - start}", "-i", str(RAW / src)]
    chain = f"[0:v]setpts=(PTS-STARTPTS)/{speed},fps=30"
    if zoom:
        x, y, w, h = zoom
        chain += f",crop={w}:{h}:{x}:{y}"
    chain += ",scale=1920:1080:flags=lanczos[v0]"
    last, idx = "v0", 1
    layers = [(OV / f"{caption}.png", 0, 0)] if caption else []
    if badge:
        layers.append((OV / "tl.png", 0, 0))
    if extra:
        layers.append(extra)
    for path, x, y in layers:
        inputs += ["-loop", "1", "-i", str(path)]
        chain += f";[{last}][{idx}:v]overlay={x}:{y}:shortest=1[v{idx}]"
        last, idx = f"v{idx}", idx + 1
    for name, t0, t1 in timed:
        inputs += ["-loop", "1", "-i", str(OV / f"{name}.png")]
        chain += f";[{last}][{idx}:v]overlay=0:0:shortest=1:enable='between(t,{t0},{t1})'[v{idx}]"
        last, idx = f"v{idx}", idx + 1
    run([*inputs, "-filter_complex", chain, "-map", f"[{last}]", *ENC, str(out)])
    return out


def lapse(n, folder, fps, caption):
    """2초마다 떠 둔 화면을 이어 붙여 타임랩스로 만든다."""
    frames = sorted((RAW / folder).glob("f_*.png"))
    good = WORK / f"_{folder.replace('/', '_')}"
    shutil.rmtree(good, ignore_errors=True)
    good.mkdir()
    i = 0
    for f in frames:
        try:
            Image.open(f).verify()   # 촬영을 끊을 때 쓰다 만 마지막 장은 버린다
        except Exception:
            continue
        (good / f"g_{i:05d}.png").symlink_to(f)
        i += 1
    out = WORK / f"{n:02d}.mp4"
    chain = f"[0:v]fps=30,scale=1920:1080:flags=lanczos[v0];[v0][1:v]overlay=0:0:shortest=1[v1];[v1][2:v]overlay=0:0:shortest=1[v2]"
    run(["-framerate", str(fps), "-i", str(good / "g_%05d.png"), "-loop", "1", "-i", str(OV / f"{caption}.png"), "-loop", "1", "-i", str(OV / "tl.png"),
         "-filter_complex", chain, "-map", "[v2]", *ENC, str(out)])
    return out


def card(n, name, seconds):
    out = WORK / f"{n:02d}.mp4"
    run(["-loop", "1", "-t", str(seconds), "-i", str(OV / f"{name}.png"), "-vf", "fps=30,scale=1920:1080,fade=t=in:st=0:d=0.25", *ENC, str(out)])
    return out


def main():
    shutil.rmtree(WORK, ignore_errors=True)
    WORK.mkdir(parents=True)
    phone1, phone2 = phone_frame("demo-phone-drop-1"), phone_frame("demo-phone-drop-2")
    P = lambda path: (path, 150, 54)   # 폰 화면은 계기판 쪽(왼쪽)에 얹는다
    M, X = "main/", "extras/"
    parts = [
        # ── 오프닝 ──
        card(0, "intro1", 2.2), card(1, "intro2", 2.4), card(2, "intro3", 2.8),
        # ── 1. 출발 전: 음성으로 이름 → 차종·단가 → 출발 ──
        # 이름을 말한 시점(원본 기준 약 11~16초, 19~24초, 27~31초)을 2.6배속에 맞춰 표시한다
        clip(10, M + "s1_setup.webm", 0.0, 33.0, 2.6, "c1a", timed=[("v1", 4.2, 6.4), ("v2", 7.4, 9.3), ("v3", 10.2, 12.2)]),
        clip(11, M + "s1_setup.webm", 32.0, 35.5, 1.0, "c1b", zoom=ZOOM_PRICE),
        # ── 2. 주행 중: 잠금 → 타임랩스 → 실측 ──
        clip(20, M + "s2_start.webm", 0.0, 19.0, 2.2, "c2a"),
        lapse(21, "main/tl1", 40, "c2b"),
        clip(22, M + "s2_cruise.webm", 2.0, 6.0, 1.0, "c2c", zoom=ZOOM_METER),
        # ── 3. 중간 하차: 정차 → 이름 탭 → 정산 카드와 QR → 폰 정산서 ──
        clip(30, M + "s3_dropoff.webm", 1.0, 15.5, 2.4, "c3a"),
        clip(31, M + "s3_dropoff.webm", 15.5, 21.5, 1.3, "c3b"),
        clip(32, M + "s3_dropoff.webm", 21.5, 24.5, 1.0, "c3c", extra=P(phone1)),
        clip(33, M + "s3_dropoff.webm", 24.5, 27.5, 1.0, "c3c", extra=P(phone2)),
        clip(34, M + "s3_dropoff.webm", 30.5, 35.2, 1.6, "c3b"),
        # ── 4. 도착: 남은 사람만 오름 → 타임랩스 → 정산 완료 → 사람별 정산서 ──
        clip(40, M + "s4_restart.webm", 0.0, 9.0, 1.5, "c4a"),
        lapse(41, "main/tl2", 40, "c4a"),
        clip(42, M + "s4_arrive.webm", 1.0, 15.5, 3.0, "c4a"),
        clip(43, M + "s4_arrive.webm", 15.5, 24.5, 1.8, "c4b"),
        clip(44, M + "s4_arrive.webm", 24.5, 28.0, 1.0, "c4b"),
        clip(45, M + "s4_arrive.webm", 28.0, 32.5, 1.0, "c4c"),
        clip(46, M + "s4_arrive.webm", 37.5, 46.5, 1.8, "c4b"),
        # ── 5. 차라서 되는 세 가지 ──
        card(50, "bonus", 1.4),
        clip(51, X + "e1_before.webm", 22.0, 25.5, 1.0, "c5a"),
        card(52, "off", 1.2),
        clip(53, X + "e1_after.webm", 0.0, 10.4, 1.6, "c5a"),
        clip(54, X + "e2_add.webm", 0.0, 23.0, 2.5, "c5b"),
        clip(55, X + "e2_add.webm", 62.0, 66.0, 1.0, "c5b"),
        clip(56, X + "e3_live.webm", 0.0, 8.0, 2.0, "c5c"),
        clip(57, X + "e3_live.webm", 30.0, 40.7, 1.4, "c5c"),
        # ── 엔딩 ──
        card(90, "end", 5.5),
    ]
    listing = WORK / "list.txt"
    listing.write_text("".join(f"file '{p}'\n" for p in parts))
    # 소리 없는 영상이지만, 재생기·업로드 호환을 위해 무음 소리 줄을 하나 넣는다.
    run(["-f", "concat", "-safe", "0", "-i", str(listing), "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
         "-c:v", "copy", "-c:a", "aac", "-b:a", "64k", "-shortest", "-movflags", "+faststart", str(OUT)])
    print(OUT)


if __name__ == "__main__":
    main()

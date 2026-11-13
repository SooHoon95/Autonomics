#!/usr/bin/env python3
"""영상 위에 얹을 자막 띠와 글자 카드를 PNG로 만든다 (1920x1080, 자막은 투명 배경)."""
import os, subprocess, tempfile, time, pathlib, html
HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "overlays"
BIN = pathlib.Path.home() / "Library/Caches/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-mac-arm64/chrome-headless-shell"
FONT = '@import url("https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css");'
BASE = f'''<!doctype html><html><head><meta charset="utf-8"><style>{FONT}
*{{box-sizing:border-box}} html,body{{margin:0;width:1920px;height:1080px;overflow:hidden;font-family:"Pretendard","Apple SD Gothic Neo",sans-serif;word-break:keep-all}}
.bar{{position:absolute;left:0;right:0;bottom:0;height:104px;background:#0e1116;display:flex;align-items:center;gap:26px;padding:0 44px;border-top:3px solid #f5a524}}
.step{{font-size:25px;font-weight:800;color:#0e1116;background:#f5a524;border-radius:999px;padding:8px 22px;white-space:nowrap}}
.cap{{font-size:38px;font-weight:800;color:#fff;letter-spacing:-.01em;white-space:nowrap}}
.tag{{margin-left:auto;font-size:22px;font-weight:600;color:#aab3c0;border:1.5px solid #4a5361;border-radius:10px;padding:7px 16px;white-space:nowrap}}
.card{{position:absolute;inset:0;background:#0e1116;color:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;gap:26px}}
.card .q{{font-size:76px;font-weight:800;letter-spacing:-.02em;line-height:1.25}}
.card .s{{font-size:40px;font-weight:600;color:#aab3c0}}
.card .brand{{font-size:120px;font-weight:800;letter-spacing:-.02em}}
.card .brand b{{color:#f5a524}}
.card .tagline{{font-size:44px;font-weight:700}}
.card .three{{display:flex;gap:26px;margin-top:10px}}
.card .three div{{background:#1b212b;border-radius:22px;padding:26px 40px;font-size:42px;font-weight:800}}
.card .three div b{{color:#f5a524}}
.card .sdk{{font-size:30px;font-weight:600;color:#aab3c0;margin-top:14px}}
.card .fine{{position:absolute;bottom:54px;left:0;right:0;font-size:22px;color:#6f7885}}
.say{{position:absolute;left:70px;bottom:150px;background:#0e1116;border:3px solid #f5a524;border-radius:22px;padding:16px 30px;color:#fff;font-size:52px;font-weight:800;display:flex;align-items:center;gap:20px}}
.say span{{font-size:26px;font-weight:700;color:#f5a524}}
.mid{{position:absolute;inset:0;background:#0e1116;color:#fff;display:flex;align-items:center;justify-content:center;gap:40px;font-size:84px;font-weight:800}}
.mid i{{font-style:normal;color:#f5a524}}
</style></head><body>%s</body></html>'''

def bar(step, cap, tag=""):
    t = f'<span class="tag">{html.escape(tag)}</span>' if tag else ""
    return f'<div class="bar"><span class="step">{html.escape(step)}</span><span class="cap">{cap}</span>{t}</div>'

ITEMS = {
  # 자막 띠 (투명 배경)
  "c1a": (bar("① 출발 전", "이름은 말로 넣는다", "SpeechToText SDK · 온디바이스"), True),
  "c1b": (bar("① 출발 전", "차종은 차가, 충전 단가는 자동으로", "Vehicle SDK · 가격 API"), True),
  "c2a": (bar("② 주행 중", "달리기 시작하면 조작이 잠긴다", "차속 · 기어"), True),
  "c2b": (bar("② 주행 중", "배터리가 실제로 줄어든 만큼 금액이 오른다", "Vehicle SDK · EvBattery · Odometer"), True),
  "c2c": (bar("② 주행 중", "추정이 아니라 <span style='color:#f5a524'>실측</span>", "타임랩스가 아닌 실시간"), True),
  "c3a": (bar("③ 중간 하차", "서면 조작이 열린다", "차속 · 기어"), True),
  "c3b": (bar("③ 중간 하차", "내리는 순간, 탄 구간만큼 확정", ""), True),
  "c3c": (bar("③ 중간 하차", "동승자는 설치 없이 QR만 찍는다", "웹 정산서 · 금액 복사"), True),
  "c4a": (bar("④ 도착", "이후 구간은 남은 사람끼리 나눈다", "Vehicle SDK · EvBattery"), True),
  "c4b": (bar("④ 도착", "사람별 금액 · 구간별 내역 · 지나온 길", "위치 · NaviHelper"), True),
  "c4c": (bar("④ 도착", "N빵이면 537원씩 → 먼저 내린 수진은 <span style='color:#f5a524'>366원</span>", ""), True),
  "c5a": (bar("차라서 되는 것 ①", "시동을 껐다 켜도 여행은 하나", "부팅 신호 · 주행거리계"), True),
  "c5b": (bar("차라서 되는 것 ②", "중간에 탄 사람은 그때부터만 낸다", "SpeechToText SDK"), True),
  "c5c": (bar("차라서 되는 것 ③", "혼자 탈 때는 운전 효율 리포트", "솔로 모드"), True),
  "tl":  ('<div style="position:absolute;left:676px;top:74px;background:#f5a524;color:#0e1116;font-size:26px;font-weight:800;border-radius:999px;padding:8px 22px">타임랩스 ▶▶</div>', True),
  # 음성 입력 장면: 소리가 없는 영상이라 무엇을 말했는지 글자로 보여 준다
  "v1": ('<div class="say"><span>말로 입력</span>"현우"</div>', True),
  "v2": ('<div class="say"><span>말로 입력</span>"유진"</div>', True),
  "v3": ('<div class="say"><span>말로 입력</span>"수진"</div>', True),
  # 글자 카드 (불투명)
  "intro1": ('<div class="card"><div class="q">"기름값, 얼마씩 내면 돼?"</div></div>', False),
  "intro2": ('<div class="card"><div class="q">"기름값, 얼마씩 내면 돼?"</div><div class="s">…차에 탄 누구도 정확히 모른다.</div></div>', False),
  "intro3": ('<div class="card"><div class="brand">AUTO<b>NOMICS</b></div><div class="tagline">함께 탄 만큼, 차가 실제로 쓴 만큼.</div><div class="s">Pleos Connect 차량용 정산 앱</div></div>', False),
  "off":    ('<div class="mid">시동 <i>OFF</i> → <i>ON</i></div>', False),
  "bonus":  ('<div class="card"><div class="q">차라서 되는 세 가지</div></div>', False),
  "end":    ('<div class="card"><div class="three"><div>차가 <b>잰다</b></div><div>탄 만큼 <b>나눈다</b></div><div>내릴 때 <b>끝낸다</b></div></div><div class="brand" style="font-size:96px;margin-top:22px">AUTO<b>NOMICS</b></div><div class="sdk">Pleos Vehicle SDK · NaviHelper SDK · SpeechToText SDK</div><div class="fine">모든 화면은 Pleos Connect 에뮬레이터에서 실제 SDK 값으로 촬영했습니다. 주행과 배터리 변화는 Virtual Console과 주행 재현 도구로 입력했습니다.</div></div>', False),
}

def shot(name, body, transparent):
    src = HERE / f"_{name}.html"
    src.write_text(BASE % body, encoding="utf-8")
    target = OUT / f"{name}.png"
    target.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory() as profile:
        args = [str(BIN), "--disable-gpu", "--no-first-run", f"--user-data-dir={profile}", "--hide-scrollbars",
                "--window-size=1920,1080", "--virtual-time-budget=8000", f"--screenshot={target}"]
        if transparent:
            args.append("--default-background-color=00000000")
        p = subprocess.Popen(args + [src.as_uri()], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            time.sleep(0.5)
            if p.poll() is not None:
                break
        p.kill(); p.wait()
    src.unlink()
    return target.exists()

if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    for name, (body, transparent) in ITEMS.items():
        print(name, shot(name, body, transparent), flush=True)

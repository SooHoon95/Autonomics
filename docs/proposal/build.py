#!/usr/bin/env python3
"""기획서 산출물을 다시 만든다.

    python3 docs/proposal/build.py          # PDF 2개 + 웹용 페이지
    python3 docs/proposal/build.py --web    # 웹용 페이지만

원본은 proposal.html(12쪽 기획서)과 guideline.html(작성 가이드라인) 두 개뿐이다.
- proposal.pdf / guideline.pdf, 그리고 웹 페이지가 내려 주는 pdf/ 아래 사본
- autonomics-web.html: 두 문서를 탭으로 묶은 웹용 페이지 (web-template.html에 본문을 끼워 넣는다)
"""
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
HEADLESS_CANDIDATES = [
    *sorted(Path.home().glob("Library/Caches/ms-playwright/chromium_headless_shell-*/*/chrome-headless-shell"), reverse=True),
    Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
]


def body_of(name: str) -> str:
    html = (HERE / name).read_text(encoding="utf-8")
    return html[html.index("<body>") + len("<body>"):html.index("</body>")]


def wrap_tables(html: str) -> str:
    """좁은 화면에서 표만 가로로 밀 수 있게 감싼다. 표지의 정보 표는 그대로 둔다."""
    def wrap(match: re.Match) -> str:
        table = match.group(0)
        return table if 'class="meta-table"' in table[:80] else f'<div class="tw">{table}</div>'
    return re.sub(r"<table\b.*?</table>", wrap, html, flags=re.S)


def toc(proposal: str) -> str:
    """기획서 각 쪽으로 가는 링크. 첫 쪽(표지 겸 요약)은 머리글이 없어 이름을 직접 준다."""
    links = []
    for pid, section in re.findall(r'<section class="sheet" id="(p\d\d)">(.*?)</section>', proposal, flags=re.S):
        title = re.search(r'<span class="en">(.*?)</span>', section)
        links.append(f'<a href="#{pid}"><b>{pid[1:]}</b>{title.group(1) if title else "Executive Summary"}</a>')
    return f'<nav class="toc" aria-label="기획서 쪽 이동">{"".join(links)}</nav>'


def build_web() -> None:
    proposal = body_of("proposal.html")
    guideline = body_of("guideline.html")
    page = (HERE / "web-template.html").read_text(encoding="utf-8")
    page = page.replace("<!--TOC-->", toc(proposal))
    page = page.replace("<!--PROPOSAL-->", wrap_tables(proposal))
    page = page.replace("<!--GUIDELINE-->", wrap_tables(guideline))
    (HERE / "autonomics-web.html").write_text(page, encoding="utf-8")
    print("autonomics-web.html", len(page), "bytes")


def print_pdf(source: str, target: Path) -> None:
    browser = next((p for p in HEADLESS_CANDIDATES if p.exists()), None)
    if browser is None:
        sys.exit("크롬(또는 Playwright의 chrome-headless-shell)을 찾지 못했습니다.")
    target.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory() as profile:
        process = subprocess.Popen(
            [str(browser), "--headless", "--disable-gpu", "--no-first-run", f"--user-data-dir={profile}",
             "--no-pdf-header-footer", "--virtual-time-budget=20000", f"--print-to-pdf={target}",
             (HERE / source).as_uri()],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        # 크롬이 PDF를 다 쓰고도 끝나지 않는 경우가 있어, 파일 크기가 멈추면 직접 끝낸다.
        last, stable = -1, 0
        for _ in range(120):
            time.sleep(0.5)
            if process.poll() is not None:
                break
            size = target.stat().st_size if target.exists() else 0
            stable = stable + 1 if size and size == last else 0
            last = size
            if stable >= 3:
                break
        process.kill()
        process.wait()
    if not target.exists() or target.stat().st_size == 0:
        sys.exit(f"{target.name}을 만들지 못했습니다.")
    print(target.name, target.stat().st_size, "bytes")


def build_pdfs() -> None:
    (HERE / "pdf").mkdir(exist_ok=True)
    for source, target, copy in (
        ("proposal.html", "proposal.pdf", "pdf/autonomics-proposal.pdf"),
        ("guideline.html", "guideline.pdf", "pdf/proposal-guideline.pdf"),
    ):
        print_pdf(source, HERE / target)
        shutil.copyfile(HERE / target, HERE / copy)


if __name__ == "__main__":
    if "--web" not in sys.argv:
        build_pdfs()
    build_web()

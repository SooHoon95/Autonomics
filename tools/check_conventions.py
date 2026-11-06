#!/usr/bin/env python3
"""
아키텍처·코드 컨벤션 검사. 규칙은 CLAUDE.md의 "지켜야 할 규칙"과 짝을 이룬다.

    python3 tools/check_conventions.py          # 전체 검사, 위반이 있으면 exit 1
    python3 tools/check_conventions.py --hook   # Claude Code PostToolUse 훅: 편집한 파일이 .kt/.kts일 때만 검사, 위반이면 exit 2
    python3 tools/check_conventions.py --stop   # Claude Code Stop 훅: Kotlin 변경이 있으면 검사 + 도메인 테스트, 실패면 exit 2

규칙을 고칠 때는 CLAUDE.md도 함께 고친다.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HANGUL = re.compile(r"[가-힣]")
IMPORT = re.compile(r"^import\s+([\w.]+)")
# 선언 이름: fun/val/var/class/object/interface/typealias 뒤의 이름(백틱 이름 포함)
DECLARATION = re.compile(
    r"\b(?:fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(`[^`]+`|[^\s(:<={,]+)"
)
FAKE_NAME = re.compile(r"\b(?:class|object)\s+(Simulated|Demo|Fake|Mock|Dummy)\w*")

MODULES = ["domain", "data", "pleos", "app"]

# 모듈이 import하면 안 되는 패키지 접두사. 의존 방향: app → data, pleos → domain.
FORBIDDEN_IMPORTS = {
    "domain": ["android.", "androidx.", "com.drivesplit.data", "com.drivesplit.pleos", "com.drivesplit.app",
               "ai.pleos.", "retrofit2.", "okhttp3."],
    "data": ["com.drivesplit.pleos", "com.drivesplit.app", "ai.pleos.", "android.car."],
    "pleos": ["com.drivesplit.data", "com.drivesplit.app", "androidx.room.", "retrofit2.", "okhttp3."],
    "app": ["androidx.room.", "retrofit2.", "okhttp3.", "ai.pleos.", "android.car."],
}

# 화면(app/ui)은 구현 모듈을 직접 쓰지 않고 도메인 인터페이스만 본다. 예외는 화면에서 바로 써야 하는 입력 장치뿐.
UI_ALLOWED_IMPLEMENTATIONS = {"com.drivesplit.pleos.SpeechInput", "com.drivesplit.pleos.PleosPermissions"}


def kotlin_files(module: str, source_set: str):
    base = ROOT / module / "src" / source_set
    return sorted(base.rglob("*.kt")) if base.exists() else []


def strip_strings_and_comments(line: str) -> str:
    """식별자 검사에서 문자열·주석의 한글은 허용하므로 걷어낸다."""
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    line = re.sub(r"'(?:\\.|[^'\\])'", "''", line)
    return line.split("//", 1)[0]


def check() -> list[str]:
    problems = []

    def report(path: Path, line_no: int, message: str):
        problems.append(f"{path.relative_to(ROOT)}:{line_no}: {message}")

    for module in MODULES:
        for source_set in ("main", "test"):
            for path in kotlin_files(module, source_set):
                in_block_comment = False
                for no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                    text = raw.strip()

                    imported = IMPORT.match(text)
                    if imported:
                        name = imported.group(1)
                        for prefix in FORBIDDEN_IMPORTS[module]:
                            if name.startswith(prefix):
                                report(path, no, f"[의존 방향] :{module} 모듈은 {prefix}… 을 import하면 안 돼요 ({name})")
                        in_ui = module == "app" and "/com/drivesplit/app/ui/" in path.as_posix()
                        if in_ui and name.startswith(("com.drivesplit.data", "com.drivesplit.pleos")) \
                                and name not in UI_ALLOWED_IMPLEMENTATIONS:
                            report(path, no, f"[의존 방향] 화면은 구현({name}) 대신 도메인 인터페이스를 써요. 구현은 AppContainer에서만 골라요")
                        continue

                    # 블록 주석·KDoc 안은 건너뛴다.
                    if in_block_comment:
                        if "*/" in text:
                            in_block_comment = False
                        continue
                    if text.startswith("/*"):
                        in_block_comment = "*/" not in text
                        continue

                    code = strip_strings_and_comments(raw)
                    for declared in DECLARATION.findall(code):
                        if HANGUL.search(declared):
                            report(path, no, f"[이름] 식별자는 영어 camelCase로 써요. 한글 이름 금지 ({declared})")

                    if source_set == "main":
                        fake = FAKE_NAME.search(code)
                        if fake:
                            report(path, no, f"[가짜 데이터] 앱 코드에 {fake.group(0)} 금지. 실제 Pleos SDK를 쓰고, 에뮬레이터 입력은 tools/에서 흉내 내요")
    return problems


def kotlin_changed() -> bool:
    """커밋하지 않은 Kotlin 변경이 있는가."""
    status = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True).stdout
    return any(line.rstrip().endswith((".kt", ".kts")) for line in status.splitlines())


def stop_check() -> int:
    """
    "완료"라고 하기 전에 검증(Verification Before Done). Kotlin을 고쳤는데 규칙이나 도메인 테스트가 깨져 있으면
    멈추지 못하게 하고 이유를 돌려준다. 이미 한 번 막혔던 Stop(stop_hook_active)은 다시 막지 않는다(무한 반복 방지).
    """
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        payload = {}
    if payload.get("stop_hook_active") or not kotlin_changed():
        return 0

    problems = check()
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    tests = subprocess.run(
        [str(ROOT / "gradlew"), "-q", ":domain:test"], cwd=ROOT, env=env, capture_output=True, text=True,
    )
    if not problems and tests.returncode == 0:
        return 0

    print("아직 완료가 아니에요. verify-architecture 스킬 절차대로 고친 뒤 다시 확인하세요.", file=sys.stderr)
    for p in problems:
        print(f"  {p}", file=sys.stderr)
    if tests.returncode != 0:
        tail = "\n".join((tests.stdout + tests.stderr).strip().splitlines()[-15:])
        print(f"  도메인 테스트 실패:\n{tail}", file=sys.stderr)
    return 2


def main():
    if "--stop" in sys.argv:
        return stop_check()
    hook = "--hook" in sys.argv
    if hook:
        try:
            payload = json.load(sys.stdin)
        except json.JSONDecodeError:
            return 0
        edited = (payload.get("tool_input") or {}).get("file_path") or (payload.get("tool_response") or {}).get("filePath") or ""
        if not edited.endswith((".kt", ".kts")):
            return 0

    problems = check()
    if not problems:
        return 0
    out = sys.stderr if hook else sys.stdout
    print("컨벤션 위반이 있어요 (CLAUDE.md 참고):", file=out)
    for p in problems:
        print(f"  {p}", file=out)
    return 2 if hook else 1


if __name__ == "__main__":
    sys.exit(main())

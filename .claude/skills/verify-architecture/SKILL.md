---
name: verify-architecture
description: AUTONOMICS의 아키텍처(모듈 의존 방향)와 코드 컨벤션을 검증한다. Kotlin 코드를 고친 뒤 "완료"라고 말하기 전, 커밋 전, 리뷰를 요청받았을 때 반드시 쓴다. 자동 검사(check_conventions.py, 도메인 테스트, 빌드)와 자동으로 못 잡는 규칙(저장 형식 호환, 단위 이름, 스레드, 테스트 동반)을 diff 기준으로 확인하고 결과를 보고한다.
---

# AUTONOMICS 아키텍처·컨벤션 검증

규칙의 원본은 저장소 루트 `CLAUDE.md`의 "모듈과 의존 방향", "지켜야 할 규칙"이다. 이 스킬은 그 규칙을 **증거와 함께** 확인하는 절차다. 추측으로 통과시키지 않는다.

## 1. 무엇을 바꿨는지 본다

```bash
git status --short
git diff --stat HEAD
```

바뀐 파일을 모듈별(`domain`, `data`, `pleos`, `app`, `web`, `tools`)로 나눠 둔다. 아래 단계는 바뀐 모듈만 깊게 본다.

## 2. 자동 검사 (모두 통과해야 다음 단계)

```bash
python3 tools/check_conventions.py
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew -q :domain:test
./gradlew -q :app:assembleDebug        # app·data·pleos를 고쳤을 때
```

실패하면 고치고 처음부터 다시 돌린다. 출력 그대로를 보고에 붙인다.

## 3. 자동으로 못 잡는 규칙을 diff로 확인한다

`git diff HEAD -- <파일>`을 읽으며 해당하는 것만 확인한다.

| 바뀐 것 | 확인할 것 |
|---|---|
| `@Serializable` 모델의 필드 이름·타입 | 옛 이름에 `@SerialName`이 붙었는가. 지난 기록·진행 중 여행 파일(`active_trip.json`)이 그대로 읽히는가. 새 필드에 기본값이 있는가 |
| Room 엔티티·DAO | DB 버전을 올리고 `Migration`을 추가했는가(지우고 새로 만들기 금지) |
| 거리·시간·돈·에너지 변수 | 이름에 단위가 있는가(`Km`, `Kmh`, `Ms`, `Won`, `Kwh`). 연료·전기 공통 값은 `energy` + `Powertrain.unit` |
| `:domain` 계산·규칙 | `domain/src/test`에 그 동작을 설명하는 테스트가 추가됐는가 |
| SDK·파일·네트워크 호출 | `Dispatchers.IO`(또는 `flowOn(IO)`)에서 도는가. 메인 스레드 동기 IPC 없음 |
| 차량 샘플 시각 | `SystemClock.elapsedRealtime()`인가(벽시계 금지) |
| 새 외부 기능 | `domain/repository` 인터페이스 → `:data`/`:pleos` 구현 → `AppContainer` 연결 순서인가. 화면이 구현을 직접 만들지 않는가 |
| 새 권한 | 그 기능의 모듈 매니페스트에 선언했는가. 실행 중 허락이 필요한 권한이면 `PleosPermissions`에 넣었는가 |
| 주행 중 화면 조작 | 정차 중에만 열리는가(`state.isStopped`) |
| 주석 | "무엇"이 아니라 "왜"를 쓰는가. 한국어 |

## 4. 실제로 동작하는지 (해당할 때)

차량 데이터·화면 흐름을 바꿨다면 에뮬레이터에서 확인한다. 가짜 데이터로 대신하지 않는다.

```bash
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
./gradlew -q :app:installDebug
$ADB forward tcp:8088 tcp:8088            # Virtual Console
python3 tools/emulator_drive.py           # 콘솔 속도에 맞춰 GPS·주행거리·배터리를 움직인다
$ADB exec-out screencap -p > /tmp/screen.png
```

시동 OFF/ON을 건드렸다면 `adb reboot` 후 여행이 이어지는지 본다.

## 5. 보고

다음 형식으로 짧게 보고한다. 통과하지 못한 항목을 숨기지 않는다.

```
검증 결과
- 컨벤션 검사: 통과 / 위반 N건 (목록)
- 도메인 테스트: N개 통과 / 실패 (이름)
- 빌드: 성공 / 실패
- 수동 규칙: 확인한 항목과 결과 (해당 없음은 생략)
- 에뮬레이터: 확인한 장면 / 하지 못함(이유)
```

## 6. 실수를 발견했다면

같은 실수가 반복되지 않게 `tasks/lessons.md`에 한 줄(무엇을 잘못했나 → 규칙)로 남긴다. 자동으로 잡을 수 있는 규칙이면 `tools/check_conventions.py`에도 추가하고 `CLAUDE.md`를 맞춘다.

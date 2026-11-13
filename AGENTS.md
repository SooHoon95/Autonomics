# AUTONOMICS

현대차·기아 Pleos Connect(AAOS) 차량용 앱. 함께 탄 구간만큼 실제로 쓴 연료·전기 값을 나누고(정산 모드),
혼자 운전할 때는 구간별 운전 효율을 보여준다(솔로 모드). Pleos 앱 공모전 출품작. 사용자와는 한국어로 이야기한다.

## 작업 방식 (보리스 6원칙)

1. **계획 먼저** — 3단계 이상이거나 모듈 경계·저장 형식·차량 데이터 흐름을 건드리는 작업은 Plan 모드로 시작해 `tasks/todo.md`에 체크리스트(검증 단계 포함)를 쓰고 사용자 확인 후 구현한다. 진행 중 계획이 틀어지면 밀어붙이지 말고 멈춰서 다시 계획한다.
2. **서브에이전트로 컨텍스트 지키기** — Pleos 문서 조사(JS 렌더링이라 브라우저 필요), SDK AAR 분석(javap), 넓은 코드 탐색, 독립 리뷰는 서브에이전트에 맡긴다. 하나에 한 과제만 주고 결론만 받는다.
3. **자기 개선 루프** — 사용자에게 지적받거나 버그를 고치면 `tasks/lessons.md`에 "무엇을 잘못했나 → 규칙" 한 줄을 남긴다. 세션 시작 때 SessionStart 훅이 이 파일을 읽어 준다. 자동으로 잡을 수 있는 규칙이면 `tools/check_conventions.py`에도 넣는다.
4. **완료 전 검증** — 동작을 증명하기 전에는 "완료"라고 하지 않는다. Kotlin을 고쳤으면 `verify-architecture` 스킬 절차를 따르고, 차량 데이터·화면 흐름을 바꿨으면 에뮬레이터에서 실제로 확인한다. 못 한 확인은 못 했다고 말한다. Stop 훅이 컨벤션 검사와 도메인 테스트를 마지막으로 강제한다.
5. **균형 잡힌 우아함** — 단순하지 않은 변경은 "더 단순하고 맞는 방법이 있나?"를 한 번 묻는다. 임시 땜질 대신 근본 원인을 고친다. 간단한 수정에는 과설계하지 않는다.
6. **스스로 버그 고치기** — 버그를 받으면 사용자에게 디버깅을 떠넘기지 않는다. `adb logcat`, 실패한 테스트, Virtual Console 상태로 원인을 찾아 고치고, 원인과 확인 결과를 보고한다.

핵심: **단순하게, 근본 원인, 필요한 만큼만 바꾸기. 가짜 데이터 금지 — 차량 값은 항상 실제 Pleos SDK.**

## 하네스 (스킬·훅)

| 장치 | 위치 | 언제 | 하는 일 |
|---|---|---|---|
| 스킬 `verify-architecture` | `.Codex/skills/verify-architecture/SKILL.md` | Kotlin을 고친 뒤 완료 보고 전, 커밋 전, 리뷰 요청 시 | 자동 검사 + diff로 수동 규칙 확인 + 에뮬레이터 확인 + 형식에 맞춘 보고 |
| SessionStart 훅 | `.Codex/settings.json` | 세션 시작 | `tasks/lessons.md`를 컨텍스트에 넣는다 |
| PostToolUse 훅 | `.Codex/settings.json` | Write·Edit로 `.kt`/`.kts`를 고칠 때마다 | `check_conventions.py --hook`, 위반이면 즉시 알림(exit 2) |
| Stop 훅 | `.Codex/settings.json` | 응답을 끝내려 할 때 (커밋 안 된 Kotlin 변경이 있으면) | 컨벤션 검사 + `:domain:test`, 실패면 끝내지 못하게 막음(한 번만) |
| git pre-commit | `.githooks/pre-commit` (`core.hooksPath`) | 커밋 | 컨벤션 검사 + Kotlin 변경 시 도메인 테스트 |

- 훅은 Codex를 이 폴더에서 열어야 적용된다: `cd ~/Desktop/Code/drive-split && Codex`. 확인·끄기는 `/hooks`.
- 새 클론에서는 `git config core.hooksPath .githooks`를 한 번 실행한다.

## 모듈과 의존 방향 (클린 아키텍처)

```
:app ──▶ :data  ──▶ :domain
  │  └─▶ :pleos ──▶ :domain
  └──────────────▶ :domain
```

| 모듈 | 하는 일 | 쓰면 안 되는 것 |
|---|---|---|
| `:domain` (순수 Kotlin/JVM) | 모델, 정산 규칙(`SettlementCalculator`, `EnergyModel`), 구간 기록(`TripRecorder`), 여행 진행(`TripSession`), 저장소 인터페이스(`domain/repository`) | Android, androidx, Room, Retrofit, Pleos SDK, 다른 모듈 |
| `:data` | 저장소 구현: Room 여행 기록, 진행 중 여행 파일, 오피넷 유가, 카카오 지명 | `:pleos`, `:app`, Pleos SDK |
| `:pleos` | Pleos SDK 어댑터: Vehicle(주행거리·배터리·연료·기어), NaviHelper(위치·주소), SpeechToText, AAOS 차속 | `:data`, `:app`, Room, Retrofit |
| `:app` | Compose 화면, `MainViewModel`, 포그라운드 서비스, 부팅 리시버, 수동 DI(`AppContainer`) | Room, Retrofit, Pleos SDK 직접 사용 |

- 구현을 고르는 곳은 `AppContainer` 하나뿐. 화면·ViewModel은 도메인 인터페이스만 본다. 예외는 `pleos.SpeechInput`, `pleos.PleosPermissions`(입력 장치·권한).
- 새 외부 기능: ① `domain/repository` 인터페이스 → ② `:data`/`:pleos` 구현 → ③ `AppContainer` 연결.
- 권한은 쓰는 모듈의 매니페스트에(차량·위치·음성은 `:pleos`, 인터넷은 `:data`).

## 지켜야 할 규칙

[자동]은 `tools/check_conventions.py`가 검사한다. 나머지는 `verify-architecture` 스킬이 diff로 확인한다.

1. [자동] **의존 방향**: 위 표의 "쓰면 안 되는 것"을 import하지 않는다.
2. [자동] **이름은 영어 camelCase**: 테스트 이름 포함. 백틱 한글 금지. 주석·화면 문구는 한국어.
3. [자동] **가짜 데이터 금지**: `src/main`에 `Simulated*`/`Demo*`/`Fake*`/`Mock*` 구현 금지. 테스트용 가짜는 `src/test`에만. 에뮬레이터 입력은 `tools/`에서 흉내 낸다.
4. **저장 형식 호환**: `@Serializable` 필드 이름을 바꾸면 `@SerialName("옛 이름")`, 새 필드는 기본값. Room 스키마는 버전 + `Migration`.
5. **단위는 이름에**: `distanceKm`, `speedKmh`, `timestampMs`, `amountWon`, `batteryKwh`. 연료·전기 공통 값은 `energy` + `Powertrain.unit`.
6. **스레드**: SDK 연결·파일·네트워크는 `Dispatchers.IO`. 메인 스레드 동기 IPC 금지. 샘플 시각은 `SystemClock.elapsedRealtime()`.
7. **여러 곳에서 불리는 진입점은 한 번만**: 재개처럼 동시에 불릴 수 있으면 `Mutex`로 막는다.
8. **도메인 로직엔 테스트**: 계산·규칙을 바꾸면 `domain/src/test`에 동작을 설명하는 테스트를 추가한다.
9. **주석은 "왜"**: 무엇을 하는지는 코드로. KDoc은 한국어 한두 줄.

## 코드 스타일

- Kotlin 공식 스타일, 들여쓰기 4칸, 여러 줄 인자·컬렉션에 끝 쉼표.
- 상수는 `private const val`(파일 위 또는 `companion object`), 의미·단위를 KDoc으로.
- Compose: `StateFlow` → `collectAsStateWithLifecycle()`. 주행 중 조작은 막고 정차 중에만 연다(`state.isStopped`).
- 테스트: JUnit4, 이름은 동작을 설명하는 camelCase 문장(`droppedOffPassengerDoesNotPayLaterSegments`).

## 빌드·테스트

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :domain:test
./gradlew :app:installDebug
python3 tools/check_conventions.py
```

- adb: `/opt/homebrew/share/android-commandlinetools/platform-tools/adb` (PATH에 없음)
- AVD `pleos_connect`(Pleos Connect v2.1, 전기차). 앱은 사용자 10으로 돈다(`run-as com.drivesplit.app --user 10`).

## 에뮬레이터에서 실제 SDK로 테스트

- Virtual Console: `adb forward tcp:8088 tcp:8088` → http://localhost:8088 (기어·속도·배터리·주행거리).
- 콘솔은 속도만 바뀐다. `python3 tools/emulator_drive.py`를 켜 두면 실제 도로(OpenStreetMap)를 따라 GPS·주행거리·배터리가 함께 움직인다.
- 시동 OFF/ON 확인: `adb reboot`.
- 에뮬레이터 내비(NaviHelper) 위치는 GPS를 따라가지 않고 "수정구 시흥동"에 고정.
- 사용자가 에뮬레이터를 쓰는 중일 수 있다. 탭·강제 종료 전에 화면 상태를 먼저 본다.

## 웹(정산 페이지)

- `web/s.html`: QR로 여는 동승자 정산서. 정산 내용은 URL `#` 뒤에만 있고 서버에 저장하지 않는다.
- `netlify/functions/prices.mjs` → `/api/prices`: 오늘 유가(오피넷)와 충전 요금표를 내려준다. 앱은 오피넷을 직접 부르지 않는다(무료 한도 하루 1,500건, 키 노출). CDN이 3시간 캐시한다. 오피넷 키는 Netlify 환경 변수 `OPINET_KEY`에만 둔다. 충전 요금 고시가 바뀌면 이 함수의 `PUBLIC_FAST`만 고친다.
- Netlify 사이트 `drivesplit` (https://drivesplit.netlify.app). 배포: `npx netlify-cli@latest deploy --prod --no-build --site 39c7a76e-537c-475a-809d-151f6db257dd` (설정은 `netlify.toml`).

## 커밋

- 사용자가 요청할 때만. 요약은 영어 명령형 한 줄, 본문에 바뀐 점. `local.properties`(키)는 커밋 금지.

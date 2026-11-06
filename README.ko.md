# Xiaomi Bluetooth Remote × Android TV

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · **한국어** · [繁體中文](README.zh-TW.md) · [Deutsch](README.de.md)

> Xiaomi Bluetooth Remote(2 / 2 Pro)를 어떤 Android TV에서든 모든 키가 동작하게 만듭니다. Sony BRAVIA 실측 검증 완료, root 불필요.

CleanYourTV는 Android TV의 겉모습을 Apple 스타일로 바꿔 주지만, 리모컨이 손에 쥐어지는 느낌까지 바꿔 주지는 못합니다. Apple TV 리모컨은 단품이 20만 원이 넘는데, Android TV 진영에서 그것에 가장 가까운 하드웨어가 Xiaomi Bluetooth Remote 2 / 2 Pro입니다. 정가 99위안, 중고 거래에서 6할 정도(60위안 안팎)면 새것을 구할 수 있습니다.

문제는 둘이 공식적으로는 연결되지 않는다는 점입니다. Xiaomi 기기는 Xiaomi TV에서만 동작을 보장하고, Sony의 블루투스는 정품 액세서리만 받아들입니다. 이 저장소는 그 벽을 허무는 프로젝트입니다.

<table>
<tr>
<td align="center"><img src="docs/images/before-sony-remote.jpg" width="280"><br><sub><b>Before</b> · Sony 정품 리모컨</sub></td>
<td align="center"><img src="docs/images/after-xiaomi-remote-2pro.jpg" width="280"><br><sub><b>After</b> · Xiaomi Bluetooth Remote 2 Pro</sub></td>
</tr>
</table>

## 동작 상태

| 키 | 상태 | 설명 |
|---|---|---|
| 방향 / 확인 / 뒤로 | ✅ 기본 | 표준 HID 키보드 키코드. 페어링 직후 바로 동작 |
| 볼륨 / 전원 | ✅ 기본 | 위와 같음 |
| 홈 | ⚠️ 매핑 필요 | 리모컨이 보내는 값은 `KEYCODE_MOVE_HOME(122)`, 텍스트 입력에서 "줄 맨 앞으로" 쓰는 키입니다. TV에는 텍스트 필드가 없으니 눌러도 아무 반응이 없습니다 |
| TV | ⚠️ 매핑 필요 | `KEYCODE_GRAVE(68)`을 보냅니다. 이것도 아무도 받지 않는 빈 키입니다. 이 프로젝트에서 "입력 전환"으로 바꿉니다 |
| 메뉴 | ✅ | 펌웨어에 따라 다르며 대개 기본 동작 |
| 음성 | ❓ 미검증 | 키코드는 잡힙니다(`KEYCODE_F5(135)`). 다만 TV 본체에 마이크가 없어 수음 구간이 해결되지 않았습니다. 아래 참고 |

수정 후 상태: **방향, 확인, 뒤로, 홈, 볼륨, 메뉴, 전원, TV(입력 전환으로 변경)가 모두 정상 동작하고, 재부팅 후에도 자동으로 다시 연결됩니다.**

## 세 개의 벽 (이 프로젝트의 전부)

| 흔한 오해 | 실제 |
|---|---|
| Xiaomi 리모컨은 Xiaomi TV 전용이다 | 속은 표준 BLE HID(HOGP)입니다. 블루투스 HID 호스트라면 무엇이든 받습니다. Xiaomi 생태계에 묶인 것은 음성 키와 일부 전용 키뿐입니다 |
| Sony가 블루투스 화이트리스트를 걸어서 기기가 안 잡힌다 | 잠긴 것은 프로토콜 스택이 아니라 **설정의 "액세서리 추가" 화면**입니다. 기기 유형으로 필터링하기 때문에 서드파티 리모컨이 목록에 뜨지 않습니다. 스택은 내내 정상적으로 스캔하고 있습니다 |
| 페어링은 됐는데 전혀 반응이 없다 | Android 9가 `BluetoothHidHost.connect()` / `setPriority()`를 숨겨진 API 차단 목록에 넣었습니다. 평범한 리플렉션은 `NoSuchMethodException`을 던지므로 "Sony가 메서드를 지웠다"처럼 보입니다. `VMRuntime.setHiddenApiExemptions("L")`을 먼저 호출하면 둘 다 통과하고 연결이 즉시 올라갑니다 |

첫 번째 벽은 이렇게 생겼습니다. 화면은 계속 돌아가는데 로그에는 기기가 계속 찍힙니다:

![Sony add-accessory screen](docs/images/sony-add-accessory.png)

## 빠른 시작

### 사전 작업: TV에서 ADB 켜기 (직접 해야 하는 유일한 단계)

AI가 대신할 수 없습니다. 아직 TV에 연결되지 않은 상태이기 때문입니다.

**Sony BRAVIA (Android 9 / Google TV)**

1. `설정 → 시스템 → 정보`
2. **빌드**에 커서를 두고 OK를 **7번** 연타(최신 펌웨어는 **Android TV OS build**에서 10번)해서 "개발자가 되었습니다"를 띄웁니다
3. `설정 → 시스템 → 개발자 옵션`으로 돌아가 **USB 디버깅**을 켜고, **네트워크 디버깅 / ADB over network**가 있으면 함께 켭니다
4. TV는 켜 둔 채로 두세요. 대기 모드로 보내면 안 됩니다(일부 펌웨어는 대기에서 깨어날 때 디버깅 스위치를 끕니다)

이어서 TV의 LAN IP를 확인합니다. PC와 같은 서브넷이어야 합니다.

### 방법 A: AI에게 맡기기 (권장)

이 저장소는 그 자체로 agent 스킬입니다. `SKILL.md`는 AI가 읽는 절차, `assets/`는 AI가 빌드할 코드, `scripts/tv.sh`는 AI가 호출할 명령, `references/pitfalls.md`는 AI가 다시 밟지 말아야 할 우회로 모음입니다. skills 디렉터리에 clone 하세요:

```bash
git clone https://github.com/koukanana/openappletvremot ~/.workbuddy/skills/openappletvremot
```

(Claude Code 사용자는 `~/.claude/skills/openappletvremot`. 다른 agent는 저장소 루트를 읽을 수 있으면 됩니다)

그리고 이렇게 말하면 됩니다:

> openappletvremot 스킬로 이 TV(192.168.x.x)에 Xiaomi Bluetooth Remote 2 Pro를 연결해 줘. 홈 키와 TV 키도 고쳐 줘. 스킬과 코드는 https://github.com/koukanana/openappletvremot

그러면 AI가 순서대로 처리합니다: ADB 연결 → 두 도구 빌드·설치 → 기기 이름 키워드로 스캔·페어링 → 숨겨진 API 제한 해제 후 HID 연결 → 키 매핑 기록 → 키를 눌러 확인 요청. 사용자가 손대는 곳은 세 군데뿐입니다: TV에서 "허용" 누르기, 리모컨의 홈+메뉴 5초 길게 누르기, 마지막에 키 눌러 확인.

스킬 내부의 참조 관계(AI가 이 사슬을 따라갑니다):

```
프롬프트 "openappletvremot 스킬 사용"
   └── SKILL.md          절차 §1~§6, 각 절이 구체적 스크립트/소스를 지목
        ├── scripts/tv.sh        connect / prep / pair / hid / keys / map / enable
        ├── assets/btpair/        페어링 + 숨겨진 API 우회 + HID 연결 (Java)
        ├── assets/btremap/       접근성 기반 키 매핑 (Java)
        ├── references/           Sony 실측 데이터, 안 되는 13가지 경로
        └── docs/                 스토리 버전과 기술 버전 (중국어)
```

### 방법 B: 수동

```bash
# 0. 의존성: adb + JDK + Android SDK build-tools / platform-28
#    없어도 됩니다 — build_apk.sh가 ~/.androidtv/sdk에 자동으로 받습니다 (약 150MB, 최초 1회)

# 1. 연결 (TV에 권한 창이 뜨면 허용)
./scripts/tv.sh <TV_IP> connect
./scripts/tv.sh <TV_IP> info

# 2. 두 도구를 빌드·설치하고 키 매핑 서비스 활성화
./scripts/tv.sh <TV_IP> prep

# 3. 리모컨을 페어링 모드로: 홈 + 메뉴를 5초 길게 눌러 LED가 빠르게 깜빡이면
#    이름 키워드로 스캔 후 페어링 (MAC 주소 불필요, 8회 × 20초 반복 스캔)
./scripts/tv.sh <TV_IP> pair

# 4. HID 연결 확인: mInputDevices가 : 2, dumpsys input에 eventN이 추가되면 정상
./scripts/tv.sh <TV_IP> hid

# 5. 키 매핑 기록 및 활성화 (홈 → 홈 화면, TV → Sony 입력 선택)
./scripts/tv.sh <TV_IP> map '122>g>2;68>k>com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178'
./scripts/tv.sh <TV_IP> enable
```

APK만 다시 빌드하려면(TV는 건드리지 않음):

```bash
./scripts/build_apk.sh assets/btpair  release/btpair.apk
./scripts/build_apk.sh assets/btremap release/btremap.apk
```

매핑 문자열 형식: `원본키코드>동작>인자`, 여러 개는 `;`로 구분. 동작은 `g`=전역 동작(1 뒤로 / 2 홈 / 3 최근 / 4 알림), `a`=액티비티 실행, `k`=키 이벤트를 붙여 액티비티 실행, `s`=어시스턴트 인텐트.

다른 브랜드 TV라면 `k` 동작의 액티비티를 해당 브랜드의 입력 선택 화면으로 바꾸면 됩니다. 자세한 내용은 `references/sony-bravia.md`.

## 동작 원리

합쳐서 20KB가 안 되는 APK 두 개, 둘 다 root가 필요 없습니다:

- **BTPair** (`assets/btpair`): 리플렉션으로 `BluetoothAdapter.startDiscovery`와 `createBond`를 호출해 페어링하고, `BluetoothHidHost.setPriority(100)` / `connect()`로 리모컨을 표준 입력 장치로 등록합니다. 핵심은 숨겨진 API 예외 처리 한 줄입니다. 이게 없으면 `connect()`는 항상 `NoSuchMethodException`을 던집니다.
- **BTRemap** (`assets/btremap`): 최소 구성의 `AccessibilityService`. `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS`를 선언하고, 할 수 있는 일은 두 가지뿐입니다. 전역 동작 실행(`GLOBAL_ACTION_HOME` 등) 또는 액티비티 실행(`KEY_EVENT` extra 동반 가능). 매핑은 `Settings.Secure.btremap_map`에 저장되며 재부팅 없이 즉시 적용됩니다.

"A 키를 가로채 B 키를 보내기"를 하지 않는 이유: `INJECT_EVENTS`는 시그니처 권한이라 서드파티 앱이 가질 수 없습니다. 따라서 키 매핑의 상한은 "전역 동작 + 액티비티 실행"이고, 홈과 TV가 마침 그 범위에 들어갑니다.

## 음성 키에 대해 (미검증이지, 불가능이 아님)

확인된 사실: 이 TV 본체에는 **마이크가 없습니다**. `/proc/asound/cards`가 비어 있고, `audio_policy`의 입력 장치도 비어 있으며, `pm list features`에는 `android.hardware.audio.output`만 있습니다. Sony의 음성 경로는 "리모컨 마이크 → 블루투스로 회신 → TV 인식"이므로, 음성 키를 "음성 실행"에 매핑해도 UI는 뜰지언정 TV 쪽에 소리를 받을 곳이 없습니다.

키 자체는 동작합니다(`KEYCODE_F5(135)` 확인). 처리 방식은 TV 키와 완전히 같아서 `135>s>`로 `ACTION_ASSIST`에 매핑하면 됩니다. 실제로 막힌 것은 오디오입니다. 이어서 해 볼 분은 PR 환영합니다.

## 되돌리기

```bash
# 원래 키 동작으로 즉시 복구
adb shell settings put secure enabled_accessibility_services ''

# 제거 (페어링은 남아 리모컨은 계속 쓰이지만 키는 원래대로)
adb uninstall com.atvremote.remap
adb uninstall com.atvremote.pair
```

마지막으로 TV에서 `설정 → 개발자 옵션 → USB 디버깅 권한 취소`. **ADB의 5555 포트는 평문입니다. 끝나면 닫으세요.**

## 구조

```
SKILL.md                    agent 스킬 진입점 (AI가 읽는 절차)
scripts/tv.sh               connect/pair/keys/map/enable 등을 묶은 래퍼
scripts/build_apk.sh        Gradle 없는 빌드: aapt2 → javac → d8 → zipalign → apksigner
assets/btpair/              페어링 + HID 활성화 (소스)
assets/btremap/             접근성 기반 키 매핑 (소스)
release/                    빌드된 APK (debug 서명, 개인용)
references/sony-bravia.md   Sony 메뉴 위치, 입력 선택 액티비티, 실측 명령
references/pitfalls.md      실측으로 확인된 13가지 막다른 길
docs/让小米强兼索尼.md        스토리 버전: 왜 만들었고 어떻게 뚫었나 (중국어)
docs/完整指南.md             기술 버전: 단계별 절차 + 프롬프트 전문 (중국어)
```

## 호환성

- Android 8.0 이상 (숨겨진 API 차단 목록은 API 28부터. 9.0 실측 완료), Android TV / Google TV 모두 가능
- 리모컨: Xiaomi Bluetooth Remote 2 Pro(검증), Xiaomi Bluetooth Remote 2(동일 구조, 이론상 가능, 미검증)
- TV: Sony BRAVIA 2018–2020 실측. 다른 브랜드도 원리는 같고 입력 선택 액티비티만 교체하면 됩니다
- PC: macOS 실측. Linux/Windows는 adb와 build-tools만 준비하면 됩니다

## 다른 브랜드 TV 확장 환영

현재는 Sony BRAVIA에서만 검증되었습니다. 다른 브랜드(TCL, Hisense, Skyworth, Xiaomi TV, Shield, Chromecast with Google TV…)도 원리는 동일하며, 차이는 **입력 선택 화면의 액티비티 이름**과 **일부 키코드** 두 가지뿐입니다.

다른 TV가 있다면 PR로 함께 채워 주세요. 실측 후 모델과 OS 버전을 적고 `references/<brand>.md`에 실측 데이터(메뉴 위치, 입력 선택 액티비티, 키코드 표)를 추가하면 됩니다. 코드는 대부분 수정할 필요가 없습니다.

## 면책

본인 TV의 블루투스 페어링과 시스템 설정을 바꾸는 작업입니다. 책임은 본인에게 있습니다: 보증 처리에 영향이 갈 수 있고, 페어링 정보가 남을 수 있으며, 시스템 업데이트 후 다시 해야 할 수 있습니다. 공용·운영 장비에서는 시도하지 마세요. 코드는 MIT이며 자유롭게 고쳐도 되지만, 망가뜨려도 책임지지 않습니다.

## License

MIT

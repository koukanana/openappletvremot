# Xiaomi Bluetooth Remote × Android TV

[简体中文](README.md) · [English](README.en.md) · **日本語** · [한국어](README.ko.md) · [繁體中文](README.zh-TW.md) · [Deutsch](README.de.md)

> Xiaomi Bluetooth Remote（2 / 2 Pro）を、どの Android TV でも全キー使えるようにする。Sony BRAVIA で実機検証済み、root 不要。

CleanYourTV は Android TV の見た目を Apple 風に変えてくれる。でも、リモコンの手触りまでは変えられない。Apple TV のリモコンは単体で 2 万円以上するが、Android TV 界隈でそれに一番近いハードが Xiaomi Bluetooth Remote 2 / 2 Pro だ。新品 99 元、フリマで 6 割程度（60 元前後）で買える。

ただし両者は公式には繋がらない。Xiaomi の機器は Xiaomi のテレビでしか動作保証されず、Sony の Bluetooth は純正アクセサリしか受け付けない。このリポジトリは、その壁を壊すためのもの。

<table>
<tr>
<td align="center"><img src="docs/images/before-sony-remote.jpg" width="280"><br><sub><b>Before</b> · Sony 純正リモコン</sub></td>
<td align="center"><img src="docs/images/after-xiaomi-remote-2pro.jpg" width="280"><br><sub><b>After</b> · Xiaomi Bluetooth Remote 2 Pro</sub></td>
</tr>
</table>

## 動作状況

| キー | 状態 | 補足 |
|---|---|---|
| 十字 / 決定 / 戻る | ✅ そのまま | 標準 HID キーボードのキーコード。ペアリング直後から使える |
| 音量 / 電源 | ✅ そのまま | 同上 |
| ホーム | ⚠️ 要リマップ | リモコンが送るのは `KEYCODE_MOVE_HOME(122)`、つまりテキスト入力で「行頭へ移動」するキー。テレビにテキスト欄はないので、押しても何も起きない |
| TV | ⚠️ 要リマップ | `KEYCODE_GRAVE(68)` を送る。これも誰も拾わない空きキー。本プロジェクトでは「入力切替」に割り当てる |
| メニュー | ✅ | ファーム次第。大抵はそのまま動く |
| 音声 | ❓ 未検証 | キーコードは取れる（`KEYCODE_F5(135)`）が、テレビ本体にマイクがなく、収音側が未解決。詳細は後述 |

修正後の状態：**十字・決定・戻る・ホーム・音量・メニュー・電源・TV（入力切替に変更）がすべて正常、再起動後も自動で再接続する。**

## 三つの壁（＝このプロジェクトの全部）

| 思い込み | 実際 |
|---|---|
| Xiaomi のリモコンは Xiaomi のテレビ専用 | 中身は標準 BLE HID（HOGP）。Bluetooth HID ホストなら何でも受け取れる。Xiaomi に縛られているのは音声キーと一部の専用キーだけ |
| Sony が Bluetooth をホワイトリスト制でロックしているから検出されない | ロックされているのはプロトコルスタックではなく、**設定の「アクセサリを追加」画面**。デバイスタイプでフィルタするので、サードパーティ製リモコンは一覧に出ない。裏ではずっとスキャンしている |
| ペアリングは成功したのに、まったく反応しない | Android 9 が `BluetoothHidHost.connect()` / `setPriority()` を hidden API のブロックリストに入れている。普通のリフレクションでは `NoSuchMethodException` になり、「Sony がメソッドを削除した」ように見える。`VMRuntime.setHiddenApiExemptions("L")` を先に呼べば両方通り、接続は即座に確立する |

一つ目の壁はこういう見た目。画面は回り続け、ログにはデバイスが出続けている：

![Sony add-accessory screen](docs/images/sony-add-accessory.png)

## クイックスタート

### 前提：テレビ側で ADB を有効にする（手作業が必要な唯一の工程）

AI には代われない。この時点でまだテレビに繋がっていないため。

**Sony BRAVIA（Android 9 / Google TV）**

1. `設定 → システム → このデバイスについて`
2. 「ビルド」にカーソルを合わせて OK を **7 回**（新しいファームでは「Android TV OS build」で 10 回）押し、「デベロッパーになりました」を出す
3. `設定 → システム → 開発者向けオプション` に戻り、**USB デバッグ**を ON。**ネットワークデバッグ / ADB over network** があれば一緒に ON
4. テレビは起動したままにする（スリープ復帰でデバッグ設定が切れる機種がある）

続いてテレビの LAN IP を控える。PC と同じセグメントにいること。

### 方法 A：AI に任せる（推奨）

このリポジトリはそのまま agent skill でもある。`SKILL.md` は AI が読む手順、`assets/` は AI がビルドするコード、`scripts/tv.sh` は AI が叩くコマンド、`references/pitfalls.md` は AI が繰り返してはいけない遠回り集。skills ディレクトリに clone する：

```bash
git clone https://github.com/koukanana/openappletvremot ~/.workbuddy/skills/openappletvremot
```

（Claude Code なら `~/.claude/skills/openappletvremot`。他の agent はリポジトリ直下を読めればよい）

そしてこう伝える：

> openappletvremot スキルを使って、このテレビ（192.168.x.x）に Xiaomi Bluetooth Remote 2 Pro を繋いで。ホームキーと TV キーも直して。スキルとコードは https://github.com/koukanana/openappletvremot

あとは AI が順にやる：ADB 接続 → 2 つのツールをビルドしてインストール → デバイス名のキーワードでスキャンしてペアリング → hidden API の制限を解除して HID 接続 → キーマップを書き込み → キーを押して確認を依頼。あなたが触るのは 3 か所だけ：テレビで「許可」を押す、リモコンのホーム＋メニューを 5 秒長押し、最後にキーを押して確認。

スキル内部の参照関係（AI はこの鎖を辿る）：

```
プロンプト「openappletvremot スキルを使って」
   └── SKILL.md          手順 §1〜§6。各節が具体的なスクリプト／ソースを示す
        ├── scripts/tv.sh        connect / prep / pair / hid / keys / map / enable
        ├── assets/btpair/        ペアリング＋hidden API 回避＋HID 接続（Java）
        ├── assets/btremap/       アクセシビリティによるキーリマップ（Java）
        ├── references/           Sony の実測データ、通らない 13 の道
        └── docs/                 ストーリー版と技術版（中国語）
```

### 方法 B：手動

```bash
# 0. 依存：adb + JDK + Android SDK build-tools / platform-28
#    無くてもよい — build_apk.sh が ~/.androidtv/sdk に自動取得する（約 150MB、初回のみ）

# 1. 接続（テレビに許可ダイアログが出るので「許可」）
./scripts/tv.sh <TV_IP> connect
./scripts/tv.sh <TV_IP> info

# 2. 2 つのツールをビルド・インストールし、リマップサービスを有効化
./scripts/tv.sh <TV_IP> prep

# 3. リモコンをペアリングモードへ：ホーム＋メニューを 5 秒長押しして LED が速く点滅したら、
#    名前キーワードでスキャンしてペアリング（MAC アドレス不要、8 回 × 20 秒のループ走査）
./scripts/tv.sh <TV_IP> pair

# 4. HID 接続の確認：mInputDevices が : 2、dumpsys input に eventN が増えていれば OK
./scripts/tv.sh <TV_IP> hid

# 5. キーマップを書いて有効化（ホーム → ホーム画面、TV → Sony の入力切替）
./scripts/tv.sh <TV_IP> map '122>g>2;68>k>com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178'
./scripts/tv.sh <TV_IP> enable
```

APK だけ再ビルドしたい場合（テレビは触らない）：

```bash
./scripts/build_apk.sh assets/btpair  release/btpair.apk
./scripts/build_apk.sh assets/btremap release/btremap.apk
```

マップ文字列の書式：`元キーコード>動作>引数`、複数は `;` 区切り。動作は `g`＝グローバル動作（1 戻る / 2 ホーム / 3 最近 / 4 通知）、`a`＝アクティビティ起動、`k`＝キーイベント付きでアクティビティ起動、`s`＝アシストインテント。

他社テレビの場合は、`k` のアクティビティをそのメーカーの入力切替画面に差し替えるだけ。詳細は `references/sony-bravia.md`。

## 仕組み

合計 20KB に満たない 2 つの APK。どちらも root 不要：

- **BTPair**（`assets/btpair`）：リフレクションで `BluetoothAdapter.startDiscovery` と `createBond` を呼んでペアリングし、`BluetoothHidHost.setPriority(100)` / `connect()` でリモコンを標準入力デバイスとして登録する。肝は hidden API の除外設定の一行。これが無いと `connect()` は必ず `NoSuchMethodException` を投げる。
- **BTRemap**（`assets/btremap`）：最小構成の `AccessibilityService`。`CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS` を宣言し、できるのは 2 つだけ — グローバル動作の実行（`GLOBAL_ACTION_HOME` など）か、アクティビティの起動（`KEY_EVENT` extra 付きも可）。マップは `Settings.Secure.btremap_map` に保存され、再起動なしで即時反映。

「A キーを横取りして B キーを送る」をやらない理由：`INJECT_EVENTS` はシグネチャ権限で、サードパーティアプリには取れない。したがってリマップの上限は「グローバル動作＋アクティビティ起動」。ホームと TV はちょうどその範囲に収まる。

## 音声キーについて（未検証であって、不可能ではない）

分かっている事実：このテレビ本体に**マイクが無い**。`/proc/asound/cards` は空、`audio_policy` の入力デバイスも空、`pm list features` には `android.hardware.audio.output` しかない。Sony の音声は「リモコンのマイク → Bluetooth で戻す → テレビが認識」という経路なので、音声キーを「音声起動」に割り当てても、UI は出てもテレビ側に収音口が無い。

キー自体は通っている（`KEYCODE_F5(135)` を取得済み）。扱いは TV キーとまったく同じで、`135>s>` で `ACTION_ASSIST` に割り当てられる。詰まっているのは音声のほう。続きをやる人がいれば PR 歓迎。

## 元に戻す

```bash
# 元のキー動作に即時で戻す
adb shell settings put secure enabled_accessibility_services ''

# アンインストール（ペアリングは残るのでリモコンは使えるが、キーは元通り）
adb uninstall com.atvremote.remap
adb uninstall com.atvremote.pair
```

最後にテレビで `設定 → 開発者向けオプション → USB デバッグの承認を取り消す`。**ADB の 5555 は平文ポート。終わったら閉じる。**

## 構成

```
SKILL.md                    agent スキルの入口（AI が読む手順）
scripts/tv.sh               connect/pair/keys/map/enable をまとめたラッパー
scripts/build_apk.sh        Gradle なしのビルド：aapt2 → javac → d8 → zipalign → apksigner
assets/btpair/              ペアリング＋HID 有効化（ソース）
assets/btremap/             アクセシビリティによるキーリマップ（ソース）
release/                    ビルド済み APK（debug 署名、個人利用向け）
references/sony-bravia.md   Sony のメニュー位置、入力切替アクティビティ、実測コマンド
references/pitfalls.md      実測で通らなかった 13 の道
docs/让小米强兼索尼.md        ストーリー版：なぜ作ったか、どう通ったか（中国語）
docs/完整指南.md             技術版：手順＋プロンプト全文（中国語）
```

## 対応範囲

- Android 8.0 以上（hidden API ブロックリストは API 28 以降。9.0 で実機検証）、Android TV / Google TV とも可
- リモコン：Xiaomi Bluetooth Remote 2 Pro（検証済み）、Xiaomi Bluetooth Remote 2（同設計、理論上可、未検証）
- テレビ：Sony BRAVIA 2018〜2020 系で検証。他社も原理は同じで、入力切替のアクティビティ名だけ差し替える
- PC：macOS で検証。Linux/Windows は adb と build-tools を用意すればよい

## 他社テレビへの展開を歓迎

現状 Sony BRAVIA でしか通っていない。他社（TCL、Hisense、Skyworth、Xiaomi TV、Shield、Chromecast with Google TV…）も原理は同一で、違いは **入力切替画面のアクティビティ名** と **一部のキーコード** の 2 点だけ。

他のテレビをお持ちなら、ぜひ PR で埋めてほしい。実測したうえで、機種と OS バージョンを添えて `references/<brand>.md` に実測データ（メニュー位置、入力切替アクティビティ、キーコード表）を追加すればよい。コードはほぼ変更不要。

## 免責

ご自身のテレビの Bluetooth ペアリングとシステム設定を書き換える作業。自己責任で：保証の扱いが変わる、ペアリング情報が残る、システム更新後にやり直しになる、といった可能性がある。共用設備や本番機では試さないこと。コードは MIT。フォークも改変も自由だが、壊れても責任は負わない。

## License

MIT

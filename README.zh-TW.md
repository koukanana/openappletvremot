# 小米藍牙遙控器 × Android TV

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · **繁體中文** · [Deutsch](README.de.md)

> 讓小米藍牙遙控器（2 / 2 Pro）在任何 Android TV 上全鍵可用。Sony BRAVIA 實機驗證通過，不需要 root。

CleanYourTV 把 Android TV 的外觀換成了蘋果風格，但手感換不了。Apple TV 那支遙控器單買要兩百多人民幣，而 Android TV 陣營裡最接近它的硬體是小米藍牙遙控器 2 / 2 Pro——99 元（二手平台六折全新大約 60 元）。

這兩者官方是不通的：小米的裝置只保證小米電視能用，Sony 的藍牙又只認原廠配件。這個專案就是把那道牆拆掉。

<table>
<tr>
<td align="center"><img src="docs/images/before-sony-remote.jpg" width="280"><br><sub><b>Before</b> · Sony 原廠遙控器</sub></td>
<td align="center"><img src="docs/images/after-xiaomi-remote-2pro.jpg" width="280"><br><sub><b>After</b> · 小米藍牙遙控器 2 Pro</sub></td>
</tr>
</table>

## 效果

| 按鍵 | 狀態 | 說明 |
|---|---|---|
| 方向 / 確認 / 返回 | ✅ 原生 | 標準 HID 鍵盤鍵位，配對後直接可用 |
| 音量 / 電源 | ✅ 原生 | 同上 |
| 首頁 | ⚠️ 需修正 | 遙控器送出的是 `KEYCODE_MOVE_HOME(122)`——文字輸入框「移到行首」的鍵，電視上沒有輸入框，所以按了毫無反應 |
| TV | ⚠️ 需修正 | 送出 `KEYCODE_GRAVE(68)`，也是個沒人接收的空鍵；本專案把它改成「切換輸入」 |
| 選單 | ✅ | 依韌體而定，通常原生可用 |
| 語音 | ❓ 未驗證 | 按鍵抓得到（`KEYCODE_F5(135)`），但電視本機沒有麥克風，收音這一段沒跑通，詳見下文 |

修正後的狀態：**方向、確認、返回、首頁、音量、選單、電源、TV（改成切換輸入）全部正常，重新開機後自動重連。**

## 三道牆（也就是這個專案的全部內容）

| 你以為的問題 | 實際情況 |
|---|---|
| 小米遙控器只認小米電視 | 它走的是標準 BLE HID（HOGP），任何藍牙 HID 主機都能收。真正綁死小米生態的只有語音鍵和幾個專屬鍵 |
| Sony 鎖了藍牙白名單，掃不到裝置 | 鎖住的不是協定堆疊，是**系統設定裡那個「新增配件」畫面**——它按裝置類型過濾，第三方遙控器不會顯示。協定堆疊全程都在正常掃描 |
| 配對成功卻完全沒反應 | Android 9 把 `BluetoothHidHost.connect()` / `setPriority()` 放進了隱藏 API 黑名單，一般反射會丟出 `NoSuchMethodException`，看起來像「被 Sony 刪掉了」。用 `VMRuntime.setHiddenApiExemptions("L")` 解除限制後立刻就連上 |

第一道牆長這樣——畫面一直轉圈，日誌裡卻一直有裝置：

![Sony 新增配件畫面](docs/images/sony-add-accessory.png)

## 快速開始

### 前置：在電視上開啟 ADB（唯一必須你親手做的一步）

AI 代替不了這一步，因為此時它還沒連上你的電視。

**Sony BRAVIA（Android 9 / Google TV）**

1. `設定 → 系統 → 關於`
2. 游標停在「版本」上連按 OK **7 次**（部分新韌體要求在「Android TV OS build」上按 10 次），出現「您現在是開發人員」
3. 回到 `設定 → 系統 → 開發人員選項`，開啟 **USB 偵錯**；有「網路偵錯 / ADB over network」也一併開啟
4. 電視保持開機，別讓它待機（部分韌體從待機喚醒會關掉偵錯開關）

接著取得電視的區域網路 IP，電腦要和電視在同一個網段。

### 路線 A：交給 AI（推薦）

這個儲存庫同時就是一個 agent 技能：`SKILL.md` 是給它讀的執行流程，`assets/` 是它要編譯的程式碼，`scripts/tv.sh` 是它要呼叫的指令，`references/pitfalls.md` 是它別重走的坑。clone 下來放進技能目錄即可：

```bash
git clone https://github.com/koukanana/openappletvremot ~/.workbuddy/skills/openappletvremot
```

（Claude Code 使用者放到 `~/.claude/skills/openappletvremot`；其他 agent 只要能讀取本機目錄，指向儲存庫根目錄即可）

然後對它說：

> 幫我用 openappletvremot 技能，把這台電視（192.168.x.x）接上小米藍牙遙控器 2 Pro，首頁和 TV 鍵也要修好。技能和程式碼參考 https://github.com/koukanana/openappletvremot

它會依序做完：連 ADB → 編譯並安裝兩個小工具 → 按裝置名稱關鍵字掃描配對 → 解除隱藏 API 限制接上 HID → 寫入按鍵對應 → 請你按鍵驗收。你只在三個地方動手：電視上點「允許」、遙控器同時長按「首頁 + 選單」5 秒、最後按鍵驗收。

技能內部的三層引用關係（AI 會自己順著走）：

```
提示詞「用 openappletvremot 技能」
   └── SKILL.md          執行流程 §1~§6，每節都指向具體指令稿或程式碼位置
        ├── scripts/tv.sh        connect / prep / pair / hid / keys / map / enable
        ├── assets/btpair/       配對 + 解除隱藏 API 限制 + connect HID（Java 原始碼）
        ├── assets/btremap/      無障礙按鍵重新對應（Java 原始碼）
        ├── references/          Sony 實測資料、13 條走不通的路
        └── docs/                給人看的故事版與技術版
```

### 路線 B：手動

```bash
# 0. 相依：adb + JDK + Android SDK build-tools / platform-28
#    沒有也行——build_apk.sh 會自己下載到 ~/.androidtv/sdk（約 150MB，只需一次）

# 1. 連電視（電視會跳授權視窗，點允許）
./scripts/tv.sh <電視IP> connect
./scripts/tv.sh <電視IP> info

# 2. 編譯並安裝兩個工具，同時啟用重新對應服務
./scripts/tv.sh <電視IP> prep

# 3. 讓遙控器進入配對模式：同時長按【首頁 + 選單】5 秒到指示燈快閃，
#    再按名稱關鍵字掃描配對（不需要 MAC 位址，8 輪 × 20 秒循環掃描）
./scripts/tv.sh <電視IP> pair

# 4. 驗證 HID 通了：應看到 mInputDevices 裡該裝置為 2、dumpsys input 裡多出 eventN
./scripts/tv.sh <電視IP> hid

# 5. 寫入按鍵對應並啟用（首頁 → 首頁畫面，TV → Sony 輸入選擇）
./scripts/tv.sh <電視IP> map '122>g>2;68>k>com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178'
./scripts/tv.sh <電視IP> enable
```

只想重新編譯 APK，不動電視：

```bash
./scripts/build_apk.sh assets/btpair  release/btpair.apk
./scripts/build_apk.sh assets/btremap release/btremap.apk
```

對應字串格式：`來源鍵碼>動作>參數`，多條用 `;` 分隔。動作 `g`＝系統動作（1 返回 / 2 首頁 / 3 最近使用 / 4 通知），`a`＝啟動畫面，`k`＝啟動畫面並附帶按鍵，`s`＝語音助理意圖。

換其他品牌電視時，把 `k` 動作的 Activity 換成該品牌的輸入選擇畫面即可，詳見 `references/sony-bravia.md`。

## 它是怎麼運作的

兩個加起來不到 20KB 的 APK，都不需要 root：

- **BTPair**（`assets/btpair`）：以反射呼叫 `BluetoothAdapter.startDiscovery` 加 `createBond` 完成配對，再反射 `BluetoothHidHost.setPriority(100)` / `connect()` 把遙控器登記為標準輸入裝置。關鍵是那行隱藏 API 豁免——沒有它，`connect()` 永遠丟出 `NoSuchMethodException`。
- **BTRemap**（`assets/btremap`）：一個精簡到最小的 `AccessibilityService`，宣告 `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS`，只做兩件事——執行系統全域動作（`GLOBAL_ACTION_HOME` 等），或啟動某個 Activity（可附帶 `KEY_EVENT` extra）。對應設定存在 `Settings.Secure.btremap_map`，改完即時生效，不用重新開機。

為什麼不做「攔截 A 鍵再送出 B 鍵」：`INJECT_EVENTS` 是簽章權限，第三方應用程式拿不到。所以重新對應的上限就是「全域動作 + 啟動畫面」，首頁和 TV 鍵剛好落在能力範圍內。

## 關於語音鍵（未驗證，不是不能）

已知事實：這台電視本機**沒有麥克風**——`/proc/asound/cards` 為空、`audio_policy` 裡輸入裝置為空、`pm list features` 只有 `android.hardware.audio.output`。Sony 的語音鏈路是「遙控器麥克風收音 → 藍牙回傳 → 電視辨識」，所以只把語音鍵對應成「喚起語音」，畫面可能起來，但電視沒有收音入口。

按鍵本身是通的（抓到 `KEYCODE_F5(135)`），處理方式和 TV 鍵完全一致，`135>s>` 即可對應到 `ACTION_ASSIST`。真正卡住的是音訊。有興趣可以接著做，做通了歡迎送 PR。

## 還原

```bash
# 立刻恢復原始鍵位
adb shell settings put secure enabled_accessibility_services ''

# 卸載（配對關係保留，遙控器照用，只是鍵位回歸原樣）
adb uninstall com.atvremote.remap
adb uninstall com.atvremote.pair
```

最後在電視上 `設定 → 開發人員選項 → 撤銷 USB 偵錯授權`。**ADB 的 5555 是明文連接埠，做完就關。**

## 目錄結構

```
SKILL.md                    agent 技能主檔案（給 AI 讀的執行流程）
scripts/tv.sh               一條指令串起 connect/pair/keys/map/enable 等子指令
scripts/build_apk.sh        無 Gradle 建置：aapt2 → javac → d8 → zipalign → apksigner
assets/btpair/              配對 + HID 啟用（原始碼）
assets/btremap/             無障礙按鍵重新對應（原始碼）
release/                    預先編譯的 APK（debug 簽章，僅自用）
references/sony-bravia.md   Sony 機型的選單位址、輸入選擇 Activity、實測指令
references/pitfalls.md      13 條實測走不通的路，省你重試一遍
docs/让小米强兼索尼.md        故事版：為什麼做、怎麼跑通的（簡體中文）
docs/完整指南.md             技術版：逐步操作 + 提示詞全文（簡體中文）
```

## 相容性

- Android 8.0+（隱藏 API 黑名單機制自 API 28 起，9.0 實測通過），Android TV / Google TV 皆可
- 遙控器：小米藍牙遙控器 2 Pro（實測）、小米藍牙遙控器 2（同架構，理論可用，未實測）
- 電視：Sony BRAVIA 2018–2020 系列實測；其他品牌原理相同，只要替換輸入選擇的 Activity
- 電腦：macOS 實測，Linux/Windows 只需自備 adb 與 build-tools

## 幫助其他品牌電視用上小米藍牙遙控器

這個方案目前只在 Sony BRAVIA 上跑通。其他品牌（TCL、海信、創維、小米電視、Shield、Chromecast with Google TV…）原理完全一致，差別只有兩處：**輸入選擇畫面的 Activity 名稱**和**個別鍵碼**。

如果你手上有別的電視，歡迎一起把相容方案補齊——測通了直接送 PR，說明機型和系統版本，在 `references/` 裡補一份該品牌的實測資料（選單位址、輸入選擇 Activity、鍵碼表）就行，程式碼大概率不用改。

## 免責

改的是你自己電視的藍牙配對和系統設定，風險自負：可能導致保固爭議、配對記錄混亂、系統更新後需要重做。別在生產或公用裝置上試。程式碼 MIT，隨便改，改壞了不要找我。

## License

MIT

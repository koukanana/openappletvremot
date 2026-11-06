---
name: openappletvremot
description: 在没有 root 的 Android TV（索尼 BRAVIA / Google TV / 国产安卓电视）上用 adb 完成第三方蓝牙遥控器配对、HID 通道激活与按键重映射。当用户提出「电视遥控器配对不上」「系统蓝牙列表里搜不到遥控器」「遥控器某个键没反应 / 想把某个键改成输入选择」「adb 连电视装 APK」「电视蓝牙是不是被锁死了」「让小米蓝牙遥控器在电视上能用」时使用。
agent_created: true
repository: https://github.com/YOUR_GITHUB/openappletvremot
---

# Android TV 遥控器：配对 + HID 激活 + 按键重映射

> 开源仓库：<https://github.com/YOUR_GITHUB/openappletvremot>　·　本文件即该仓库的 `SKILL.md`
> 代码在 `assets/btpair`（配对 + HID）与 `assets/btremap`（按键映射），封装脚本在 `scripts/tv.sh`
> 面向读者的两篇文档：`docs/让小米强兼索尼.md`（故事版）、`docs/完整指南.md`（技术版）

无 root 完成整条链路。核心结论先给出：**Android 电视的蓝牙从来不是被厂商锁死的，两个真正的拦路点是「系统设置界面按设备类型过滤」和「Android 9 隐藏 API 黑名单」**，两者都能在应用层绕过。

## 适用判断

| 症状 | 走哪一节 |
|---|---|
| adb 连不上 / 想装 APK / 想截图抓日志 | §1 |
| 系统蓝牙界面搜不到遥控器（列表一直空） | §2 |
| 配对成功但遥控器完全没反应 | §3 |
| 某些键没反应、想把键改成别的功能 | §4、§5 |
| 语音键想用 | §6（实测未验证：按键能通，卡在电视没有麦克风） |
| 已经踩坑，想确认某条路是否白费 | `references/pitfalls.md` |

索尼机型的实测数据（键码、组件名、入口）在 `references/sony-bravia.md`。其他品牌可套用同一流程，只是组件名不同。

---

## §1 打通 adb（唯一需要用户在电视上动手的一步）

1. 电视上：`设置 → 系统(或设备偏好设置) → 关于`，连点 **版本** 7 次，出现"已是开发者"。
2. `设置 → 系统 → 开发者选项`，打开 **USB 调试**（索尼可能写作"网络调试 / ADB over network"）。
3. 本机执行 `tv.sh <电视IP> connect`，电视会弹"允许 USB 调试吗"，勾**一直允许**。

adb 装不上时不要纠缠包管理器，直接取官方包：

```bash
curl -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
unzip -q platform-tools.zip && ./platform-tools/adb version
```

`adb root` 在量产机上必然失败（`adbd cannot run as root in production builds`），本技能全流程**不需要 root**，不必尝试。

## §2 配对第三方蓝牙遥控器（不需要 MAC，按设备名称匹配）

先看系统里有没有：

```bash
tv.sh <ip> hid     # 或 adb shell dumpsys bluetooth_manager | sed -n '/Bonded devices/,+4p'
```

- **系统设置「添加设备」能列出目标 → 直接配，跳过本节。**
- **列表始终为空、但协议栈其实扫到了 → 该界面按设备类型白名单过滤，用它配不上**，改用自带工具。

**不要向用户索要 MAC 地址，也不要用「设备列表里选一个」的方式确认**。按名称关键字自动匹配即可，默认关键字是 `小米蓝牙遥控器`。

### 2.1 一次把准备做完

```bash
tv.sh <ip> prep
```

它会编译并安装 BTPair（配对/接 HID）与 Button Remap（按键映射）、启用无障碍服务，最后打印就绪提示。也可以手工分步：`scripts/build_apk.sh assets/btpair` → `tv.sh install` → `tv.sh enable`。

### 2.2 准备就绪后再提示用户长按（顺序不能反）

`prep` 成功返回之后，才让用户操作遥控器 —— **在此之前不要提前让用户按**，否则按完还在编译，白按一轮：

> 请同时长按小米蓝牙遥控器的 **主页键 + 菜单键 5 秒**，直到指示灯快闪。
> （不同批次组合键可能不同，以指示灯快闪为准；并先关掉它原来连着的手机，否则它不再广播。）

### 2.3 配对

```bash
tv.sh <ip> pair                                   # 默认关键字：小米蓝牙遥控器
tv.sh <ip> pair "小米蓝牙遥控器|Mi Remote"          # 多个关键字用 | 或 , 分隔
tv.sh <ip> pair 小米蓝牙遥控器 --rescan             # 强制重新扫描配对（忽略已配对捷径）
tv.sh <ip> pair AA:BB:CC:DD:EE:FF                  # 也仍支持直接给 MAC
```

若这台电视上**已经存在名称匹配的配对记录**，工具不会再扫描（已配对的遥控器不广播，扫也扫不到），而是直接走 HID 连接，日志会给出 `RESULT=SUCCESS <MAC> already_bonded`。需要真的重配（例如换了遥控器、配对记录坏了）才加 `--rescan`。

名称匹配是模糊的：去掉空格 / `_` / `-` / 括号后先做包含匹配，再做「按序子序列」匹配，因此 **`小米蓝牙遥控器` 能命中实际广播名 `小米蓝牙语音遥控器`**（中间多插了字也能中），同时不会误配 `Mi Automatic Smart Door Lock` 这类设备。

工具会在 20 秒一轮、最多 8 轮（约 2.5 分钟）内反复扫描，扫到即配对：用户什么时候把遥控器切进配对模式都能被追上，不必卡时机。配对失败（如 `0x05`）会自动把该设备拉黑并继续扫，不会死在一台上。

判断结果：

- `TARGET_MATCH <MAC> <名称>` → `BOND NONE -> BONDING -> BONDED` + `RESULT=SUCCESS` → 继续 §3
- `RESULT=FAILED` 后接 `result: 0x05`（Authentication Failure）且日志有 `pairingVariant 0 passkey:` → 对方要求 6 位密码而设备没有数字键盘，**这类设备配不上**，工具会自动换下一台；超时仍无结果就请用户确认是否真的进了配对模式
- `RESULT=NOT_FOUND` → 8 轮都没扫到该名称，先确认指示灯是否在快闪，再考虑换关键字

## §3 激活 HID 通道（最容易误判为"厂商锁死"的一步）

配对成功不等于能用。检查：

```bash
tv.sh <ip> hid
```

`Profile: HidHostService` 里 `mInputDevices:` 为空 → HID 没建立。**根因不是权限，是 Android 9 把 `BluetoothHidHost.connect()` 和 `setPriority()` 放进了隐藏 API 黑名单**：方法在框架里确实存在，但普通应用 `getMethod()` 直接抛 `NoSuchMethodException`，看起来就像方法被删了。

`BTPair` 已内置解法，配对成功后自动执行，无需额外操作：

```java
// 双重反射，绕过「用反射调用反射」的鸡生蛋限制
Class<?> vm = (Class<?>) Class.class.getDeclaredMethod("forName", String.class)
        .invoke(null, "dalvik.system.VMRuntime");
Object rt = vm.getMethod("getRuntime").invoke(null);
vm.getMethod("setHiddenApiExemptions", String[].class).invoke(rt, (Object) new String[]{"L"});
```

解除后依次调用 `setPriority(dev, 100)` → `connect(dev)`，状态应为 `0 → 1(CONNECTING) → 2(CONNECTED)`。`setPriority(100)` 就是"加白名单"的真实落点，写入 `Settings.Global.bluetooth_input_device_priority_<MAC>`，重启不丢，之后在范围内会自动回连。

三项验收，全过才算通：

```bash
tv.sh <ip> hid                                # mInputDevices: <MAC> : 2
adb shell dumpsys input | grep -A6 遥控器       # 出现设备节点 /dev/input/eventN
adb shell getevent -lt /dev/input/eventN      # 按方向键应出 EV_MSC MSC_SCAN 0007004f / EV_KEY KEY_RIGHT
```

`MSC_SCAN 0007004f` 说明是标准 HID 键盘用法码，链路真的通了；只有连接状态、没有按键报文，才是 report descriptor 解析问题。

遥控器掉线自行解绑（`BOND_STATE_NONE`）时，重跑一次 `tv.sh <ip> pair` 即可（仍按名称自动匹配，不需要 MAC）；只是重新接 HID 可以更快：`am start -n com.atvremote.pair/.MainActivity --ez hid true`（工具会优先挑名称匹配的已配对设备）。

## §4 部署按键重映射服务

**为什么必须用无障碍服务**：`/system/usr/keylayout/` 是只读分区、`/data/system/devices/keylayout/` 不存在，无 root 改不了布局文件；而 `INJECT_EVENTS` 是签名权限，自定义服务无法注入按键。所以映射目标只能是「系统全局动作」或「启动某个 Activity」。

```bash
scripts/build_apk.sh assets/btremap
tv.sh <ip> install assets/btremap/build/app.apk
tv.sh <ip> map '122>g>2'      # 先配一条最简单的
tv.sh <ip> enable             # 写 enabled_accessibility_services + accessibility_enabled
tv.sh <ip> logs               # 确认 service connected
```

映射串写在 `Settings.Secure.btremap_map`，格式 `源键码>动作>参数`，多条用 `;` 分隔，**改完即刻生效，不用重启也不用重装**：

| 动作 | 含义 | 参数 |
|---|---|---|
| `g` | 全局动作 | `1`=返回 `2`=主页 `3`=最近任务 `4`=通知栏 |
| `a` | 启动 Activity | `包名/类名` |
| `k` | 启动 Activity 并附带一个 KeyEvent | `包名/类名:键码` |
| `s` | 系统 ASSIST 意图 | 空 |

## §5 发现键值并映射

1. 让用户按目标键，读日志：`tv.sh <ip> logs`，只看 `BTRemap : key code=NN name=KEYCODE_XXX`。
   注入事件（`input keyevent`）**不会经过无障碍过滤器**（带 `FLAG_INJECTED`），所以必须靠真实按键，不要用注入自测。
2. 按需写入映射。典型两例：

```bash
# 文本编辑键 MOVE_HOME(122) 在电视上无意义 → 改成系统主页
tv.sh <ip> map '122>g>2'

# 把某个闲置键 → 索尼「输入选择」浮层
tv.sh <ip> map '122>g>2;68>k>com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178'
```

3. 验证：截图看界面是否出现，再用方向键确认高亮会移动。**不要用 `mCurrentFocus` 判断**——这类浮层不抢焦点，该字段仍显示桌面，会误判成失败。手动调试单条映射可绕过按键：`tv.sh <ip> dbg k "<组件>:178"`。

### 关键细节：带 extra 的 Activity

索尼的 `InputSelectionActivity` **不带 `KEY_EVENT` extra 会立即自我关闭**（启动后 1 秒内消失）。正常链路是系统收到 `KEYCODE_TV_INPUT(178)` 后发受保护广播 `android.intent.action.GLOBAL_BUTTON` 给 `.inputselect.InputKeyReceiver`，再由它带着 extra 启动。`am start` 传不了 Parcelable，所以必须由 App 构造 Intent，且 flag 要与索尼一致：无 action + `NEW_TASK|CLEAR_WHEN_TASK_RESET|NO_ANIMATION`。这就是映射格式里要带 `:178` 的原因。

### 换其他品牌电视时

先找"输入选择"这类系统页面的真实组件，别凭类名猜（`ExternalInputsActivity` 之类猜出来的名字通常不存在）：

```bash
adb shell cmd package query-activities -a android.settings.TV_INPUT_SETTINGS
adb shell dumpsys package <包名> | grep -oE '<包名>/[a-zA-Z0-9_.]+' | sort -u
```

再按上法试 `a` / `k` 两种动作；只有在"带 extra 才存活"的情况下才需要 `k`。

## §6 边界与未验证项

- **语音键：未验证，不要直接判定"不可能"**。已知事实是电视侧没有拾音入口——`dumpsys media.audio_policy` 无任何输入设备、`pm list features` 只有 `android.hardware.audio.output`；`VoiceSearchActivity` 未导出，经 `AssistProxyActivity` 转发只会弹「请通过 Bluetooth 连接遥控器以激活语音按键」并立即退出。这说明**唤起这一段是能走的，卡在拾音**，而不是按键不通。处理原则：默认跳过；用户明确要求时照 TV 键的路径试——`getevent -lt` 抓键码（实测语音键为 `KEYCODE_F5`/135，属无人接的空键）→ 写映射 `135>s>`（`ACTION_ASSIST`）→ 真按遥控器验证（`input keyevent` 不过过滤器，测不出来）。若界面起来但无拾音，改走手机端 Google TV App（WiFi 通道）。不要为语音键改 `/system`、改 `audio_policy` 或装第三方语音助手。
- **无障碍服务不能注入按键**（`INJECT_EVENTS` 为签名权限），因此无法"拦截 A 键再发出 B 键"做纯键位映射，只能映射到全局动作或 Activity。
- **无障碍服务不能注入按键**（`INJECT_EVENTS` 为签名权限），因此无法"拦截 A 键再发出 B 键"做纯键位映射，只能映射到全局动作或 Activity。

## 交付与维护

- 会留在电视上的东西：`com.atvremote.pair`（配对/接 HID）、`com.atvremote.remap`（按键映射），以及 `Settings.Secure.btremap_map`、`Settings.Global.bluetooth_input_device_priority_<MAC>` 两条设置。
- 遥控器名称关键字默认写在 `tv.sh` 的 `REMOTE_NAME`（默认 `小米蓝牙遥控器`），临时换：`REMOTE_NAME="Mi Remote|遥控器" tv.sh <ip> pair`；也可以在 App 层直接指定：`am start -n com.atvremote.pair/.MainActivity --ez scan true --ez autobond true -e name "小米蓝牙遥控器"`。
- 包名可自定义：改 `AndroidManifest.xml` 的 `package` 与源码目录后重新编译，运行时用 `PKG_REMAP=<新包名> tv.sh ...` 覆盖脚本里的默认值。同一台电视上换过包名要先把旧包卸载，并重新执行 `tv.sh enable`（无障碍服务绑定的是包名）。
- 撤销调试口子：电视 `设置 → 开发者选项 → 撤销 USB 调试授权`。
- 停用映射：`tv.sh <ip> disable`；卸载：`adb uninstall com.atvremote.remap`。
- 收尾时检查是否装了用户根本不需要的东西（例如纯音频管理类 App），装了要主动问是否清理。

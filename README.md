# 小米蓝牙遥控器 × Android TV

> 让小米蓝牙遥控器（2 / 2 Pro）在任何 Android TV 上全键可用。索尼 BRAVIA 实测通过，不用 root。
>
> Pair a Xiaomi Bluetooth remote with any Android TV — every key working, no root required.

CleanYourTV 把 Android TV 的皮换成了苹果风味，但手感换不了。Apple TV 那只遥控器单买两百多，而 Android TV 阵营里最接近它的硬件是小米蓝牙遥控器 2 / 2 Pro——99 元（咸鱼 6 折全新能到 60 上下）。

它俩官方是不通的：小米的设备只保证小米电视能用，索尼的蓝牙又只认原厂配件。这个仓库就是拆那堵墙的。

<table>
<tr>
<td align="center"><img src="docs/images/before-sony-remote.jpg" width="280"><br><sub><b>Before</b> · 索尼原装</sub></td>
<td align="center"><img src="docs/images/after-xiaomi-remote-2pro.jpg" width="280"><br><sub><b>After</b> · 小米蓝牙遥控器 2 Pro</sub></td>
</tr>
</table>

## 效果

| 键 | 状态 | 说明 |
|---|---|---|
| 方向 / 确认 / 返回 | ✅ 原生 | 标准 HID 键盘键位，配对后直接可用 |
| 音量 / 开关 | ✅ 原生 | 同上 |
| 主页 | ⚠️ 需修 | 遥控器发的是 `KEYCODE_MOVE_HOME(122)`——文本框"移到行首"的键，电视上没有文本框，所以按了毫无反应 |
| TV | ⚠️ 需修 | 发 `KEYCODE_GRAVE(68)`，也是个没人接的空键；本项目把它改成「切换输入」 |
| 菜单 | ✅ | 视固件而定，通常原生可用 |
| 语音 | ❓ 未验证 | 按键能抓到（`KEYCODE_F5(135)`），但电视本机没有麦克风，拾音一段没跑通，详见下文 |

修好之后的状态：**方向、确认、返回、主页、音量、菜单、开关、TV（改成切换输入）统统正常，重启自动重连。**

## 三个坑（也是这个项目的全部内容）

| 你以为的问题 | 真实情况 |
|---|---|
| 小米遥控器只认小米电视 | 它走的是标准 BLE HID（HOGP），任何蓝牙 HID 主机都能收。真正绑死小米生态的只有语音键和几个专属键 |
| 索尼锁了蓝牙白名单，扫不到设备 | 锁住的不是协议栈，是**系统设置里那个「添加设备」界面**——它按设备类型过滤，第三方遥控器不显示。协议栈全程在正常扫描 |
| 配对成功却完全没反应 | Android 9 把 `BluetoothHidHost.connect()` / `setPriority()` 放进了隐藏 API 黑名单，普通反射抛 `NoSuchMethodException`，看起来像"被索尼删了"。用 `VMRuntime.setHiddenApiExemptions("L")` 解除限制后立刻连通 |

第一堵墙长这样——界面在转圈，日志里却一直有设备：

![索尼添加配件界面](docs/images/sony-add-accessory.png)

## 快速开始

### 前置：在电视上开 ADB（唯一必须你亲手做的）

AI 替不了这一步，因为此时它还没连上你的电视。

**索尼 BRAVIA（Android 9 / Google TV）**

1. `设置 → 系统 → 关于`
2. 光标停在「版本」上连按 OK **7 次**（部分新固件要求在「Android TV OS build」上按 10 次），出现「您已是开发者」
3. 回 `设置 → 系统 → 开发者选项`，打开 **USB 调试**；有「网络调试 / ADB over network」也一并打开
4. 电视保持开机，别待机（部分固件从待机唤醒会关掉调试开关）

然后拿到电视的局域网 IP，电脑和电视在同一个网段。

### 路线 A：交给 AI（推荐）

这个仓库同时就是一个 agent 技能：`SKILL.md` 是给它读的执行流程，`assets/` 是它要编译的代码，`scripts/tv.sh` 是它要调的命令，`references/pitfalls.md` 是它别重走的坑。clone 下来放进技能目录即可：

```bash
git clone https://github.com/YOUR_GITHUB/openappletvremot ~/.workbuddy/skills/openappletvremot
```

（Claude Code 用户放到 `~/.claude/skills/openappletvremot`；其他 agent 只要能让它读本地目录，指向仓库根即可）

然后对它说：

> 帮我用 openappletvremot 技能，把这台电视（192.168.x.x）接上小米蓝牙遥控器 2 Pro，Home 和 TV 键也要修好。技能和代码参考 https://github.com/YOUR_GITHUB/openappletvremot

它会依次做完：连 ADB → 编译并安装两个小工具 → 按设备名关键字扫描配对 → 解除隐藏 API 限制接上 HID → 写按键映射 → 让你按键验收。你只在三个地方动手：电视上点「允许」、遥控器同时长按「主页 + 菜单」5 秒、最后按键验收。

技能内部的三层引用关系（AI 会自己顺着走）：

```
提示词「用 openappletvremot 技能」
   └── SKILL.md          执行流程 §1~§6，每节都点到具体脚本或代码位置
        ├── scripts/tv.sh         connect / prep / pair / hid / keys / map / enable
        ├── assets/btpair/        配对 + 解除隐藏 API 限制 + connect HID（Java 源码）
        ├── assets/btremap/       无障碍按键重映射（Java 源码）
        ├── references/           索尼实测数据、13 条走不通的路
        └── docs/                 给人看的故事版与技术版
```

### 路线 B：手动

```bash
# 0. 依赖：adb + JDK + Android SDK build-tools / platform-28。
#    没有也行——build_apk.sh 会自己下到 ~/.androidtv/sdk（约 150MB，只需一次）

# 1. 连电视（电视上会弹授权，点允许）
./scripts/tv.sh <电视IP> connect
./scripts/tv.sh <电视IP> info

# 2. 编译并安装两个工具，同时启用重映射服务
./scripts/tv.sh <电视IP> prep

# 3. 让遥控器进配对模式：同时长按【主页 + 菜单】5 秒到指示灯快闪，
#    然后按名称关键字扫描配对（不需要 MAC 地址，8 轮 × 20 秒循环扫描）
./scripts/tv.sh <电视IP> pair

# 4. 验证 HID 通了：应看到 mInputDevices 里该设备为 2、dumpsys input 里多出 eventN
./scripts/tv.sh <电视IP> hid

# 5. 写按键映射并启用（Home → 主页，TV → 索尼输入选择）
./scripts/tv.sh <电视IP> map '122>g>2;68>k>com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178'
./scripts/tv.sh <电视IP> enable
```

只想重编译 APK，不动电视：

```bash
./scripts/build_apk.sh assets/btpair  release/btpair.apk
./scripts/build_apk.sh assets/btremap release/btremap.apk
```

映射串格式：`源键码>动作>参数`，多条用 `;` 分隔。动作 `g`=系统动作（1 返回 / 2 主页 / 3 最近 / 4 通知），`a`=启动界面，`k`=启动界面并附带按键，`s`=语音助手意图。

换其他品牌电视时，把 `k` 动作的 Activity 换成该品牌的输入选择界面即可，详见 `references/sony-bravia.md`。

## 它是怎么工作的

两个加起来不到 20KB 的 APK，都不需要 root：

- **BTPair**（`assets/btpair`）：反射调 `BluetoothAdapter.startDiscovery` + `createBond` 完成配对，再反射 `BluetoothHidHost.setPriority(100)` / `connect()` 把遥控器登记为标准输入设备。核心是那行隐藏 API 豁免——没有它，`connect()` 永远抛 `NoSuchMethodException`。
- **BTRemap**（`assets/btremap`）：一个裁剪到最小的 `AccessibilityService`，声明 `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS`，只做两件事——执行系统全局动作（`GLOBAL_ACTION_HOME` 等），或启动某个 Activity（可附带 `KEY_EVENT` extra）。映射配置存在 `Settings.Secure.btremap_map`，改完即时生效，不用重启。

为什么不做"拦截 A 键发出 B 键"：`INJECT_EVENTS` 是签名权限，第三方应用拿不到。所以重映射的上限就是"全局动作 + 启动界面"，Home 和 TV 键正好落在能力范围内。

## 关于语音键（未验证，不是不能）

已知事实：这台电视本机**没有麦克风**——`/proc/asound/cards` 为空、`audio_policy` 里输入设备为空、`pm list features` 只有 `android.hardware.audio.output`。索尼的语音链路是"遥控器麦采集 → 蓝牙回传 → 电视识别"，所以只把语音键映射成"唤起语音"，界面可能起来但电视没有拾音入口。

按键本身是通的（抓到 `KEYCODE_F5(135)`），处理方式和 TV 键完全一致，`135>s>` 即可映射到 `ACTION_ASSIST`。真正卡住的是音频。有兴趣可以接着做，做通了欢迎 PR。

## 回滚

```bash
# 立刻恢复原始键位
adb shell settings put secure enabled_accessibility_services ''

# 卸载（配对关系保留，遥控器照用，只是键位回原样）
adb uninstall com.atvremote.remap
adb uninstall com.atvremote.pair
```

最后电视上 `设置 → 开发者选项 → 撤销 USB 调试授权`。**ADB 的 5555 是明文端口，做完就关。**

## 目录结构

```
SKILL.md                    agent 技能主文件（给 AI 读的执行流程）
scripts/tv.sh               一条命令串起 connect/pair/keys/map/enable 等子命令
scripts/build_apk.sh        无 Gradle 构建：aapt2 → javac → d8 → zipalign → apksigner
assets/btpair/              配对 + HID 激活（源码）
assets/btremap/             无障碍按键重映射（源码）
release/                    预编译 APK（debug 签名，仅自用）
references/sony-bravia.md   索尼型号的菜单位置、输入选择 Activity、遥测命令
references/pitfalls.md      13 条实测走不通的路，省你再试一遍
docs/让小米强兼索尼.md        故事版：为什么做、怎么跑通的
docs/完整指南.md             技术版：逐步操作 + 提示词全文
```

## 兼容性

- Android 8.0+（隐藏 API 黑名单机制自 API 28 起，9.0 实测通过），Android TV / Google TV 均可
- 遥控器：小米蓝牙遥控器 2 Pro（实测）、小米蓝牙遥控器 2（同架构，理论可用，未实测）
- 电视：索尼 BRAVIA 2018–2020 系列实测；其他品牌原理相同，只需替换输入选择的 Activity
- 电脑：macOS 实测，Linux/Windows 只需自备 adb 与 build-tools

## 帮助其他品牌电视用上小米蓝牙遥控器

这个方案目前只在索尼 BRAVIA 上跑通。其他品牌（TCL、海信、创维、小米电视、Shield、Chromecast with Google TV…）原理完全一致，差别只有两处：**输入选择界面的 Activity 名**和**个别键码**。

如果你手上有别的电视，欢迎一起来把兼容方案补齐——测通了直接提 PR，说明机型和系统版本，把 `references/` 里补一份该品牌的实测数据（菜单位置、输入选择 Activity、键码表）就行，代码大概率不用改。

## 免责

改的是你自己电视的蓝牙配对和系统设置，风险自负：可能导致保修争议、配对记录混乱、系统更新后需要重做。别在生产/公共设备上试。代码 MIT，随便改，改坏了不要找我。

## License

MIT

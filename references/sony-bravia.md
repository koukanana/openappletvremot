# 索尼 BRAVIA（Android 9 / SDK 28）实测数据

在 Sony BRAVIA、Android 9 上逐条验证过的结果。换机型时数值会变，方法不变。下文的 MAC 一律用占位符代替。

## 蓝牙栈能力（说明"没被锁死"）

```
A2DP Source State: Enabled                  # 蓝牙音频输出是开的
MaxConnectedAudioDevices: 2
Profile: A2dpSourceService / AvrcpTargetService / HidHostService / B3dsService
```

`B3dsService` 是索尼自有配件服务，挂在蓝牙栈里，但 HID 通道的准入并不由它拦截——`setPriority` + `connect` 一旦调用成功它就放行，全程零 `Permission Denial`。

BLE 遥控器掉线的典型原因不是被拒，而是**连不上 HID 就超时自行解绑**：日志表现为 `BOND_STATE_NONE` + `bta_dm_authentication_complete_cback ... result: 0x05`。

## 键码（小米蓝牙语音遥控器，HOGP）

| 按键 | keyCode | 常量 | 备注 |
|---|---|---|---|
| Home | 122 | `KEYCODE_MOVE_HOME` | 文本编辑用键，电视无响应，**必须重映射** |
| TV | 68 | `KEYCODE_GRAVE` | 无默认行为，可自由使用 |
| 语音 | 135 | `KEYCODE_F5` | 无默认行为；语音链路另有限制，见 SKILL.md §6 |
| 返回 | 4 | `KEYCODE_BACK` | 正常 |
| 菜单 | 82 | `KEYCODE_MENU` | 正常 |
| 确认 | 66 | `KEYCODE_ENTER` | 正常 |
| 方向 | 19/20/21/22 | `DPAD_*` | 正常 |

设备节点信息：`/dev/input/event4`，`vendor=0x2717`（Xiaomi），`Sources=0x101`（键盘 + D-pad）。真实报文形如 `EV_MSC MSC_SCAN 0007004f` / `EV_KEY KEY_RIGHT DOWN`（`0x7004f` 是 HID 键盘页右方向键用法码）。

## 配对相关的名称与按键

- 蓝牙广播名：**`小米蓝牙语音遥控器`**（不是 `小米蓝牙遥控器 2 Pro`，机型名与广播名不一致，所以匹配要用关键字而不是全名）。
- 配对组合键：**主页 + 菜单 同按约 5 秒**至指示灯快闪（若无效再试 主页 + 返回、语音 + 返回）。
- 该设备是 BLE（HOGP）设备，`Bonded devices` 里会显示 `[LE]` 前缀。

## 系统组件

| 用途 | 组件 | 能否直接 start |
|---|---|---|
| 添加配件（蓝牙搜索） | `com.android.tv.settings/com.sony.dtv.settings.accessories.AddAccessoryActivity` | ✅ 可拉起，但**按设备类型过滤**，BLE 遥控器不显示 |
| 输入选择浮层 | `com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity` | ⚠️ 必须带 `KEY_EVENT` extra（178），否则秒退 |
| 遥控器/语音设置提示 | `com.sony.dtv.searchapp/com.baidu.duer.modules.assistant.AssistantActivity`（小度） | 仅提示 |
| 语音搜索 | `com.sony.dtv.searchapp/com.sony.dtv.search.app.VoiceSearchActivity` | ❌ 未导出 |
| 语音代理 | `com.sony.dtv.searchapp/com.sony.dtv.assist.AssistProxyActivity` | 弹提示后自毁 |
| 文字搜索 | `com.sony.dtv.searchapp/com.sony.dtv.search.app.TextSearchActivity` | ✅ |

- 标准 `android.settings.BLUETOOTH_SETTINGS` 在这台机器上**没有 Activity 响应**（`No activities found`），通用教程里的蓝牙设置入口不适用。
- 输入选择的正常链路：`KEYCODE_TV_INPUT(178)` → 系统发 `android.intent.action.GLOBAL_BUTTON`（受保护广播）→ `com.sony.dtv.tvx/.inputselect.InputKeyReceiver` 从 `android.intent.extra.KEY_EVENT` 取键事件 → 带 extra 启动 `InputSelectionActivity`。
- `assist` 设置指向 `com.sony.dtv.searchapp/...`（百度小度），但只有原装语音遥控器能真正进入。

## 音频与麦克风

```
/proc/asound/cards           → 空（无录音声卡）
dumpsys media.audio_policy   → Available input devices: 空
pm list features             → 只有 android.hardware.audio.output
```

结论：电视本体没有麦克风，语音输入只能来自原装遥控器经蓝牙回传。

## 不可写 / 不可用的系统路径

```
/system/usr/keylayout/Generic.kl        → 存在但分区只读
/data/system/devices/keylayout/         → 不存在
/data/misc/bluedroid/bt_config.conf     → Permission denied（属 bluetooth 用户）
adb root                                → adbd cannot run as root in production builds
dev.uhid                                → /dev/uhid 可写（shell 属 uhid 组），但仅能造虚拟 HID，不能替代配对
```

本机 `Generic.kl` 里 `key 172 → HOME`、`158 → BACK`、`127/139 → MENU` 都是正常映射，说明系统键盘布局没被改坏，问题只在遥控器发来的码不同。

## 可用的其他入口

```bash
# 输入选择（手动，等价于遥控器 TV/输入键）
adb shell input keyevent 178

# 声音设置
adb shell am start -n com.android.tv.settings/com.sony.dtv.settings.device.sound.SoundActivity

# 已配对设备
adb shell dumpsys bluetooth_manager | sed -n '/Bonded devices/,+4p'
```

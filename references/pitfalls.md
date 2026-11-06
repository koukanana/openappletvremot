# 已验证走不通的路（避免重复踩坑）

每条都实测过，附拒绝方式。遇到同类现象时直接跳过，不要"再试一次"。

## adb 与 root

| 尝试 | 结果 |
|---|---|
| `adb root` | `adbd cannot run as root in production builds`（量产机恒失败） |
| `adb shell cmd bluetooth_manager` | `No shell command implementation.`（Android 9 没有蓝牙 shell 服务） |
| `ls /system/bin /system/xbin \| grep -E '^(bt\|hci\|bluetooth)'` | 空——没有 `btmgmt` / `hcitool` / `bluetoothctl` |

纯 adb 命令**无法**发起蓝牙配对：配对只能走系统蓝牙框架的 `createBond()`，`shell` 用户没有该入口。必须落到应用层（BTPair）。

## 配对与 HID

| 尝试 | 结果 / 原因 |
|---|---|
| 用系统「添加配件」页配对 BLE 遥控器 | 协议栈扫到了（日志有 `btif_get_device_type ... type 1`），但界面列表始终为空：该 UI 按设备类型白名单过滤，且 `discarding advertising packet` 只保留它认识的配件 |
| 装第三方「Bluetooth Controller」类 App 来配对 | 这类 App 只调 `getBondedDevices()` 读系统已配对列表 + A2DP，**没有** `startDiscovery()` / `createBond()`，本身不配对 |
| 普通反射 `BluetoothHidHost.connect()` | 抛 `NoSuchMethodException`。**方法并不缺失**，是隐藏 API 黑名单导致 `getMethod()` 看不到；`getConnectionState` / `getConnectedDevices` 却能调到，容易误判为"被厂商删了" |
| 从 `Bluetooth.apk` 里找 `bluetooth_input_device_priority_` 字符串 | 找不到该字符串，但设置键确实生效——`setPriority()` 写的就是 `Settings.Global.bluetooth_input_device_priority_<MAC>`，不要因反编译不到就否定它 |
| 给 `settings` 里的 priority 键提前写值 | 无效。必须由框架的 `setPriority()` 写入才会被 HID 服务读取 |
| 先扫一遍、把 MAC 列给用户确认再配对 | 多余环节。设备广播名稳定（小米这只叫「小米蓝牙语音遥控器」），直接用名称关键字模糊匹配即可；让用户念 MAC 或从列表里挑一台，既慢又容易配错到门锁之类的设备 |
| 只扫 20~30 秒就判定"扫不到" | 用户切配对模式常常比扫描慢半拍，且设备配不上会自动解绑。BTPair 已改成 8 轮 × 20 秒循环扫描，扫到即停，不要只跑一轮就下结论 |

## 按键映射

| 尝试 | 结果 / 原因 |
|---|---|
| 改 `/system/usr/keylayout/*.kl` | 只读分区，无 root 不可改；`/data/system/devices/keylayout/` 也不存在 |
| 无障碍服务里用 `Instrumentation.sendKeySync` / 注入按键 | `INJECT_EVENTS` 是签名权限，普通应用拿不到 |
| 用 `input keyevent` 自测映射是否生效 | 注入事件带 `FLAG_INJECTED`，**不会经过无障碍按键过滤器**，永远测不出来；必须真按遥控器 |
| 用 `pm grant` 给 BTPair 位置权限后再扫描 | 这一步本身没问题（Android 9 扫描需要 `ACCESS_COARSE_LOCATION`），但扫不到设备时不要归因于权限——先确认目标是否在广播 |

## 索尼内部入口

| 尝试 | 结果 |
|---|---|
| `am start` 直接起 `InputSelectionActivity`（无 extra） | 界面一闪即退，1 秒内自我销毁 |
| `am broadcast -a android.intent.action.GLOBAL_BUTTON` | 受保护广播，连 shell 都发不出 |
| 猜 `com.sony.dtv.settings.externalinputs.ExternalInputsActivity` | 类不存在，`am start` 无任何反应（网上搜到的名字多为其他机型） |
| `com.sony.dtv.braviasyncmenu/.activity.BraviaSyncMenuActivity` | 能打开，但那是 CEC 说明页，不是信号源列表 |
| 用 `mCurrentFocus` 判断浮层是否弹出 | 误导。这类侧边浮层不抢焦点，字段仍显示 launcher；正确判断是截图 + 方向键看高亮是否移动 |
| `VoiceSearchActivity`（未导出）经 `AssistProxyActivity` 转发 | 只弹"请转至设置>遥控器与配件>通过 Bluetooth 连接遥控器以激活语音按键"，随即退出 |
| 附带 231/219/84 等语音键码去"骗过"门禁 | 无效，门禁依据是设备是否有语音遥控器，不是键码 |

## 其他环境问题

| 尝试 | 结果 |
|---|---|
| `brew install android-platform-tools` | 可能装不上（网络/证书），别纠缠，直接下 Google 官方 zip 解压 |
| apkpure `d.apkpure.com` 下载 APK | 403 |
| `apkcombo.com` / `apk.support` / `apkpure.net` | 该线路不可达 |
| 反编译索尼 `framework.jar` / `classes.dex` | `dexdump` 解析失败（Sony 做过裁剪），改用 `app_process` 在机上反射 dump 方法表 |
| `app_process` + `ActivityThread.systemMain()` + `bindService` 取 HID 代理 | Context 能拿到，但 AMS 拒绝：`Unable to find app for caller ... when binding service`——非 zygote 托管进程没有 ApplicationRecord。这条路只能用于 dump 方法表，不能用于实际调用 |
| `monkey -p <pkg> -c android.intent.category.LEANBACK_LAUNCHER 1` | 可用，但对无 launcher intent 的系统 Activity 无效，认准 `am start -n` |

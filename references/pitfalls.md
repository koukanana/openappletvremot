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

## 电视上做 UI 的坑（btpair 2.0 踩过的）

| 尝试 | 结果 |
|---|---|
| 用 `ScrollView` 当内容容器 | 它自己可获焦，方向键会停在空白滚动区上，焦点死活进不了按钮。必须 `setFocusable(false)` + `setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS)`（日志区）/`FOCUS_AFTER_DESCENDANTS`（列表区） |
| 扫描到新设备就整表 `removeAllViews()` 重建 | 焦点随被移除的 View 一起消失，系统重新挑一个 focusable（通常是底部按钮），手感直接崩。正确做法：顺序没变只刷文字，顺序变了重建并记住 `focusedMac` 事后 `requestFocus()` 还原 |
| 彩色按钮用同色描边做焦点态 | 蓝底蓝框等于没有焦点。焦点描边要用高对比色（白），填充同时提亮 |
| 依赖初始焦点自然落位 | 列表是异步渲染的，布局完成时列表还空着，初始焦点会跑到页脚按钮。首次拿到数据后主动 `requestFocus()` 第一行 |
| 方向键从列表往右 | 默认几何就近会落到按钮网格中间（比如「重连」）。给行设 `setNextFocusRightId()`：已配对指向「连接 HID」，未配对指向「配对」；目标按钮被禁用时把路由改到另一个 |

## 重连 / 解绑 / 配对失败的坑（btpair 2.2 踩过的）

| 现象 | 真实原因 | 处置 |
|---|---|---|
| 界面显示「已连接 · 可作输入设备」，但按键完全没反应 | HID profile 的 `state=2` 只代表 L2CAP 通道开了，不代表内核注册了输入节点 | 必须用 `InputDevice.getDeviceIds()` 按设备名核对；找不到就是「通道假连」，不能只看 profile state |
| 上述假连时 `getevent` 只有电视本机 3 个节点 | `bta_hh_co_send_hid_info: dscp_len = 66`（完整应 86）、`version=0x0011`（应 0x0111）、`bta_hh_co_open: attr_mask = 0x0000`。report map 被截断错位，`uhid` 拒绝创建设备 | 断开 HID，等通道真正回落到 0 再 connect；重启蓝牙进程/电视都无效，因为坏数据在 `bta_gattc` 缓存和 half-dead 的 uhid 句柄里 |
| 解绑后重连一直是 66 字节 | 解绑直接调 `removeBond()`，bt stack 走 `btif_hh_virtual_unplug` 报 `status=5`（device not opened），旧 uhid 句柄没销毁，重连时 `fd` 被复用 | 解绑前先 `HID disconnect()`，等 1.6 秒再 `removeBond()` |
| `createBond()` 等 30 秒必失败 | 日志 `btif_get_device_type: type 2`（DUAL）。纯 BLE 遥控器被当成双模，栈先试 BR/EDR，最后 `L2CA_RemoveFixedChnl CID:0x0006 not connected` / `btm_proc_smp_cback: p_dev_rec is NULL`，SMP 通道从未建立 | 反射调 `BluetoothDevice.setType(DEVICE_TYPE_LE)` + `createBond(int transport)` 传 `TRANSPORT_LE`，配对 1 秒内完成 |
| 配对失败后广播里还能看到遥控器 | 能看到广播 ≠ 处于配对模式。普通广播不会接受配对，`createBond` 必然超时 | 提示用户长按组合键至指示灯快闪，再发起配对 |
| `adb shell input tap` 点在按钮上没反应 | 列表在扫描中不断 `markListDirty` 重建，点击被吞；且列表行会滚动，固定坐标点到了别的设备 | 先「停止」扫描再点；或用 `am start --es mac <MAC> --ez autobond true` 走 Intent 直达，不依赖坐标 |

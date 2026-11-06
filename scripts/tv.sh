#!/usr/bin/env bash
# Android TV 操作封装。所有子命令第一个参数都是电视 IP（可省略，默认取 $TV_IP）。
#
#   tv.sh <ip> connect            连接并等待授权（电视上要开 USB/网络调试）
#   tv.sh <ip> info               机型 / 系统版本 / 是否 root
#   tv.sh <ip> install <apk>      安装
#   tv.sh <ip> prep               一次性准备：编译并安装两个 App + 启用重映射服务
#   tv.sh <ip> keys [秒]          监听真实按键（默认 30s）
#   tv.sh <ip> logs               看重映射服务的按键日志
#   tv.sh <ip> scan [秒] [关键字]  扫蓝牙设备名（不配对）
#   tv.sh <ip> pair [关键字|MAC] [--rescan]  配对：默认按名称关键字「小米蓝牙遥控器」自动匹配
#                                  （已配对设备会直接接 HID；--rescan 强制重新扫描）
#   tv.sh <ip> hid                查看 HID 连接状态与输入设备
#   tv.sh <ip> map "<映射串>"      写按键映射（见 SKILL.md 格式）
#   tv.sh <ip> mapshow            打印当前映射
#   tv.sh <ip> enable / disable   启用 / 停用重映射服务
#   tv.sh <ip> dbg <k|a|s> <arg>  通过 DebugReceiver 手动触发一次映射
set -uo pipefail

ADB="${ADB:-$(command -v adb || echo "$HOME/.androidtv/platform-tools/adb")}"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILL_DIR="$(cd "$SELF_DIR/.." && pwd)"
CMD="${1:?用法见脚本头}"; shift
if [[ "$CMD" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  IP="$CMD"; CMD="${1:-}"; shift || true
else
  IP="${TV_IP:?请传电视IP或设置 TV_IP}"
fi
S="$IP:5555"
A=("$ADB" -s "$S")
# 电视上已装旧包名时，用环境变量覆盖：PKG_REMAP=xxx PKG_PAIR=yyy tv.sh ...
PKG_REMAP="${PKG_REMAP:-com.atvremote.remap}"
SVC_REMAP=$PKG_REMAP/$PKG_REMAP.RemapService
PKG_PAIR="${PKG_PAIR:-com.atvremote.pair}"
# 遥控器名称关键字：默认小米，可用 REMOTE_NAME 覆盖，多个用 | 分隔
REMOTE_NAME="${REMOTE_NAME:-小米蓝牙遥控器}"

need_device() {
  "$ADB" connect "$S" >/dev/null 2>&1
  local st; st="$("$ADB" devices | awk -v s="$S" '$1==s{print $2}')"
  [ "$st" = "device" ] || { echo "❌ 未授权/未连接 ($st)。请在电视上打开开发者选项里的 USB 调试，并允许本机调试密钥。"; exit 1; }
}

# 只保留本进程最近一次启动 App 之后产生的日志，避免读到上一轮的旧结果
latest_run() {
  "${A[@]}" logcat -d 2>/dev/null | grep "$PKG_PAIR" \
    | awk '/START mac=/{buf=""} {buf=buf"\n"$0} END{print buf}'
}

case "$CMD" in
  connect)
    "$ADB" connect "$S"
    for i in $(seq 1 20); do
      st="$("$ADB" devices | awk -v s="$S" '$1==s{print $2}')"
      [ "$st" = "device" ] && { echo "✅ 已连接（第 $((i*3)) 秒）"; break; }
      sleep 3
    done
    "$ADB" devices
    ;;
  info)
    need_device
    echo "机型: $("${A[@]}" shell getprop ro.product.manufacturer | tr -d '\r') $("${A[@]}" shell getprop ro.product.model | tr -d '\r')"
    echo "系统: Android $("${A[@]}" shell getprop ro.build.version.release | tr -d '\r') (SDK $("${A[@]}" shell getprop ro.build.version.sdk | tr -d '\r'))"
    echo "root: $("${A[@]}" shell getprop ro.build.type | tr -d '\r') / secure=$("${A[@]}" shell getprop ro.secure | tr -d '\r')"
    ;;
  install)
    need_device; "${A[@]}" install -r "${1:?缺 apk 路径}"
    ;;
  prep)
    need_device
    for p in btpair btremap; do
      echo "== 编译 $p"
      bash "$SELF_DIR/build_apk.sh" "$SKILL_DIR/assets/$p" "$SKILL_DIR/assets/$p/build/app.apk" | tail -3
      echo "== 安装 $p"
      "${A[@]}" install -r "$SKILL_DIR/assets/$p/build/app.apk" | tail -1
    done
    "${A[@]}" shell "settings put secure enabled_accessibility_services $SVC_REMAP"
    "${A[@]}" shell 'settings put secure accessibility_enabled 1'
    sleep 2
    echo "== 无障碍服务"
    "${A[@]}" shell 'dumpsys accessibility' | grep -oE 'Service\[label=[^]]*\]' | head -2
    echo
    echo "✅ 就绪。现在请让遥控器进入配对模式："
    echo "   同时长按【主页键 + 菜单键】5 秒，直到指示灯快闪"
    echo "   然后运行： tv.sh $IP pair"
    ;;
  keys)
    need_device
    echo "== 监听真实按键（注入事件不会经过无障碍过滤器，必须真按）=="
    "${A[@]}" shell "getevent -lt" | grep -E 'EV_KEY|EV_MSC' | head -200 &
    P=$!
    "${A[@]}" logcat -c
    sleep "${1:-30}"
    kill $P 2>/dev/null
    echo "== 无障碍层看到的按键 =="
    "${A[@]}" logcat -d | grep 'BTRemap : key'
    ;;
  logs)
    need_device; "${A[@]}" logcat -d | grep -E 'BTRemap' | tail -40
    ;;
  scan)
    need_device
    NAME="${2:-$REMOTE_NAME}"
    "${A[@]}" shell am force-stop $PKG_PAIR
    "${A[@]}" shell "am start -n $PKG_PAIR/.MainActivity --ez scan true -e name '$NAME'" >/dev/null
    echo "扫描中… 请让设备进入配对模式（关键字：$NAME）"
    sleep "${1:-30}"
    latest_run | grep -E 'FOUND|SCAN_|SCAN_START'
    ;;
  pair)
    need_device
    TARGET="${1:-$REMOTE_NAME}"
    RESCAN=""
    [[ "${2:-}" == "--rescan" ]] && RESCAN="--ez rescan true"
    "${A[@]}" shell am force-stop $PKG_PAIR
    if [[ "$TARGET" =~ ^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$ ]]; then
      echo "按 MAC 配对：$TARGET"
      "${A[@]}" shell "am start -n $PKG_PAIR/.MainActivity -e mac '$TARGET'" >/dev/null
    else
      echo "按名称关键字配对：$TARGET"
      echo "（若尚未进入配对模式，请长按遥控器【主页 + 菜单】5 秒）"
      # 已配对过会直接接 HID（遥控器此时不广播）；加 --rescan 强制重新扫描配对
      "${A[@]}" shell "am start -n $PKG_PAIR/.MainActivity --ez scan true --ez autobond true $RESCAN -e name '$TARGET'" >/dev/null
    fi
    echo "等待配对 + 接 HID（工具会循环扫描 8 轮，最多约 2.5 分钟）…"
    RESULT=""
    for i in $(seq 1 50); do
      sleep 3
      # 只有真正出结果才跳出：失败会自动拉黑重扫，中途的 TARGET_MATCH 不算完成
      RESULT="$(latest_run | grep -E 'RESULT=SUCCESS|RESULT=NOT_FOUND' | tail -1)"
      [ -n "$RESULT" ] && break
    done
    latest_run | grep -E 'SCAN_START|TARGET_MATCH|BOND |RESULT=|HID ' | tail -20
    if echo "$RESULT" | grep -q 'RESULT=SUCCESS'; then
      sleep 4
      echo "== HID 状态 =="
      "${A[@]}" shell 'dumpsys bluetooth_manager' | grep -A3 'Profile: HidHostService'
    else
      echo "⚠️ 未配上。确认遥控器指示灯是否在快闪（长按 主页+菜单 5 秒），再重跑： tv.sh $IP pair"
    fi
    ;;
  hid)
    need_device
    echo "== HidHostService =="
    "${A[@]}" shell 'dumpsys bluetooth_manager' | grep -A3 'Profile: HidHostService'
    echo "== 输入设备 =="
    "${A[@]}" shell 'dumpsys input' | grep -E '^ +[0-9]+: |Name:' | head -12
    ;;
  map)
    need_device
    "${A[@]}" shell "settings put secure btremap_map '${1:?缺映射串}'"
    "${A[@]}" shell 'settings get secure btremap_map'
    ;;
  mapshow)
    need_device; "${A[@]}" shell 'settings get secure btremap_map'
    ;;
  enable)
    need_device
    "${A[@]}" shell "settings put secure enabled_accessibility_services $SVC_REMAP"
    "${A[@]}" shell 'settings put secure accessibility_enabled 1'
    sleep 2
    "${A[@]}" shell 'dumpsys accessibility' | grep -oE 'Service\[label=[^]]*\]' | head -2
    ;;
  disable)
    need_device
    "${A[@]}" shell "settings put secure enabled_accessibility_services ''"
    "${A[@]}" shell 'settings put secure accessibility_enabled 0'
    ;;
  dbg)
    need_device
    "${A[@]}" shell am broadcast -a $PKG_REMAP.DEBUG -n $PKG_REMAP/.DebugReceiver \
      --es act "${1:?k|a|s}" --es arg "${2:-}" | tail -2
    ;;
  *)
    echo "未知子命令: $CMD"; sed -n '2,18p' "$0"; exit 1
    ;;
esac

package com.atvremote.remap;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视按键重映射（无障碍按键过滤）。
 *
 * 配置走 Settings.Secure 的 btremap_map，可用 adb 随时改，无需重装：
 *   btremap_map = "条目;条目;条目"
 *   条目 = 源keyCode > 动作 > 参数
 *
 * 动作：
 *   g  = 全局动作        参数 1=BACK 2=HOME 3=RECENTS 4=NOTIFICATIONS
 *   a  = 启动 Activity   参数 = 组件名 包/类
 *   k  = 启动 Activity 并附带一个 KeyEvent（参数 = 组件名:键码）
 *   s  = 系统 ASSIST 意图（拉起默认语音助手）
 *
 * 例：
 *   122>g>2;68>k>com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178
 *
 * 所有经过的按键都会打到 logcat（tag=BTRemap）。
 */
public class RemapService extends AccessibilityService {

    static final String TAG = "BTRemap";
    static final String KEY_MAP = "btremap_map";

    static class Rule {
        int src;
        char act;
        String arg;
    }

    private final List<Rule> rules = new ArrayList<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        reload();
        Log.i(TAG, "service connected, rules=" + rules.size());
    }

    private void reload() {
        rules.clear();
        String raw = Settings.Secure.getString(getContentResolver(), KEY_MAP);
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        for (String item : raw.split(";")) {
            item = item.trim();
            if (item.isEmpty()) continue;
            String[] p = item.split(">", 3);
            if (p.length < 2) continue;
            try {
                Rule r = new Rule();
                r.src = Integer.parseInt(p[0].trim());
                r.act = p[1].trim().charAt(0);
                r.arg = p.length > 2 ? p[2].trim() : "";
                rules.add(r);
            } catch (Throwable t) {
                Log.w(TAG, "bad rule: " + item);
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        reload();

        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code != KeyEvent.KEYCODE_UNKNOWN) {
            Log.i(TAG, "key code=" + code + " name=" + KeyEvent.keyCodeToString(code)
                    + " action=" + (down ? "DOWN" : "UP")
                    + " device=" + event.getDeviceId()
                    + " repeat=" + event.getRepeatCount());
        }

        Rule hit = null;
        for (Rule r : rules) {
            if (r.src == code) { hit = r; break; }
        }
        if (hit == null) {
            return false;
        }

        if (down) {
            // 吃掉按下，避免系统原行为重复触发
            return true;
        }

        boolean ok;
        switch (hit.act) {
            case 'g':
                ok = performGlobalAction(parseInt(hit.arg, 2));
                Log.i(TAG, "remap " + code + " -> global " + hit.arg + " ok=" + ok);
                break;
            case 's':
                ok = start(new Intent(Intent.ACTION_ASSIST));
                Log.i(TAG, "remap " + code + " -> assist ok=" + ok);
                break;
            case 'a':
                ok = start(component(hit.arg, 0));
                Log.i(TAG, "remap " + code + " -> activity " + hit.arg + " ok=" + ok);
                break;
            case 'k': {
                int idx = hit.arg.lastIndexOf(':');
                String comp = idx > 0 ? hit.arg.substring(0, idx) : hit.arg;
                int key = idx > 0 ? parseInt(hit.arg.substring(idx + 1), KeyEvent.KEYCODE_TV_INPUT) : KeyEvent.KEYCODE_TV_INPUT;
                ok = start(component(comp, key));
                Log.i(TAG, "remap " + code + " -> activity " + comp + " withKey=" + key + " ok=" + ok);
                break;
            }
            default:
                ok = false;
                Log.w(TAG, "unknown action " + hit.act);
        }
        return true;
    }

    /** 构造启动 Intent；keyCode>0 时附带一个 KEY_EVENT，模拟索尼自家按键广播。 */
    private Intent component(String flat, int keyCode) {
        // 与索尼 InputKeyReceiver 完全一致：无 action + NEW_TASK|CLEAR_WHEN_TASK_RESET|NO_ANIMATION
        Intent it = new Intent();
        it.setComponent(ComponentName.unflattenFromString(flat));
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (keyCode > 0) {
            long now = SystemClock.uptimeMillis();
            KeyEvent ke = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
            it.putExtra("android.intent.extra.KEY_EVENT", ke);
        }
        return it;
    }

    private boolean start(Intent it) {
        if ((it.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            startActivity(it);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t);
            return false;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // 不需要事件流
    }

    @Override
    public void onInterrupt() {
    }
}

package com.atvremote.remap;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/**
 * 调试入口：让 adb 能直接触发一次映射动作，不用真的按键。
 *
 *   adb shell am broadcast -a com.atvremote.remap.DEBUG -n com.atvremote.remap/.DebugReceiver \
 *       --es act k --es arg "com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178"
 */
public class DebugReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent in) {
        String act = in.getStringExtra("act");
        String arg = in.getStringExtra("arg");
        Log.i("BTRemap", "DEBUG act=" + act + " arg=" + arg);
        if (act == null) return;

        try {
            if ("s".equals(act)) {
                Intent it = new Intent(Intent.ACTION_ASSIST);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
            } else if ("a".equals(act) || "k".equals(act)) {
                int key = 0;
                String comp = arg;
                if ("k".equals(act)) {
                    int idx = arg.lastIndexOf(':');
                    if (idx > 0) {
                        comp = arg.substring(0, idx);
                        key = Integer.parseInt(arg.substring(idx + 1));
                    }
                }
                Intent it = new Intent();
                it.setComponent(ComponentName.unflattenFromString(comp));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                if (key > 0) {
                    long now = SystemClock.uptimeMillis();
                    it.putExtra("android.intent.extra.KEY_EVENT",
                            new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
                }
                ctx.startActivity(it);
            }
        } catch (Throwable t) {
            Log.w("BTRemap", "DEBUG failed: " + t);
        }
    }
}

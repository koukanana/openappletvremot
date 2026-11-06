package com.atvremote.pair;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * BT pairing + HID-connect helper for Android TV.
 *
 *   scan + auto pair by NAME :  --ez scan true --ez autobond true [-e name 小米蓝牙遥控器]
 *   bond one MAC             :  -e mac AA:BB:CC:DD:EE:FF
 *   hid connect only         :  --ez hid true  [-e name 小米蓝牙遥控器]
 *
 * 名称匹配是模糊的：先去空格/分隔符做包含匹配，再做「按序子序列」匹配，
 * 所以「小米蓝牙遥控器」能命中「小米蓝牙语音遥控器」。多个关键字用 | 或 , 分隔。
 * 扫到即配对，配不上（0x05 等）会拉黑继续扫，直到 MAX_ROUNDS 轮。
 *
 * All state changes are mirrored to logcat tag BTPair.
 */
public class MainActivity extends Activity {

    private static final String TAG = "BTPair";
    private static final long SCAN_MS = 20000L;
    private static final int MAX_ROUNDS = 8;
    private static final int PROFILE_HID_HOST = 4;
    private static final String DEFAULT_NAME = "小米蓝牙遥控器";

    private TextView status;
    private BluetoothAdapter adapter;
    private final Map<String, String> found = new LinkedHashMap<>();
    private final Set<String> failedMacs = new HashSet<>();
    private boolean autoBond;
    private String wantMac;
    private String nameKeyword;
    private int scanRounds;
    private BluetoothProfile hidProxy;
    private int hidAttempts;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.i(TAG, "SCAN_DONE round=" + scanRounds + " found=" + found.size()
                        + " target=" + (wantMac == null ? "none" : wantMac));
                maybeNextRound();
                return;
            }
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null) {
                return;
            }
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                final String addr = dev.getAddress();
                String name = dev.getName();
                int cod = intent.getIntExtra(BluetoothDevice.EXTRA_CLASS, 0);
                String line = addr + "  " + (name == null ? "(no name)" : name) + "  cod=" + cod;
                if (!found.containsKey(addr)) {
                    Log.i(TAG, "FOUND " + line);
                    found.put(addr, line);
                    render("Found " + found.size() + " device(s)");
                }
                if (autoBond && wantMac == null && name != null
                        && !failedMacs.contains(addr) && matchesName(name)) {
                    wantMac = addr;
                    Log.i(TAG, "TARGET_MATCH " + line);
                    bond(dev);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                Log.i(TAG, "BOND " + dev.getAddress() + " " + stateName(prev) + " -> " + stateName(state));
                if (state == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "RESULT=SUCCESS " + dev.getAddress());
                    render("PAIRED OK: " + dev.getAddress() + "\nconnecting HID...");
                    connectHid(dev);
                } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                    Log.i(TAG, "RESULT=FAILED " + dev.getAddress());
                    failedMacs.add(dev.getAddress());
                    if (dev.getAddress().equals(wantMac)) {
                        wantMac = null;
                    }
                    render("PAIRING FAILED: " + dev.getAddress() + "\nrescanning...");
                    if (autoBond) {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startScan();
                            }
                        }, 2500);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("BTPair");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#7FD1AE"));
        status.setTextSize(19);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 28, 0, 0);
        root.addView(status);
        setContentView(root);

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            render("No Bluetooth adapter");
            return;
        }

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(receiver, f);

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }

        Intent it = getIntent();
        String mac = it != null ? it.getStringExtra("mac") : null;
        String name = it != null ? it.getStringExtra("name") : null;
        boolean scan = it != null && it.getBooleanExtra("scan", false);
        boolean hidOnly = it != null && it.getBooleanExtra("hid", false);
        boolean forceScan = it != null && it.getBooleanExtra("rescan", false);
        autoBond = it != null && it.getBooleanExtra("autobond", false);

        if (mac != null && !mac.trim().isEmpty()) {
            wantMac = mac.trim().toUpperCase();
        }
        if (name != null && !name.trim().isEmpty()) {
            nameKeyword = name.trim();
        }
        if (nameKeyword == null && (autoBond || hidOnly)) {
            nameKeyword = DEFAULT_NAME;
        }
        Log.i(TAG, "START mac=" + wantMac + " name=" + nameKeyword
                + " scan=" + scan + " autobond=" + autoBond + " hid=" + hidOnly
                + " rescan=" + forceScan);

        if (hidOnly) {
            BluetoothDevice dev = pickTarget();
            if (dev != null) {
                connectHid(dev);
            } else {
                render("No bonded device matching " + nameKeyword);
            }
            return;
        }

        if (wantMac != null) {
            startBondByMac(wantMac);
            return;
        }

        // 已配对过（遥控器不会再次广播）就直接接 HID，不必重扫；要强制重配用 --ez rescan true
        if (autoBond && !forceScan) {
            BluetoothDevice bonded = pickTarget();
            if (bonded != null && bonded.getBondState() == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "RESULT=SUCCESS " + bonded.getAddress()
                        + " already_bonded name=" + bonded.getName());
                render("Already bonded: " + bonded.getAddress() + "\nconnecting HID...");
                connectHid(bonded);
                return;
            }
        }

        startScan();
    }

    private void startScan() {
        if (!adapter.isEnabled()) {
            adapter.enable();
        }
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        found.clear();
        scanRounds++;
        boolean ok = adapter.startDiscovery();
        Log.i(TAG, "SCAN_START ok=" + ok + " round=" + scanRounds + "/" + MAX_ROUNDS
                + " keyword=" + nameKeyword + " autobond=" + autoBond);
        if (autoBond) {
            render("Scanning " + (SCAN_MS / 1000) + "s (round " + scanRounds + "/" + MAX_ROUNDS + ")\n"
                    + "长按遥控器 主页 + 菜单 5 秒");
        } else {
            render("Scanning " + (SCAN_MS / 1000) + "s ...");
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
                StringBuilder sb = new StringBuilder("SCAN_RESULT count=" + found.size());
                for (String v : found.values()) {
                    sb.append(" | ").append(v);
                }
                Log.i(TAG, sb.toString());
            }
        }, SCAN_MS);
    }

    /** 自动配对模式下持续多轮扫描：用户什么时候进配对模式都能被扫到。 */
    private void maybeNextRound() {
        if (!autoBond || wantMac != null) {
            if (autoBond && wantMac != null) {
                Log.i(TAG, "SCAN_STOP target already chosen " + wantMac);
            }
            return;
        }
        if (scanRounds >= MAX_ROUNDS) {
            Log.i(TAG, "RESULT=NOT_FOUND rounds=" + scanRounds + " keyword=" + nameKeyword);
            render("Not found: " + nameKeyword + "\nretry after remote enters pairing mode");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wantMac == null) {
                    startScan();
                }
            }
        }, 1500);
    }

    private void startBondByMac(String mac) {
        BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "RESULT=INVALID_MAC " + mac);
            render("Invalid MAC: " + mac);
            return;
        }
        if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "already bonded " + mac + ", going straight to HID connect");
            connectHid(dev);
        } else {
            bond(dev);
        }
    }

    private void bond(BluetoothDevice dev) {
        int state = dev.getBondState();
        Log.i(TAG, "bond request " + dev.getAddress() + " state=" + stateName(state));
        if (state == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "RESULT=ALREADY_BONDED " + dev.getAddress());
            connectHid(dev);
            return;
        }
        if (state == BluetoothDevice.BOND_BONDING) {
            render("Already bonding: " + dev.getAddress());
            return;
        }
        boolean started = dev.createBond();
        Log.i(TAG, "createBond() = " + started + " for " + dev.getAddress());
        render((started ? "Bonding " : "createBond rejected ") + dev.getAddress());
        if (!started) {
            failedMacs.add(dev.getAddress());
            wantMac = null;
        }
    }

    /**
     * Android 9 把 connect/setPriority 放进了隐藏 API 黑名单，getMethod 会直接抛
     * NoSuchMethodException。这里通过双重反射调用 VMRuntime.setHiddenApiExemptions("L")
     * 解除整个 L 前缀的限制，之后普通反射即可访问。
     */
    private static void bypassHiddenApi() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            Log.i(TAG, "hiddenApiExemptions 已解除");
        } catch (Throwable t) {
            Log.w(TAG, "hiddenApiExemptions 失败: " + t);
        }
    }

    /** Grab the hidden HID_HOST profile proxy and call connect() by reflection. */
    private void connectHid(final BluetoothDevice dev) {
        hidAttempts = 0;
        bypassHiddenApi();
        try {
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    hidProxy = proxy;
                    Log.i(TAG, "HID proxy connected class=" + proxy.getClass().getName());
                    doHidConnect(dev);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    Log.i(TAG, "HID proxy disconnected");
                    hidProxy = null;
                }
            }, PROFILE_HID_HOST);
        } catch (Throwable t) {
            Log.e(TAG, "getProfileProxy failed: " + t);
            render("HID proxy failed: " + t);
        }
    }

    private void doHidConnect(final BluetoothDevice dev) {
        if (hidProxy == null) {
            return;
        }
        hidAttempts++;
        try {
            Method setPriority = hidProxy.getClass().getMethod("setPriority", BluetoothDevice.class, int.class);
            boolean p = (Boolean) setPriority.invoke(hidProxy, dev, 100);
            boolean p2 = (Boolean) setPriority.invoke(hidProxy, dev, 1000);
            Log.i(TAG, "HID setPriority(100)=" + p + " setPriority(1000)=" + p2);
            Method getPriority = hidProxy.getClass().getMethod("getPriority", BluetoothDevice.class);
            Log.i(TAG, "HID getPriority=" + getPriority.invoke(hidProxy, dev));
        } catch (Throwable t) {
            Log.w(TAG, "HID setPriority failed: " + t);
        }
        try {
            Method connect = hidProxy.getClass().getMethod("connect", BluetoothDevice.class);
            boolean ok = (Boolean) connect.invoke(hidProxy, dev);
            Log.i(TAG, "HID connect() = " + ok + " attempt=" + hidAttempts);
            render("HID connect attempt " + hidAttempts + ": " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "HID connect failed: " + t);
            render("HID connect error: " + t);
        }
        try {
            Method getState = hidProxy.getClass().getMethod("getConnectionState", BluetoothDevice.class);
            int st = (Integer) getState.invoke(hidProxy, dev);
            Log.i(TAG, "HID connectionState = " + st);
        } catch (Throwable t) {
            Log.w(TAG, "HID getConnectionState failed: " + t);
        }
        if (hidAttempts < 5) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    doHidConnect(dev);
                }
            }, 4000);
        } else {
            dumpHidState();
        }
    }

    private void dumpHidState() {
        if (hidProxy == null) {
            return;
        }
        try {
            Method getDevices = hidProxy.getClass().getMethod("getConnectedDevices");
            Object list = getDevices.invoke(hidProxy);
            Log.i(TAG, "HID connectedDevices = " + list);
            render("HID connected devices: " + list);
        } catch (Throwable t) {
            Log.w(TAG, "HID getConnectedDevices failed: " + t);
        }
    }

    /** 已配对设备里按名称挑目标；名称不匹配就返回 null，不做"取第一个"的兜底，避免连错设备。 */
    private BluetoothDevice pickTarget() {
        if (wantMac != null) {
            try {
                return adapter.getRemoteDevice(wantMac);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        Log.i(TAG, "bonded count=" + bonded.size() + " keyword=" + nameKeyword);
        for (BluetoothDevice d : bonded) {
            String n = d.getName();
            Log.i(TAG, "bonded " + d.getAddress() + " " + n);
            if (n != null && matchesName(n)) {
                Log.i(TAG, "pickTarget matched " + d.getAddress() + " " + n);
                return d;
            }
        }
        return null;
    }

    /**
     * 模糊名称匹配：去空格/下划线/连字符/括号后先做包含，再退化为「按序子序列」，
     * 这样「小米蓝牙遥控器」能命中「小米蓝牙语音遥控器」（中间多插了字也能中）。
     */
    private boolean matchesName(String devName) {
        if (nameKeyword == null || devName == null) {
            return false;
        }
        String n = normalize(devName);
        for (String raw : nameKeyword.split("[|,]")) {
            String k = normalize(raw);
            if (k.isEmpty()) {
                continue;
            }
            if (n.contains(k) || subsequence(n, k)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[\\s_\\-()（）\\[\\]]", "");
    }

    private static boolean subsequence(String hay, String needle) {
        int i = 0;
        for (int j = 0; j < hay.length() && i < needle.length(); j++) {
            if (hay.charAt(j) == needle.charAt(i)) {
                i++;
            }
        }
        return i == needle.length();
    }

    private void render(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE:
                return "NONE";
            case BluetoothDevice.BOND_BONDING:
                return "BONDING";
            case BluetoothDevice.BOND_BONDED:
                return "BONDED";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            if (adapter != null && hidProxy != null) {
                adapter.closeProfileProxy(PROFILE_HID_HOST, hidProxy);
            }
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}

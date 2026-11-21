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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用蓝牙遥控器配对 / HID 诊断工具（Android TV）。
 *
 * 图形界面：
 *   左侧  扫描到的设备列表，焦点移动即选中，点击锁定
 *   右侧  选中设备的实时状态（配对 / HID / 输入通道 / 优先级）与操作按钮
 *
 * 命令行入口（保持向后兼容，tv.sh 依赖这些 flag）：
 *   scan + auto pair by NAME :  --ez scan true --ez autobond true [-e name 小米蓝牙遥控器]
 *   bond one MAC             :  -e mac AA:BB:CC:DD:EE:FF
 *   hid connect only         :  --ez hid true  [-e name 小米蓝牙遥控器]
 *   不带任何参数启动则直接进入图形界面，自动扫一轮。
 *
 * 名称匹配是模糊的：去空格/分隔符做包含匹配，再做「按序子序列」匹配，
 * 所以「小米蓝牙遥控器」能命中「小米蓝牙语音遥控器」。多个关键字用 | 或 , 分隔。
 *
 * 所有状态变化仍镜像到 logcat tag BTPair。
 */
public class MainActivity extends Activity {

    private static final String TAG = "BTPair";
    private static final long SCAN_MS = 20000L;
    private static final int MAX_ROUNDS = 8;
    private static final int PROFILE_HID_HOST = 4;
    private static final String DEFAULT_NAME = "小米蓝牙遥控器";

    // 固定控件 ID：用于显式焦点路由（方向键从列表落到指定按钮）
    private static final int ID_BOND = 0x7F0A0001;
    private static final int ID_CONNECT = 0x7F0A0002;
    private static final int ID_RECONNECT = 0x7F0A0003;
    private static final int ID_DISCONNECT = 0x7F0A0004;
    private static final int ID_UNBOND = 0x7F0A0005;

    private static final int C_BG = 0xFF0E1218;
    private static final int C_PANEL = 0xFF10151C;
    private static final int C_HEAD = 0xFF141A22;
    private static final int C_CARD = 0xFF171E27;
    private static final int C_LINE = 0xFF232B36;
    private static final int C_TEXT = 0xFFE8EDF5;
    private static final int C_SUB = 0xFF7E8B9E;
    private static final int C_DIM = 0xFF5C6878;
    private static final int C_ACCENT = 0xFF4C8DFF;
    private static final int C_OK = 0xFF2FBF71;
    private static final int C_WARN = 0xFFE0A030;
    private static final int C_ERR = 0xFFE0574C;

    /** 列表 / 详情共用的一条设备记录。 */
    static class DeviceEntry {
        final String mac;
        String name;
        int rssi = -127;
        int bondState = BluetoothDevice.BOND_NONE;
        int hidState = -1;
        /** HID 通道连上且内核已注册输入节点，按键才算真的能用。 */
        boolean inputOk;
        long seenAt;
        int order;

        DeviceEntry(String mac) {
            this.mac = mac;
        }

        String label() {
            return name == null || name.trim().isEmpty() ? "未命名设备" : name.trim();
        }
    }

    /** 一行的视图引用，便于只更新徽标而不重建列表。 */
    static class RowHolder {
        View root;
        View focusable;
        TextView name;
        TextView mac;
        TextView bondBadge;
        TextView hidBadge;
    }

    private BluetoothAdapter adapter;
    private final Map<String, DeviceEntry> devices = new LinkedHashMap<String, DeviceEntry>();
    private final Map<String, RowHolder> holders = new LinkedHashMap<String, RowHolder>();
    private DeviceEntry selected;

    private LinearLayout rowsBox;
    private TextView listTitle;
    private TextView scanState;
    private TextView panelName;
    private TextView panelMac;
    private TextView vBond;
    private TextView vHid;
    private TextView vPrio;
    private TextView vRssi;
    private TextView logView;
    private TextView mapStatus;
    private Button bScan;
    private Button bStop;
    private Button bBond;
    private Button bConnect;
    private Button bReconnect;
    private Button bDisconnect;
    private Button bUnbond;

    private BluetoothProfile hidProxy;
    private boolean proxyRequested;
    private boolean scanning;
    private boolean listDirty;
    private boolean initialFocusDone;
    private String lastOrderKey = "";
    private int scanRounds;
    private boolean autoBond;
    private String nameKeyword;
    private String wantMac;
    private int seq;
    private long unbondArmedAt;
    /** 程序还原焦点时置位，避免把外部指定/markDirty 之前的选中项覆盖成首行设备。 */
    private boolean restoringFocus;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<String> logLines = new ArrayList<String>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                scanning = true;
                updateScanState();
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                updateScanState();
                Log.i(TAG, "SCAN_DONE round=" + scanRounds + " found=" + devices.size()
                        + " target=" + (wantMac == null ? "none" : wantMac));
                maybeNextRound();
                return;
            }
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null) {
                return;
            }
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                String addr = dev.getAddress();
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -127);
                String name = null;
                try {
                    name = dev.getName();
                } catch (Throwable ignored) {
                }
                DeviceEntry e = upsert(addr);
                if (name != null && !name.trim().isEmpty()) {
                    e.name = name.trim();
                }
                e.rssi = rssi;
                e.seenAt = System.currentTimeMillis();
                if (e.bondState != BluetoothDevice.BOND_BONDED) {
                    try {
                        e.bondState = dev.getBondState();
                    } catch (Throwable ignored) {
                    }
                }
                Log.i(TAG, "FOUND " + addr + "  " + (name == null ? "(no name)" : name) + "  rssi=" + rssi);
                markListDirty();
                if (selected != null && selected.mac.equals(addr)) {
                    refreshPanel();
                }
                if (autoBond && wantMac == null && name != null && matchesName(name)) {
                    wantMac = addr;
                    Log.i(TAG, "TARGET_MATCH " + addr + " " + name);
                    select(addr);
                    bond(dev);
                }
                return;
            }
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                String addr = dev.getAddress();
                DeviceEntry e = upsert(addr);
                e.bondState = state;
                Log.i(TAG, "BOND " + addr + " " + stateName(prev) + " -> " + stateName(state));
                appendLog("配对状态 " + stateName(prev) + " → " + stateName(state));
                markListDirty();
                refreshPanel();
                if (state == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "RESULT=SUCCESS " + addr);
                    // 配对成功后补写 HID 优先级，否则下次休眠唤醒不会自动回连
                    ensureHidProxy();
                    hidSetPriority(dev, 100);
                    connectHid(dev, 3);
                    if (autoBond && wantMac == null) {
                        autoBond = false;
                    }
                } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                    Log.i(TAG, "RESULT=FAILED " + addr);
                    appendLog("配对失败 · 请让遥控器进入配对模式后再试（长按组合键至指示灯快闪）");
                    if (addr.equals(wantMac)) {
                        wantMac = null;
                    }
                    refreshPanel();
                    if (autoBond) {
                        ui.postDelayed(new Runnable() {
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
        bypassHiddenApi();
        buildUi();

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            appendLog("这台设备没有蓝牙适配器");
            updateScanState();
            return;
        }

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(receiver, f);

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }

        ensureHidProxy();
        seedBondedDevices();
        handleIntent(getIntent());
        ui.postDelayed(poller, 1200);
    }

    /** singleTop：重复 am start 只复用同一个界面，重跑扫描而不叠实例、不闪屏。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent it) {
        String mac = it != null ? it.getStringExtra("mac") : null;
        String name = it != null ? it.getStringExtra("name") : null;
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
                + " autobond=" + autoBond + " hid=" + hidOnly + " rescan=" + forceScan);

        if (hidOnly) {
            BluetoothDevice dev = pickByName();
            if (dev != null) {
                select(dev.getAddress());
                ensureHidProxy();
                hidSetPriority(dev, 100);
                connectHid(dev, 5);
            } else {
                appendLog("已配对设备里没有匹配 " + nameKeyword + " 的设备");
            }
            return;
        }

        if (wantMac != null) {
            select(wantMac);
            BluetoothDevice dev = adapter.getRemoteDevice(wantMac);
            if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
                ensureHidProxy();
                hidSetPriority(dev, 100);
                connectHid(dev, 5);
            } else {
                bond(dev);
            }
            return;
        }

        if (autoBond && !forceScan) {
            BluetoothDevice bonded = pickByName();
            if (bonded != null) {
                select(bonded.getAddress());
                Log.i(TAG, "RESULT=SUCCESS " + bonded.getAddress() + " already_bonded");
                appendLog("已配对，直接连 HID：" + bonded.getAddress());
                ensureHidProxy();
                hidSetPriority(bonded, 100);
                connectHid(bonded, 5);
                return;
            }
        }

        startScan();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(C_HEAD);
        head.setPadding(dp(22), dp(12), dp(22), dp(12));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("遥控器配对助手", 19, C_TEXT, true);
        TextView sub = text("扫描 · 配对 · HID 连接 · 按键映射", 11, C_DIM, false);
        titleBox.addView(title);
        titleBox.addView(sub);
        head.addView(titleBox, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scanState = text("待机", 13, C_SUB, false);
        scanState.setPadding(dp(14), dp(6), dp(14), dp(6));
        head.addView(scanState);
        root.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f));

        // 主体
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // 左：设备列表
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(C_BG);
        left.setPadding(dp(18), dp(12), dp(12), dp(12));

        listTitle = text("附近设备", 12, C_SUB, false);
        listTitle.setPadding(dp(4), 0, 0, dp(8));
        left.addView(listTitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        // 容器本身不参与焦点遍历，否则方向键会停在空白的滚动区上
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rowsBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        left.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(0, dp(10), 0, 0);
        bScan = button("开始扫描", C_ACCENT, true);
        bStop = button("停止", 0xFF2A3341, false);
        Button bClear = button("清空", 0xFF2A3341, false);
        bScan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scanRounds = 0;
                autoBond = false;
                startScan();
            }
        });
        bStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopScan();
            }
        });
        bClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                devices.clear();
                holders.clear();
                selected = null;
                renderList();
                refreshPanel();
            }
        });
        tools.addView(bScan, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tools.addView(space(dp(8), 1));
        tools.addView(bStop, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tools.addView(space(dp(8), 1));
        tools.addView(bClear, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tools);
        body.addView(left, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));

        View divider = new View(this);
        divider.setBackgroundColor(C_LINE);
        body.addView(divider, lp(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, 0f));

        // 右：状态面板
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(C_PANEL);
        right.setPadding(dp(18), dp(14), dp(18), dp(14));

        TextView panelTitle = text("设备状态", 11, C_DIM, false);
        right.addView(panelTitle);

        panelName = text("未选择设备", 18, C_TEXT, true);
        panelName.setPadding(0, dp(4), 0, dp(2));
        right.addView(panelName);

        panelMac = text("在上方列表中选择一台设备", 11, C_SUB, false);
        panelMac.setPadding(0, 0, 0, dp(8));
        right.addView(panelMac);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(C_CARD, dp(10), 0, 0));
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        vBond = infoRow(card, "配对");
        vHid = infoRow(card, "HID");
        vPrio = infoRow(card, "优先级");
        vRssi = infoRow(card, "信号");
        right.addView(card);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);
        bBond = button("配对", C_ACCENT, true);
        bBond.setId(ID_BOND);
        bConnect = button("连接 HID", 0xFF2F6B4F, true);
        bConnect.setId(ID_CONNECT);
        row1.addView(bBond, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(space(dp(8), 1));
        row1.addView(bConnect, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        bReconnect = button("重连", 0xFF2A3341, false);
        bReconnect.setId(ID_RECONNECT);
        bDisconnect = button("断开", 0xFF2A3341, false);
        bDisconnect.setId(ID_DISCONNECT);
        bUnbond = button("解除绑定", 0xFF4A2B2B, false);
        bUnbond.setId(ID_UNBOND);
        // 危险动作不放在主操作的正下方：↓ 先落到安全按钮上
        bBond.setNextFocusDownId(ID_RECONNECT);
        bConnect.setNextFocusDownId(ID_DISCONNECT);
        row2.addView(bReconnect, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(space(dp(8), 1));
        row2.addView(bDisconnect, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(space(dp(8), 1));
        row2.addView(bUnbond, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.addView(row2);

        bBond.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                BluetoothDevice d = devOf(selected);
                if (d == null) return;
                if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                    appendLog("已配对，改为重连");
                    doReconnect();
                } else {
                    bond(d);
                }
            }
        });
        bConnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                BluetoothDevice d = devOf(selected);
                if (d == null) return;
                ensureHidProxy();
                hidSetPriority(d, 100);
                connectHid(d, 4);
            }
        });
        bReconnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doReconnect();
            }
        });
        bDisconnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                BluetoothDevice d = devOf(selected);
                if (d == null) return;
                boolean ok = hidDisconnect(d);
                appendLog("断开 HID：" + (ok ? "已发出" : "失败"));
                ui.postDelayed(new Runnable() {
                    public void run() {
                        refreshPanel();
                    }
                }, 1200);
            }
        });
        bUnbond.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                BluetoothDevice d = devOf(selected);
                if (d == null) return;
                long now = System.currentTimeMillis();
                if (now - unbondArmedAt > 4000) {
                    unbondArmedAt = now;
                    bUnbond.setText("确认解除？");
                    appendLog("再按一次确认解除绑定（4 秒内有效）");
                    return;
                }
                unbondArmedAt = 0;
                bUnbond.setText("解除绑定");
                // 直接 removeBond 会让 bt stack 走 virtual_unplug 报 status=5，
                // 残留半死的 uhid 句柄，之后重连只能拿到残缺的 report map。
                // 先摘掉 HID profile，等它真正断开再解绑。
                final BluetoothDevice target = d;
                int st = hidState(d);
                if (st != BluetoothProfile.STATE_DISCONNECTED) {
                    hidDisconnect(d);
                    appendLog("先断开 HID 通道，1.6 秒后解除绑定");
                }
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (removeBond(target)) {
                            appendLog("已解除绑定 · 请让遥控器进入配对模式后重新扫描");
                        } else {
                            appendLog("解除绑定失败（需系统隐藏接口权限）");
                        }
                        refreshPanel();
                        markListDirty();
                    }
                }, 1600);
            }
        });

        // 底部：左日志 / 右按键映射说明
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(0, dp(10), 0, 0);

        LinearLayout logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);

        TextView logTitle = text("日志", 11, C_DIM, false);
        logTitle.setPadding(0, 0, 0, dp(4));
        logBox.addView(logTitle);

        logView = text("", 11, C_SUB, false);
        logView.setLineSpacing(dp(2), 1f);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setFocusable(false);
        logScroll.setFocusableInTouchMode(false);
        logScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        logScroll.addView(logView);
        logBox.addView(logScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bottom.addView(logBox, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottom.addView(space(dp(12), 1));

        // 右下角：按键映射说明（原来这里是整块空白）
        LinearLayout mapCard = new LinearLayout(this);
        mapCard.setOrientation(LinearLayout.VERTICAL);
        mapCard.setBackground(roundRect(C_CARD, dp(10), 0, 0));
        mapCard.setPadding(dp(11), dp(8), dp(11), dp(8));

        TextView mapTitle = text("按键映射", 11, C_DIM, false);
        mapCard.addView(mapTitle);
        mapRow(mapCard, "Home", "122", "系统主页");
        mapRow(mapCard, "TV", "68", "输入选择");
        mapRow(mapCard, "语音", "135", "未映射（可选）");
        // 撑开剩余高度，把生效状态压到卡片底部
        mapCard.addView(new View(this), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mapStatus = text("", 10, C_DIM, false);
        mapStatus.setPadding(0, dp(5), 0, 0);
        mapStatus.setLineSpacing(dp(2), 1f);
        mapCard.addView(mapStatus);
        bottom.addView(mapCard, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f));

        right.addView(bottom, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(right, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(body, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshButtons();
        updateScanState();
    }

    private TextView infoRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView l = text(label, 12, C_SUB, false);
        row.addView(l, lp(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        TextView v = text("—", 13, C_TEXT, false);
        v.setSingleLine(true);
        row.addView(v, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return v;
    }

    /** 按键映射的一行说明：键名 / 键码 / 映射目标。 */
    private void mapRow(LinearLayout parent, String key, String code, String action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        TextView k = text(key, 11, C_TEXT, true);
        row.addView(k, lp(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT, 0f));

        TextView c = text(code, 10, C_DIM, false);
        c.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(c, lp(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT, 0f));

        TextView a = text(action, 11, C_SUB, false);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(a, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private View space(int w, int h) {
        View v = new View(this);
        v.setLayoutParams(lp(w, h == 1 ? ViewGroup.LayoutParams.MATCH_PARENT : h, 0f));
        return v;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) {
            d.setStroke(strokeWidth, strokeColor);
        }
        return d;
    }

    /** 焦点态高亮，遥控器方向键移动时能明显看出落在哪一行/哪个按钮。 */
    private StateListDrawable focusableBg(int normal, int focused, int radius, int focusStroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                roundRect(focused, radius, dp(3), focusStroke));
        s.addState(new int[]{android.R.attr.state_pressed},
                roundRect(focused, radius, dp(3), focusStroke));
        s.addState(new int[]{}, roundRect(normal, radius, dp(1), C_LINE));
        return s;
    }

    /** 把颜色朝白色提亮，用于焦点态填充，避免「蓝底 + 蓝框」看不出焦点。 */
    private static int lighten(int c, float f) {
        int r = Math.min(255, (int) (Color.red(c) + (255 - Color.red(c)) * f));
        int g = Math.min(255, (int) (Color.green(c) + (255 - Color.green(c)) * f));
        int b = Math.min(255, (int) (Color.blue(c) + (255 - Color.blue(c)) * f));
        return Color.rgb(r, g, b);
    }

    private Button button(String label, int bg, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(primary ? 0xFFFFFFFF : C_TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(focusableBg(bg, lighten(bg, 0.28f), dp(8), 0xFFFFFFFF));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        // 不再用 setScaleX/Y 做焦点放大：按权重平分宽度的按钮一放大就会溢出容器边缘，
        // 焦点态完全由「白色描边 + 提亮填充」表达，描边是内描边，不改变布局尺寸。
        b.setStateListAnimator(null);
        return b;
    }

    private static int blend(int a, int b) {
        int r = (Color.red(a) + Color.red(b)) / 2;
        int g = (Color.green(a) + Color.green(b)) / 2;
        int bl = (Color.blue(a) + Color.blue(b)) / 2;
        return Color.rgb(r, g, bl);
    }

    // -------------------------------------------------------- 设备列表

    private DeviceEntry upsert(String mac) {
        DeviceEntry e = devices.get(mac);
        if (e == null) {
            e = new DeviceEntry(mac);
            e.order = seq++;
            devices.put(mac, e);
            markListDirty();
        }
        return e;
    }

    private void seedBondedDevices() {
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            Log.i(TAG, "bonded count=" + bonded.size());
            for (BluetoothDevice d : bonded) {
                DeviceEntry e = upsert(d.getAddress());
                try {
                    if (d.getName() != null) {
                        e.name = d.getName().trim();
                    }
                } catch (Throwable ignored) {
                }
                e.bondState = BluetoothDevice.BOND_BONDED;
                Log.i(TAG, "bonded " + d.getAddress() + " " + e.name);
            }
            if (selected == null && !devices.isEmpty()) {
                select(firstBonded());
            }
            markListDirty();
        } catch (Throwable t) {
            Log.w(TAG, "seedBonded failed: " + t);
        }
    }

    private String firstBonded() {
        for (DeviceEntry e : devices.values()) {
            if (e.bondState == BluetoothDevice.BOND_BONDED) {
                return e.mac;
            }
        }
        return null;
    }

    private void markListDirty() {
        if (listDirty) {
            return;
        }
        listDirty = true;
        ui.postDelayed(new Runnable() {
            public void run() {
                listDirty = false;
                renderList();
            }
        }, 400);
    }

    private void renderList() {
        List<DeviceEntry> list = new ArrayList<DeviceEntry>(devices.values());
        Collections.sort(list, new Comparator<DeviceEntry>() {
            public int compare(DeviceEntry a, DeviceEntry b) {
                boolean ba = a.bondState == BluetoothDevice.BOND_BONDED;
                boolean bb = b.bondState == BluetoothDevice.BOND_BONDED;
                if (ba != bb) {
                    return ba ? -1 : 1;
                }
                int na = a.name == null ? 1 : 0;
                int nb = b.name == null ? 1 : 0;
                if (na != nb) {
                    return na - nb;
                }
                return a.order - b.order;
            }
        });

        StringBuilder key = new StringBuilder();
        for (DeviceEntry e : list) {
            key.append(e.mac).append(',');
        }
        String orderKey = key.toString();
        if (orderKey.equals(lastOrderKey) && !holders.isEmpty()) {
            // 顺序没变：只刷文字，不重建视图，避免焦点被销毁
            updateBadges();
            highlightSelected();
            return;
        }
        lastOrderKey = orderKey;

        // 重建前记住焦点落在哪个设备上，重建后还原
        String focusedMac = null;
        View cf = getCurrentFocus();
        if (cf != null) {
            for (Map.Entry<String, RowHolder> en : holders.entrySet()) {
                if (en.getValue().root == cf || isInside(cf, en.getValue().root)) {
                    focusedMac = en.getKey();
                    break;
                }
            }
        }

        rowsBox.removeAllViews();
        holders.clear();
        if (list.isEmpty()) {
            TextView empty = text("还没有扫描到设备\n\n让遥控器进入配对模式：\n长按「主页 + 菜单」5 秒至指示灯快闪", 13, C_DIM, false);
            empty.setPadding(dp(8), dp(28), dp(8), 0);
            empty.setLineSpacing(dp(4), 1f);
            rowsBox.addView(empty);
        }
        for (DeviceEntry e : list) {
            RowHolder h = makeRow(e);
            holders.put(e.mac, h);
            rowsBox.addView(h.root);
        }
        int bonded = 0;
        int connected = 0;
        for (DeviceEntry e : devices.values()) {
            if (e.bondState == BluetoothDevice.BOND_BONDED) bonded++;
            if (e.hidState == BluetoothProfile.STATE_CONNECTED) connected++;
        }
        listTitle.setText("附近设备  " + devices.size() + " 台"
                + (bonded > 0 ? " · 已配对 " + bonded : "")
                + (connected > 0 ? " · 已连接 " + connected : ""));
        updateBadges();
        highlightSelected();

        // 还原焦点；首次拿到设备则把焦点放到第一行，
        // 否则焦点会落在底部按钮上，方向键手感就乱了。
        final View target;
        if (focusedMac != null && holders.containsKey(focusedMac)) {
            target = holders.get(focusedMac).focusable;
        } else if (!initialFocusDone && !holders.isEmpty()) {
            initialFocusDone = true;
            target = holders.values().iterator().next().focusable;
        } else {
            target = null;
        }
        if (target != null) {
            target.post(new Runnable() {
                public void run() {
                    restoringFocus = true;
                    boolean ok = target.requestFocus();
                    if (!ok) {
                        restoringFocus = false;
                    }
                    // 兜底：requestFocus 没触发焦点变化时，避免标志卡住导致下一次真实聚焦被吞
                    target.postDelayed(new Runnable() {
                        public void run() {
                            restoringFocus = false;
                        }
                    }, 0);
                    Log.i(TAG, "restore focus=" + ok + " cur=" + getCurrentFocus());
                }
            });
        }
    }

    private static boolean isInside(View child, View parent) {
        View p = child;
        while (p != null) {
            if (p == parent) {
                return true;
            }
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return false;
    }

    private RowHolder makeRow(final DeviceEntry e) {
        RowHolder h = new RowHolder();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(focusableBg(0xFF161C24, 0xFF223042, dp(8), 0xFF7FB2FF));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        h.name = text(e.label(), 15, C_TEXT, true);
        h.name.setSingleLine(true);
        h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.mac = text("", 11, C_DIM, false);
        left.addView(h.name);
        left.addView(h.mac);
        row.addView(left, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        h.bondBadge = badge();
        h.hidBadge = badge();
        right.addView(h.bondBadge);
        right.addView(h.hidBadge);
        row.addView(right, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f));

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    // 焦点由程序还原时不算用户主动选择，否则外部 --es mac 指定的设备会被首行顶掉
                    if (restoringFocus) {
                        restoringFocus = false;
                        return;
                    }
                    select(e.mac);
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                select(e.mac);
            }
        });
        // 从列表按右键固定落到该设备第一个可用动作上：
        // 已配对 → 连接 HID；未配对 → 配对。否则会跳到按钮网格中间。
        row.setNextFocusRightId(e.bondState == BluetoothDevice.BOND_BONDED ? ID_CONNECT : ID_BOND);

        View wrap = new View(this);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(row);
        outer.setPadding(0, 0, 0, dp(6));
        h.root = outer;
        h.focusable = row;
        return h;
    }

    private TextView badge() {
        TextView t = text("", 10, C_SUB, false);
        t.setPadding(dp(7), dp(2), dp(7), dp(2));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void updateBadges() {
        for (Map.Entry<String, RowHolder> en : holders.entrySet()) {
            DeviceEntry e = devices.get(en.getKey());
            RowHolder h = en.getValue();
            if (e == null) {
                continue;
            }
            h.name.setText(e.label());
            h.mac.setText(e.mac + (e.rssi > -127 ? "   " + e.rssi + " dBm" : ""));
            if (h.focusable != null) {
                h.focusable.setNextFocusRightId(
                        e.bondState == BluetoothDevice.BOND_BONDED ? ID_CONNECT : ID_BOND);
            }

            int bs = e.bondState;
            if (bs == BluetoothDevice.BOND_BONDED) {
                setBadge(h.bondBadge, "已配对", 0xFF1F4A36, 0xFF6FDDA8);
            } else if (bs == BluetoothDevice.BOND_BONDING) {
                setBadge(h.bondBadge, "配对中", 0xFF4A3D1F, 0xFFE8C273);
            } else {
                setBadge(h.bondBadge, "未配对", 0xFF2A3341, C_SUB);
            }

            int hs = e.hidState;
            if (hs == BluetoothProfile.STATE_CONNECTED) {
                setBadge(h.hidBadge, "已连接", 0xFF1F4A36, 0xFF6FDDA8);
            } else if (hs == BluetoothProfile.STATE_CONNECTING) {
                setBadge(h.hidBadge, "连接中", 0xFF4A3D1F, 0xFFE8C273);
            } else if (hs >= 0) {
                setBadge(h.hidBadge, "未连接", 0xFF2A3341, C_SUB);
            } else {
                setBadge(h.hidBadge, "", 0, 0);
            }
        }
    }

    private void setBadge(TextView t, String s, int bg, int fg) {
        t.setText(s);
        if (s == null || s.isEmpty()) {
            t.setVisibility(View.INVISIBLE);
            return;
        }
        t.setVisibility(View.VISIBLE);
        t.setTextColor(fg);
        t.setBackground(roundRect(bg, dp(6), 0, 0));
    }

    private void highlightSelected() {
        for (Map.Entry<String, RowHolder> en : holders.entrySet()) {
            boolean on = selected != null && selected.mac.equals(en.getKey());
            RowHolder h = en.getValue();
            if (on) {
                h.root.setBackground(roundRect(0xFF1B2634, dp(8), dp(1), 0xFF35507A));
            } else {
                h.root.setBackground(null);
            }
        }
    }

    private void select(String mac) {
        if (mac == null) {
            return;
        }
        DeviceEntry e = devices.get(mac);
        if (e == null) {
            e = upsert(mac);
        }
        if (selected != null && selected.mac.equals(mac)) {
            refreshPanel();
            return;
        }
        selected = e;
        unbondArmedAt = 0;
        bUnbond.setText("解除绑定");
        highlightSelected();
        refreshPanel();
    }

    // -------------------------------------------------------- 状态刷新

    private final Runnable poller = new Runnable() {
        public void run() {
            refreshPanel();
            refreshMapping();
            ui.postDelayed(this, 1500);
        }
    };

    /**
     * 右下角映射卡片的实时状态：读 Settings.Secure 里的 btremap_map 与无障碍服务列表。
     * 只做展示，不写配置（写配置由 tv.sh map / 无障碍服务负责）。
     */
    private void refreshMapping() {
        if (mapStatus == null) {
            return;
        }
        String map = null;
        String svc = null;
        try {
            map = Settings.Secure.getString(getContentResolver(), "btremap_map");
            svc = Settings.Secure.getString(getContentResolver(),
                    "enabled_accessibility_services");
        } catch (Throwable ignored) {
        }
        boolean hasSvc = svc != null && svc.toLowerCase().contains("remap");
        boolean hasHome = map != null && map.contains("122>");
        boolean hasTv = map != null && map.contains("68>");

        if (hasSvc && hasHome && hasTv) {
            mapStatus.setText("已生效 · 无障碍服务接管中");
            mapStatus.setTextColor(C_OK);
        } else if (!hasSvc) {
            mapStatus.setText("未生效 · 无障碍服务未启用");
            mapStatus.setTextColor(C_WARN);
        } else if (!hasHome && !hasTv) {
            mapStatus.setText("未生效 · 映射为空");
            mapStatus.setTextColor(C_WARN);
        } else {
            mapStatus.setText("部分生效 · 缺少 " + (hasHome ? "TV(68)" : "Home(122)"));
            mapStatus.setTextColor(C_WARN);
        }
    }

    private void refreshPanel() {
        BluetoothDevice d = devOf(selected);
        boolean has = d != null;

        if (!has) {
            panelName.setText("未选择设备");
            panelMac.setText("在上方列表中选择一台设备");
            vBond.setText("—");
            vHid.setText("—");
            vPrio.setText("—");
            vRssi.setText("—");
            refreshButtons();
            return;
        }

        String name = null;
        try {
            name = d.getName();
        } catch (Throwable ignored) {
        }
        if (name != null && !name.trim().isEmpty() && selected != null) {
            selected.name = name.trim();
        }
        panelName.setText(selected == null ? d.getAddress() : selected.label());
        panelMac.setText(d.getAddress() + (selected != null && selected.rssi > -127
                ? "   ·   " + selected.rssi + " dBm" : ""));

        int bond = d.getBondState();
        if (selected != null) {
            selected.bondState = bond;
        }
        if (bond == BluetoothDevice.BOND_BONDED) {
            vBond.setText("已配对");
            vBond.setTextColor(C_OK);
        } else if (bond == BluetoothDevice.BOND_BONDING) {
            vBond.setText("配对中…");
            vBond.setTextColor(C_WARN);
        } else {
            vBond.setText("未配对");
            vBond.setTextColor(C_ERR);
        }

        int hs = hidState(d);
        if (selected != null) {
            selected.hidState = hs;
        }
        // 只有内核注册了输入节点，按键才真的能用；profile 的 CONNECTED 会骗人
        boolean inputOk = hs == BluetoothProfile.STATE_CONNECTED && inputDevicePresent(d);
        if (selected != null) {
            selected.inputOk = inputOk;
        }
        if (hs == BluetoothProfile.STATE_CONNECTED && inputOk) {
            vHid.setText("已连接 · 按键生效");
            vHid.setTextColor(C_OK);
        } else if (hs == BluetoothProfile.STATE_CONNECTED) {
            vHid.setText("通道已连 · 按键未注册，点「修复」");
            vHid.setTextColor(C_ERR);
        } else if (hs == BluetoothProfile.STATE_CONNECTING) {
            vHid.setText("连接中…");
            vHid.setTextColor(C_WARN);
        } else if (hs == BluetoothProfile.STATE_DISCONNECTING) {
            vHid.setText("断开中…");
            vHid.setTextColor(C_WARN);
        } else if (hs < 0) {
            vHid.setText("未知");
            vHid.setTextColor(C_DIM);
        } else {
            vHid.setText("未连接");
            vHid.setTextColor(C_SUB);
        }

        int prio = hidPriority(d);
        if (prio < 0) {
            vPrio.setText("不可用");
            vPrio.setTextColor(C_DIM);
        } else if (prio >= 100) {
            vPrio.setText(prio + "（可自动回连）");
            vPrio.setTextColor(C_OK);
        } else {
            vPrio.setText(prio + "（点「重连」补写 100）");
            vPrio.setTextColor(C_WARN);
        }

        if (selected != null && selected.rssi > -127) {
            vRssi.setText(selected.rssi + " dBm");
            vRssi.setTextColor(C_TEXT);
        } else if (hs == BluetoothProfile.STATE_CONNECTED) {
            vRssi.setText("已连接（不广播）");
            vRssi.setTextColor(C_OK);
        } else {
            vRssi.setText("未测到");
            vRssi.setTextColor(C_DIM);
        }

        updateBadges();
        refreshButtons();
    }

    private void refreshButtons() {
        BluetoothDevice d = devOf(selected);
        boolean has = d != null;
        int bond = has ? d.getBondState() : BluetoothDevice.BOND_NONE;
        boolean bonded = bond == BluetoothDevice.BOND_BONDED;

        bBond.setEnabled(has && !bonded && bond != BluetoothDevice.BOND_BONDING);
        bConnect.setEnabled(bonded);
        bReconnect.setEnabled(bonded);
        bDisconnect.setEnabled(bonded);
        bUnbond.setEnabled(bonded);
        bStop.setEnabled(scanning);
        bScan.setEnabled(!scanning);

        // 通道假连时把按钮改成「修复」，让用户知道该点哪个
        boolean needFix = bonded && hidState(d) == BluetoothProfile.STATE_CONNECTED
                && !inputDevicePresent(d);
        String reconnectLabel = needFix ? "修复" : "重连";
        if (!reconnectLabel.contentEquals(bReconnect.getText())) {
            bReconnect.setText(reconnectLabel);
        }

        float a = 0.45f;
        bBond.setAlpha(bBond.isEnabled() ? 1f : a);
        bConnect.setAlpha(bConnect.isEnabled() ? 1f : a);
        bReconnect.setAlpha(bReconnect.isEnabled() ? 1f : a);
        bDisconnect.setAlpha(bDisconnect.isEnabled() ? 1f : a);
        bUnbond.setAlpha(bUnbond.isEnabled() ? 1f : a);
        bScan.setAlpha(bScan.isEnabled() ? 1f : a);
        bStop.setAlpha(bStop.isEnabled() ? 1f : a);
    }

    private void updateScanState() {
        if (scanState == null) {
            return;
        }
        if (scanning) {
            scanState.setText("扫描中  " + (SCAN_MS / 1000) + "s");
            scanState.setTextColor(C_ACCENT);
            scanState.setBackground(roundRect(0xFF1B2634, dp(8), dp(1), 0xFF35507A));
        } else {
            scanState.setText("待机");
            scanState.setTextColor(C_SUB);
            scanState.setBackground(roundRect(0xFF1B1F27, dp(8), dp(1), C_LINE));
        }
        refreshButtons();
    }

    private void appendLog(String s) {
        logLines.add(s);
        while (logLines.size() > 14) {
            logLines.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            sb.append(l).append('\n');
        }
        if (logView != null) {
            logView.setText(sb.toString());
        }
    }

    // -------------------------------------------------------- 扫描

    private void startScan() {
        if (adapter == null) {
            return;
        }
        if (!adapter.isEnabled()) {
            adapter.enable();
            appendLog("正在打开蓝牙…");
            ui.postDelayed(new Runnable() {
                public void run() {
                    startScan();
                }
            }, 2000);
            return;
        }
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        scanRounds++;
        boolean ok = adapter.startDiscovery();
        scanning = true;
        updateScanState();
        Log.i(TAG, "SCAN_START ok=" + ok + " round=" + scanRounds + "/" + MAX_ROUNDS
                + " keyword=" + nameKeyword + " autobond=" + autoBond);
        appendLog("开始扫描（第 " + scanRounds + " 轮）");
        ui.postDelayed(new Runnable() {
            public void run() {
                if (adapter != null && adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
                StringBuilder sb = new StringBuilder("SCAN_RESULT count=" + devices.size());
                for (DeviceEntry e : devices.values()) {
                    sb.append(" | ").append(e.mac).append("  ").append(e.label());
                }
                Log.i(TAG, sb.toString());
            }
        }, SCAN_MS);
    }

    private void stopScan() {
        try {
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (Throwable ignored) {
        }
        scanning = false;
        autoBond = false;
        updateScanState();
        appendLog("已停止扫描");
    }

    private void maybeNextRound() {
        if (!autoBond || wantMac != null) {
            if (autoBond && wantMac != null) {
                Log.i(TAG, "SCAN_STOP target already chosen " + wantMac);
            }
            return;
        }
        if (scanRounds >= MAX_ROUNDS) {
            Log.i(TAG, "RESULT=NOT_FOUND rounds=" + scanRounds + " keyword=" + nameKeyword);
            appendLog("未找到 " + nameKeyword + "，请让设备进入配对模式后重试");
            autoBond = false;
            return;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                if (wantMac == null && autoBond) {
                    startScan();
                }
            }
        }, 1500);
    }

    // -------------------------------------------------------- 配对 / HID

    /**
     * 按设备实际承载的传输层发起配对。
     * 遥控器是纯 BLE HID，但 bt_config 里常被记成 DUAL(type=2)，
     * 于是 createBond() 先试 BR/EDR，白等 30 秒直到 SMP 超时
     * （日志：L2CA_RemoveFixedChnl CID:0x0006 not connected / btm_proc_smp_cback: p_dev_rec is NULL）。
     * 这里先把 type 纠正为 LE，再走 createBond(TRANSPORT_LE)，让配对直接落在 LE 上。
     */
    private boolean createBondForced(BluetoothDevice dev) {
        int type = -1;
        try {
            type = dev.getType();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "bond prep: type=" + type + " name=" + dev.getName());
        boolean le = type == BluetoothDevice.DEVICE_TYPE_LE
                || type == BluetoothDevice.DEVICE_TYPE_DUAL;
        if (le) {
            try {
                Method st = BluetoothDevice.class.getMethod("setType", int.class);
                Object r = st.invoke(dev, BluetoothDevice.DEVICE_TYPE_LE);
                Log.i(TAG, "setType(LE) = " + r);
            } catch (Throwable t) {
                Log.w(TAG, "setType failed: " + t);
            }
            try {
                Method cb = BluetoothDevice.class.getMethod("createBond", int.class);
                Object r = cb.invoke(dev, 2 /* TRANSPORT_LE */);
                if (Boolean.TRUE.equals(r)) {
                    Log.i(TAG, "createBond(TRANSPORT_LE) = true");
                    return true;
                }
                Log.w(TAG, "createBond(TRANSPORT_LE) = " + r);
            } catch (Throwable t) {
                Log.w(TAG, "createBond(transport) failed: " + t);
            }
        }
        try {
            return dev.createBond();
        } catch (Throwable t) {
            Log.e(TAG, "createBond failed: " + t);
            return false;
        }
    }

    private void bond(BluetoothDevice dev) {
        int state = dev.getBondState();
        Log.i(TAG, "bond request " + dev.getAddress() + " state=" + stateName(state));
        if (state == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "RESULT=ALREADY_BONDED " + dev.getAddress());
            connectHid(dev, 3);
            return;
        }
        if (state == BluetoothDevice.BOND_BONDING) {
            appendLog("正在配对中，稍等…");
            return;
        }
        boolean started = createBondForced(dev);
        Log.i(TAG, "createBond() = " + started + " for " + dev.getAddress());
        appendLog(started ? "发起配对：" + dev.getAddress() : "配对请求被拒绝");
        if (!started && dev.getAddress().equals(wantMac)) {
            wantMac = null;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                refreshPanel();
            }
        }, 1500);
    }

    private void doReconnect() {
        final BluetoothDevice d = devOf(selected);
        if (d == null) {
            return;
        }
        ensureHidProxy();
        // CONNECTED 但输入节点缺失 = 通道假连，先彻底断开再重挂，等待时间也要放宽
        boolean broken = hidState(d) == BluetoothProfile.STATE_CONNECTED && !inputDevicePresent(d);
        appendLog(broken ? "修复：通道已连但按键无效，断开后重新挂载" : ("重连：" + d.getAddress()));
        hidDisconnect(d);
        reconnectAfterDisconnect(d, broken ? 2200 : 900, 0);
    }

    /** 等 HID 真正回落到未连接再发起 connect，否则会被蓝牙栈判定为 already added 而复用坏句柄。 */
    private void reconnectAfterDisconnect(final BluetoothDevice d, final long delay, final int attempt) {
        ui.postDelayed(new Runnable() {
            public void run() {
                int st = hidState(d);
                boolean stillUp = st == BluetoothProfile.STATE_CONNECTED
                        || st == BluetoothProfile.STATE_DISCONNECTING;
                if (stillUp && attempt < 6) {
                    reconnectAfterDisconnect(d, 700, attempt + 1);
                    return;
                }
                Log.i(TAG, "reconnect: state=" + st + " attempt=" + attempt);
                hidSetPriority(d, 100);
                connectHid(d, 4);
            }
        }, delay);
    }

    private BluetoothDevice devOf(DeviceEntry e) {
        if (e == null || adapter == null) {
            return null;
        }
        try {
            return adapter.getRemoteDevice(e.mac);
        } catch (Throwable t) {
            return null;
        }
    }

    private BluetoothDevice pickByName() {
        if (adapter == null) {
            return null;
        }
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n != null && matchesName(n)) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

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

    /**
     * Android 9 把 connect/setPriority 放进了隐藏 API 黑名单，getMethod 会直接抛
     * NoSuchMethodException。这里通过双重反射调用 VMRuntime.setHiddenApiExemptions("L")
     * 解除整个 L 前缀的限制，之后普通反射即可访问。
     */
    private void bypassHiddenApi() {
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

    private void ensureHidProxy() {
        if (hidProxy != null || adapter == null || proxyRequested) {
            return;
        }
        proxyRequested = true;
        try {
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    hidProxy = proxy;
                    proxyRequested = false;
                    Log.i(TAG, "HID proxy connected class=" + proxy.getClass().getName());
                    ui.post(new Runnable() {
                        public void run() {
                            refreshPanel();
                        }
                    });
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    Log.i(TAG, "HID proxy disconnected");
                    hidProxy = null;
                    proxyRequested = false;
                }
            }, PROFILE_HID_HOST);
        } catch (Throwable t) {
            proxyRequested = false;
            Log.e(TAG, "getProfileProxy failed: " + t);
        }
    }

    private int hidState(BluetoothDevice dev) {
        if (hidProxy == null || dev == null) {
            return -1;
        }
        try {
            Method m = hidProxy.getClass().getMethod("getConnectionState", BluetoothDevice.class);
            return (Integer) m.invoke(hidProxy, dev);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * HID profile 报 CONNECTED 不等于按键生效：只有当内核真的建出输入节点才算数。
     * 蓝牙栈把设备名原样交给 uhid，所以按名字匹配，地址的两种写法作为兜底。
     * 出问题时 dscp_len 会短于完整 report map（例如 66 vs 86）或 attr_mask=0，
     * 此时 uhid 拒绝注册，getevent 里看不到设备，必须断开重连。
     */
    private boolean inputDevicePresent(BluetoothDevice d) {
        if (d == null) {
            return false;
        }
        String dn = null;
        try {
            dn = d.getName();
        } catch (Throwable ignored) {
        }
        if (dn != null) {
            dn = dn.trim();
        }
        String addr = d.getAddress();
        String addrUs = addr == null ? null : addr.replace(':', '_');
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice in = InputDevice.getDevice(id);
                if (in == null) {
                    continue;
                }
                String n = in.getName();
                if (n == null || n.trim().isEmpty()) {
                    continue;
                }
                if (dn != null && !dn.isEmpty() && n.trim().equalsIgnoreCase(dn)) {
                    return true;
                }
                if (addr != null && (n.trim().equalsIgnoreCase(addr)
                        || (addrUs != null && n.trim().equalsIgnoreCase(addrUs)))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "inputDevicePresent failed: " + t);
        }
        return false;
    }

    private int hidPriority(BluetoothDevice dev) {        if (hidProxy == null || dev == null) {
            return -1;
        }
        try {
            Method m = hidProxy.getClass().getMethod("getPriority", BluetoothDevice.class);
            return (Integer) m.invoke(hidProxy, dev);
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean hidSetPriority(BluetoothDevice dev, int value) {
        if (hidProxy == null || dev == null) {
            return false;
        }
        try {
            Method m = hidProxy.getClass().getMethod("setPriority", BluetoothDevice.class, int.class);
            boolean ok = (Boolean) m.invoke(hidProxy, dev, value);
            Log.i(TAG, "HID setPriority(100)=" + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "HID setPriority failed: " + t);
            return false;
        }
    }

    private boolean hidConnect(BluetoothDevice dev) {
        if (hidProxy == null || dev == null) {
            return false;
        }
        try {
            Method m = hidProxy.getClass().getMethod("connect", BluetoothDevice.class);
            return (Boolean) m.invoke(hidProxy, dev);
        } catch (Throwable t) {
            Log.e(TAG, "HID connect failed: " + t);
            return false;
        }
    }

    private boolean hidDisconnect(BluetoothDevice dev) {
        if (hidProxy == null || dev == null) {
            return false;
        }
        try {
            Method m = hidProxy.getClass().getMethod("disconnect", BluetoothDevice.class);
            return (Boolean) m.invoke(hidProxy, dev);
        } catch (Throwable t) {
            Log.e(TAG, "HID disconnect failed: " + t);
            return false;
        }
    }

    /** 带重试的 HID 连接：connect() 返回 false 常见于状态还没回落，隔几秒再试。 */
    private void connectHid(final BluetoothDevice dev, final int attempts) {
        connectHid(dev, attempts, 1);
    }

    private void connectHid(final BluetoothDevice dev, final int attempts, final int round) {
        if (dev == null) {
            return;
        }
        ensureHidProxy();
        ui.post(new Runnable() {
            public void run() {
                if (hidProxy == null) {
                    if (round <= attempts) {
                        ui.postDelayed(new Runnable() {
                            public void run() {
                                connectHid(dev, attempts, round);
                            }
                        }, 800);
                    }
                    return;
                }
                int st = hidState(dev);
                if (st == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "HID connectionState = 2 (already connected)");
                    appendLog("HID 已连接");
                    refreshPanel();
                    return;
                }
                boolean ok = hidConnect(dev);
                Log.i(TAG, "HID connect() = " + ok + " attempt=" + round);
                int now = hidState(dev);
                Log.i(TAG, "HID connectionState = " + now);
                appendLog("连接 HID 第 " + round + " 次：" + (ok ? "已发起" : "被拒绝")
                        + (now >= 0 ? "，状态 " + now : ""));
                refreshPanel();
                if (now != BluetoothProfile.STATE_CONNECTED && round < attempts) {
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            connectHid(dev, attempts, round + 1);
                        }
                    }, 3500);
                }
            }
        });
    }

    /** removeBond 是隐藏接口，需要先解除隐藏 API 限制。 */
    private boolean removeBond(BluetoothDevice dev) {
        if (dev == null) {
            return false;
        }
        if (dev.getBondState() != BluetoothDevice.BOND_BONDED) {
            return false;
        }
        try {
            Method m = BluetoothDevice.class.getDeclaredMethod("removeBond");
            m.setAccessible(true);
            Object r = m.invoke(dev);
            Log.i(TAG, "removeBond() = " + r + " for " + dev.getAddress());
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            Log.w(TAG, "removeBond failed: " + t);
            return false;
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            appendLog("已获得位置权限，可扫描蓝牙设备");
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
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

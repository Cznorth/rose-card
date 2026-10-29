package io.github.cznorth.rosecard;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 在欢律（com.oplus.melody）进程里为 Rose 耳机写入 mydevices 卡片，并接管卡片上的降噪点击。 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "RoseCard";
    static final String MELODY = "com.oplus.melody";
    static final String AUTHORITY = "com.oplus.melody.devicecard.MyDeviceProvider";
    static final String SETGATE = "com.oplus.melody.setgate";

    /** 卡片模式类型（melody 定义）→ 资源名后缀 / 图标名 */
    static final int T_CLOSE = 1, T_TRANS = 2, T_WEAK = 3, T_STRONG = 4, T_MID = 5;
    /** 10 在欢律里是「自适应」，借来当 Rose 的透风（防风降噪）模式（面板文案由 hook 替换） */
    static final int T_WIND = 10;
    static final String WIND_LABEL = "透风";
    static final int[] TYPES = {T_CLOSE, T_TRANS, T_WEAK, T_MID, T_STRONG, T_WIND};

    static final String ACTION_STATE = "io.github.cznorth.rosecard.STATE";
    static boolean isMain;
    static ClassLoader cl;
    static Context app;
    static volatile int curType = T_STRONG;
    static volatile String mac, name;
    /** 电量：左、右、盒，-1 未知；充电标志 */
    static volatile int batL = -1, batR = -1, batBox = -1;
    static volatile boolean chgL, chgR, chgBox;
    static final String ACTION_BATTERY = "io.github.cznorth.rosecard.BATTERY";
    static final String ROSELINK = "cn.ikaile.ruoshui.client";

    static void log(String s) { Log.i(TAG, s); XposedBridge.log(TAG + ": " + s); }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!MELODY.equals(lp.packageName)) return;
        if (!MELODY.equals(lp.processName) && !(MELODY + ":fg").equals(lp.processName)) return;
        isMain = MELODY.equals(lp.processName);
        cl = lp.classLoader;
        log("loaded in " + lp.processName);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (app != null) return;
                app = (Application) p.thisObject;
                Handler h = new Handler(Looper.getMainLooper());
                RoseBle.batteryListener = Hook::onBattery;
                if (isMain) {
                    h.postDelayed(Hook::poll, 12000);
                    android.content.IntentFilter f = new android.content.IntentFilter();
                    f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                    f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                    app.registerReceiver(new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) {
                            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                            if (d == null || !isRose(d)) return;
                            // 只认经典蓝牙链路；BLE 控制链路（本模块或 RoseLink 建的）断开不代表耳机断开
                            int tr = i.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO);
                            if (tr == BluetoothDevice.TRANSPORT_LE) { log("acl le ignored"); return; }
                            boolean on = BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction());
                            log("acl " + (on ? "connected" : "disconnected"));
                            if (on) h.postDelayed(() -> readState(Hook::refresh), 4000);
                            else remove();
                        }
                    }, f, Context.RECEIVER_EXPORTED);
                    app.registerReceiver(new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) {
                            setBattery(i.getIntArrayExtra("v"));
                            refresh();
                        }
                    }, new android.content.IntentFilter(ACTION_BATTERY), Context.RECEIVER_NOT_EXPORTED);
                } else {
                    android.content.IntentFilter f = new android.content.IntentFilter(ACTION_STATE);
                    app.registerReceiver(new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) {
                            curType = i.getIntExtra("type", curType);
                            log("state from main: " + curType);
                            fillPanel();
                        }
                    }, f, Context.RECEIVER_NOT_EXPORTED);
                }
            }
        });
        try {
            XposedHelpers.findAndHookMethod("com.oplus.melody.mydevices.devicecard.noisereduction.NoiseReductionCommand",
                    cl, "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { if (onSetgate((Intent) p.args[1])) p.setResult(null); }
                    catch (Throwable t) { log("onSetgate " + Log.getStackTraceString(t)); }
                }
            });
        } catch (Throwable t) { log("hook NoiseReductionCommand failed " + t); }
        try {
            XposedHelpers.findAndHookMethod("com.oplus.melody.onespace.b", cl, "onViewCreated",
                    android.view.View.class, Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object f = p.thisObject;
                        String m = (String) getField(f, "z");
                        if (mac == null) { BluetoothDevice d = findRose(); if (d != null) { mac = d.getAddress(); name = d.getAlias() != null ? d.getAlias() : d.getName(); } }
                        log("OneSpace onViewCreated match=" + (mac != null && mac.equalsIgnoreCase(m)));
                        if (mac == null || !mac.equalsIgnoreCase(m)) { panel = null; return; }
                        panel = new java.lang.ref.WeakReference<>(f);
                        new Handler(Looper.getMainLooper()).postDelayed(Hook::fillPanel, 300);
                        readState(() -> new Handler(Looper.getMainLooper()).post(Hook::fillPanel));
                    } catch (Throwable t) { log("onViewCreated " + Log.getStackTraceString(t)); }
                }
            });
        } catch (Throwable t) { log("hook OneSpace failed " + t); }
        if (isMain) return;
        // 面板降噪按钮：类型 10 欢律显示「自适应」，Rose 面板上改成「透风」
        try {
            XposedHelpers.findAndHookMethod("com.oplus.melody.onespace.items.OneSpaceNoisePreference", cl, "i",
                    Context.class, int.class, android.graphics.drawable.Drawable.class, String.class, boolean.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if ((Integer) p.args[1] == T_WIND && panel != null && panel.get() != null) p.args[3] = WIND_LABEL;
                }
            });
        } catch (Throwable t) { log("hook noise item failed " + t); }
        // 面板底部「立即连接」：ContentFragment.z(fragment, 连接状态)，2 = 已连接则隐藏
        try {
            Class<?> cf = cl.loadClass("com.oplus.melody.onespace.ContentFragment");
            XposedHelpers.findAndHookMethod(cf, "z", cf, Integer.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        content = new java.lang.ref.WeakReference<>(p.args[0]);
                        if (isRosePanel(p.args[0], "mAddress")) p.args[1] = 2;
                    } catch (Throwable t) { log("ContentFragment.z " + t); }
                }
            });
            XposedHelpers.findAndHookMethod(cf, "initView", android.view.View.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    content = new java.lang.ref.WeakReference<>(p.thisObject);
                    try { log("ContentFragment.initView rose=" + isRosePanel(p.thisObject, "mAddress")); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("hook ContentFragment failed " + t); }
        // 电量：melody 自己推的（未连接/无数据）替换成 Rose 的
        try {
            XposedHelpers.findAndHookMethod("com.oplus.melody.onespace.items.OneSpaceHeaderPreference$initObserver$4",
                    cl, "invoke", Object.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object f = panel == null ? null : panel.get();
                        if (f == null || p.args[0] == ourBattery) return;
                        if (getField(p.thisObject, "receiver") != getField(f, "x")) return;
                        Object b = batteryVO();
                        if (b != null) p.args[0] = b;
                    } catch (Throwable t) { log("header$4 " + t); }
                }
            });
        } catch (Throwable t) { log("hook header failed " + t); }
        // 「耳机设置」→ RoseLink；「断开连接」→ 断开 Rose
        try {
            XposedHelpers.findAndHookMethod("androidx.preference.Preference", cl, "performClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object f = panel == null ? null : panel.get();
                        if (f == null) return;
                        Object pref = p.thisObject;
                        if (pref == getField(f, "s")) { log("more setting -> RoseLink"); openRoseLink(f); p.setResult(null); }
                        else if (pref == getField(f, "y")) { log("disconnect"); disconnect(f); p.setResult(null); }
                    } catch (Throwable t) { log("performClick " + Log.getStackTraceString(t)); }
                }
            });
        } catch (Throwable t) { log("hook performClick failed " + t); }
    }

    static java.lang.ref.WeakReference<Object> content;
    static volatile Object ourBattery;

    static boolean isRose(BluetoothDevice d) {
        return isRoseName(d.getName());
    }
    static boolean isRosePanel(Object o, String field) throws Exception {
        if (mac == null) { BluetoothDevice d = findRose(); if (d != null) mac = d.getAddress(); }
        Object m = getField(o, field);
        return mac != null && m instanceof String && mac.equalsIgnoreCase((String) m);
    }
    static Object activityOf(Object fragment) throws Exception {
        Method m = null;
        for (Class<?> c = fragment.getClass(); c != null && m == null; c = c.getSuperclass())
            try { m = c.getDeclaredMethod("getActivity"); } catch (NoSuchMethodException ignored) {}
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(fragment);
    }
    static void openRoseLink(Object f) throws Exception {
        android.app.Activity a = (android.app.Activity) activityOf(f);
        Context c = a != null ? a : app;
        // 欢律没声明 <queries>，查不到 RoseLink；显式组件启动不受包可见性限制
        Intent it = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ROSELINK, ROSELINK + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(it); }
        catch (Throwable t) { log("start RoseLink failed " + t); return; }
        if (a != null) a.finish();
    }
    /** 同欢律自己的做法：经 HFP / A2DP profile 代理反射调用 disconnect(device)；失败则打开蓝牙设置。 */
    static void disconnect(Object f) throws Exception {
        android.app.Activity a = (android.app.Activity) activityOf(f);
        BluetoothDevice d = findRose();
        BluetoothManager bm = app.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (d == null || ad == null) { openBtSettings(a); return; }
        final int[] left = {2};
        final boolean[] ok = {false};
        for (int prof : new int[]{android.bluetooth.BluetoothProfile.HEADSET, android.bluetooth.BluetoothProfile.A2DP}) {
            ad.getProfileProxy(app, new android.bluetooth.BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int pf, android.bluetooth.BluetoothProfile proxy) {
                    try {
                        Object r = proxy.getClass().getMethod("disconnect", BluetoothDevice.class).invoke(proxy, d);
                        log("profile " + pf + " disconnect -> " + r);
                        if (Boolean.TRUE.equals(r)) ok[0] = true;
                    } catch (Throwable t) { log("profile " + pf + " disconnect failed " + (t.getCause() != null ? t.getCause() : t)); }
                    ad.closeProfileProxy(pf, proxy);
                    if (--left[0] == 0) {
                        if (!ok[0]) openBtSettings(a);
                        if (a != null) a.finish();
                    }
                }
                @Override public void onServiceDisconnected(int pf) {}
            }, prof);
        }
    }
    static void openBtSettings(android.app.Activity a) {
        Context c = a != null ? a : app;
        try { c.startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable t) { log("bt settings " + t); }
    }

    /** 电量 payload → batL/batR/batBox（布局见 README）。 */
    static void onBattery(byte[] p) {
        // payload 7 字节：?? ?? 左 右 左充电? 右充电? 盒（ff = 未知）
        if (p.length < 7) return;
        int[] v = {p[2] & 0xff, p[3] & 0xff, p[6] & 0xff, p[4], p[5], 0};
        setBattery(v);
        log("battery L=" + batL + (chgL ? "+" : "") + " R=" + batR + (chgR ? "+" : "") + " box=" + batBox + (chgBox ? "+" : ""));
        if (!isMain && app != null) {
            app.sendBroadcast(new Intent(ACTION_BATTERY).setPackage(MELODY).putExtra("v", v));
            new Handler(Looper.getMainLooper()).post(Hook::fillPanel);
        }
    }
    static void setBattery(int[] v) {
        if (v == null || v.length < 6) return;
        batL = v[0] <= 100 ? v[0] : -1; batR = v[1] <= 100 ? v[1] : -1; batBox = v[2] <= 100 ? v[2] : -1;
        chgL = v[3] != 0; chgR = v[4] != 0; chgBox = v[5] != 0;
    }
    static Object batteryVO() throws Exception {
        if (batL < 0 && batR < 0) return null;
        Object b = make("P9.a", "mConnectionState", 2, "mHeadsetConnectionState", 2,
                "mLeftBattery", batL, "mRightBattery", batR, "mBoxBattery", batBox,
                "mHeadsetLeftBattery", batL, "mHeadsetRightBattery", batR, "mHeadsetBoxBattery", batBox,
                "mIsLeftCharging", chgL, "mIsRightCharging", chgR, "mIsBoxCharging", chgBox,
                "mIsSpp", false, "mType", "");
        ourBattery = b;
        return b;
    }

    /** 主进程：Rose 连着就读一次状态和电量并刷新卡片，每 10 分钟一次。 */
    static void poll() {
        try {
            BluetoothDevice d = findRose();
            boolean conn = false;
            if (d != null) try { conn = (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d); } catch (Throwable t) { conn = true; }
            log("poll connected=" + conn);
            if (conn) readState(Hook::refresh);
        } catch (Throwable t) { log("poll " + t); }
        new Handler(Looper.getMainLooper()).postDelayed(Hook::poll, 10 * 60 * 1000);
    }

    static synchronized void remove() {
        try {
            if (mac == null) return;
            if (name == null) name = mac;
            insert(build(), "g");
            log("card removed");
        } catch (Throwable t) { log("remove " + Log.getStackTraceString(t)); }
    }

    static java.lang.ref.WeakReference<Object> panel;

    static Object getField(Object o, String n) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField(n); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(n);
    }
    static void setField(Object o, String n, Object v) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField(n); f.setAccessible(true); f.set(o, v); return; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(n);
    }
    static Object unsafe;
    static Object alloc(String cls) throws Exception {
        Class<?> c = cl.loadClass(cls);
        if (unsafe == null) {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = u.getDeclaredField("theUnsafe"); f.setAccessible(true); unsafe = f.get(null);
        }
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, c);
    }
    /** 新建对象：Unsafe 分配后逐字段赋值（kv 交替：字段名, 值）。 */
    static Object make(String cls, Object... kv) throws Exception {
        Object o = alloc(cls);
        for (int i = 0; i < kv.length; i += 2) setField(o, (String) kv[i], kv[i + 1]);
        return o;
    }
    static Object fnRef(String cls, Object receiver) throws Exception {
        Object o = alloc(cls);
        setField(o, "receiver", receiver);
        return o;
    }
    static void invoke1(Object fn, Object arg) throws Exception {
        for (Method m : fn.getClass().getDeclaredMethods())
            if (m.getName().equals("invoke") && m.getParameterTypes().length == 1) { m.setAccessible(true); m.invoke(fn, arg); return; }
        throw new NoSuchMethodException("invoke");
    }

    static synchronized void fillPanel() {
        Object f = panel == null ? null : panel.get();
        if (f == null) return;
        try {
            final String NRM = "com.oplus.melody.common.data.WhitelistConfigDTO$NoiseReductionMode";
            java.util.ArrayList<Object> modes = new java.util.ArrayList<>();
            for (int t : TYPES)
                modes.add(make(NRM, "modeType", t, "protocolIndex", t, "childrenMode", new java.util.ArrayList<>(),
                        "decideByEarDevice", false));
            Object fun = make("com.oplus.melody.common.data.WhitelistConfigDTO$Function",
                    "noiseReductionMode", modes, "noiseReductionUIVersion", 0);
            Object wl = make("com.oplus.melody.common.data.WhitelistConfigDTO", "function", fun,
                    "id", "rose", "name", name);
            invoke1(fnRef("com.oplus.melody.onespace.OneSpaceListFragment$initObserver$1", f), wl);

            Object vo = make("Ba.z", "mConnectionState", 2, "mHeadsetConnectionState", 2,
                    "mCurrentNoiseReductionModeIndex", curType, "mNoiseReductionModeList", modes,
                    "mIsCapabilityReady", true, "mNoiseReductionUIVersion", 0);
            invoke1(fnRef("com.oplus.melody.onespace.OneSpaceListFragment$initObserver$2", f), vo);

            Object pref = getField(f, "u");
            if (pref != null) {
                Object nvo = make("l9.b", "mAddress", mac, "mProductName", name, "mProductId", "rose",
                        "mConnectState", 2, "mCurrentNoiseMode", curType, "mNoiseReductionModeList", modes,
                        "mNoiseReductionUIVersion", 0);
                invoke1(fnRef("com.oplus.melody.onespace.items.OneSpaceNoisePreference$initObserver$1", pref), nvo);
            }
            Object hdr = getField(f, "x"), bvo = batteryVO();
            if (hdr != null && bvo != null)
                invoke1(fnRef("com.oplus.melody.onespace.items.OneSpaceHeaderPreference$initObserver$4", hdr), bvo);
            Object cf = content == null ? null : content.get();
            if (cf != null && isRosePanel(cf, "mAddress")) {
                Method z = cf.getClass().getDeclaredMethod("z", cf.getClass(), Integer.class);
                z.setAccessible(true);
                z.invoke(null, cf, 2);
            }
            log("panel filled, cur=" + curType + " bat=" + batL + "/" + batR + "/" + batBox + " content=" + (cf != null));
        } catch (Throwable t) { log("fillPanel " + Log.getStackTraceString(t)); }
    }

    /** 返回 true 表示是 Rose 的点击，已处理。 */
    static boolean onSetgate(Intent it) throws Exception {
        Bundle b = it.getExtras();
        StringBuilder sb = new StringBuilder("setgate action=" + it.getAction());
        String json = null;
        if (b != null) for (String k : b.keySet()) {
            Object v = b.get(k);
            sb.append(' ').append(k).append('=').append(v);
            if (v instanceof String && ((String) v).contains("\"type\"")) json = (String) v;
        }
        log(sb.toString());
        if (mac == null) { BluetoothDevice d = findRose(); if (d != null) { mac = d.getAddress(); name = d.getAlias() != null ? d.getAlias() : d.getName(); } }
        if (json == null || mac == null) return false;
        JSONObject j = new JSONObject(json);
        if (!mac.equalsIgnoreCase(j.optString("mac"))) return false;
        int type = j.optInt("type", -1);
        apply(type);
        return true;
    }

    static void apply(int type) {
        int mode, level = -1;           // Rose: mode 0 降噪 1 普通 2 防风降噪 3 通透；level 0 轻 1 中 2 深
        switch (type) {
            case T_CLOSE: mode = 1; break;
            case T_TRANS: mode = 3; break;
            case T_WEAK: mode = 0; level = 0; break;
            case T_MID: mode = 0; level = 1; break;
            case T_STRONG: mode = 0; level = 2; break;
            case T_WIND: mode = 2; break;
            default: log("unknown type " + type); return;
        }
        final int lv = level;
        RoseBle ble = RoseBle.get(app);
        ble.setMode(mode, (v, err) -> {
            log("setMode " + mode + " -> " + v + " err=" + err);
            if (err != null) return;
            if (lv < 0) { curType = type; refreshAll(); return; }
            ble.setLevel(lv, (v2, err2) -> {
                log("setLevel " + lv + " -> " + v2 + " err=" + err2);
                if (err2 == null) curType = type;
                refreshAll();
            });
        });
    }

    static BluetoothDevice findRose() {
        BluetoothManager bm = app.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) return null;
        for (BluetoothDevice d : ad.getBondedDevices()) {
            String n = d.getName();
            if (isRoseName(n)) return d;
        }
        return null;
    }

    /** 经典蓝牙名为「ROSE EARFREE i5」（也兼容 EarFeel 写法） */
    static boolean isRoseName(String n) {
        if (n == null) return false;
        String u = n.toUpperCase();
        return u.startsWith("ROSE") && (u.contains("EARFREE") || u.contains("EARFEEL"));
    }

    static String str(String n) {
        int id = app.getResources().getIdentifier(n, "string", MELODY);
        return id == 0 ? n : app.getString(id);
    }
    static int drw(String n) { return app.getResources().getIdentifier(n, "drawable", MELODY); }

    static void refreshAll() {
        refresh();
        try { app.sendBroadcast(new Intent(ACTION_STATE).setPackage(MELODY).putExtra("type", curType)); }
        catch (Throwable t) { log("broadcast " + t); }
    }

    static int typeOf(int mode, int level) {
        if (mode == 1) return T_CLOSE;
        if (mode == 3) return T_TRANS;
        if (mode == 0) return level == 0 ? T_WEAK : level == 1 ? T_MID : T_STRONG;
        if (mode == 2) return T_WIND;
        return -1;
    }

    /** 从耳机读模式与强度，更新 curType 后执行 then（BLE 线程）。 */
    static void readState(Runnable then) {
        RoseBle ble = RoseBle.get(app);
        ble.readMode((m, err) -> {
            if (err != null) { log("readMode err=" + err); then.run(); return; }
            ble.readLevel((lv, err2) -> {
                if (err2 == null) curType = typeOf(m, lv);
                log("readState mode=" + m + " level=" + lv + " -> " + curType);
                ble.readBattery((n, err3) -> { if (err3 != null) log("readBattery err=" + err3); then.run(); });
            });
        });
    }

    static synchronized void refresh() {
        try {
            BluetoothDevice d = findRose();
            if (d == null) { log("rose not bonded"); return; }
            mac = d.getAddress();
            String alias = d.getAlias();
            name = alias != null ? alias : d.getName();
            insert(build(), "h");
        } catch (Throwable t) { log("refresh " + Log.getStackTraceString(t)); }
    }

    static JSONObject build() throws Exception {
        long now = System.currentTimeMillis();
        String id = "rose_" + mac.replace(":", "").toLowerCase();
        JSONObject j = new JSONObject();
        j.put("deviceId", id).put("macAddress", mac).put("name", name).put("modelId", "rose_earfeel_i5")
         .put("type", "HEADSET").put("mConnectState", "CONNECTED").put("useState", "USED")
         .put("isActive", true).put("mAuthority", AUTHORITY).put("associatedPackageName", MELODY)
         .put("mTemplateType", "MULTI_BATTERY").put("mTemplateList", new JSONArray())
         .put("mDeviceIcon", drw("melody_mydevices_ic_report_headset"))
         .put("isSupportNoiseCanceling", true).put("mIsSupportAudioConnect", true)
         .put("timestamp", now).put("mConnectTime", now)
         .put("data", "").put("iconUrl", "").put("mIconUrl", "").put("videoUrl", "");
        j.put("connection", new JSONObject().put("connectState", "CONNECTED")
                .put("lastConnectTime", now).put("lastDisconnectTime", 0));

        JSONObject act = new JSONObject().put("mDeviceId", id).put("mMenuActionType", "activity")
                .put("mMenuIntentPackage", "cn.ikaile.ruoshui.client")
                .put("mMenuIntentClass", "cn.ikaile.ruoshui.client.MainActivity")
                .put("mMenuIntentAction", "android.intent.action.MAIN")
                .put("mMenuIntentHaveExtra", 0).put("mAuthority", AUTHORITY)
                .put("intentExtraList", new JSONArray());
        j.put("mActionMenuList", new JSONArray().put(act));

        JSONArray sc = new JSONArray(), sw = new JSONArray();
        for (int t : TYPES) {
            String label, icon;
            switch (t) {
                case T_CLOSE: label = "close_reduction"; icon = "normal"; break;
                case T_TRANS: label = "pass_through_reduction"; icon = "transparent"; break;
                case T_WEAK: label = "weak_reduction"; icon = "weak"; break;
                case T_MID: label = "reduction"; icon = "strong"; break;
                case T_WIND: label = null; icon = "adaptive"; break;
                default: label = "strong_reduction"; icon = "strong"; break;
            }
            String nm = label == null ? WIND_LABEL : str("melody_mydevices_noise_reduction_action_" + label);
            String extra = new JSONObject().put("type", t).put("mac", mac).put("product_name", name)
                    .put("product_id", "rose").toString();
            String link = "melody://com.oplus.melody/headset?type=" + t + "&protocol=" + t + "&mac=" + mac;
            sc.put(new JSONObject().put("shortcutId", String.valueOf(t)).put("shortcutName", nm)
                    .put("icon", 0).put("iconHighlight", 0).put("action", SETGATE).put("componentType", 1)
                    .put("links", link).put("extra", extra).put("state", t == curType ? 1 : 0));
            sw.put(new JSONObject().put("id", String.valueOf(t)).put("menuName", nm).put("menuNameHighlight", nm)
                    .put("icon", drw("icon_noise_reduction_" + icon + "_off"))
                    .put("iconHighLight", drw("icon_noise_reduction_" + icon + "_on"))
                    .put("check", t == curType).put("deepLinks", link));
        }
        j.put("shortcuts", sc).put("switchMenuList", sw);
        return j;
    }

    static Object gson; static Method fj;
    static Object gsonFrom(String js, java.lang.reflect.Type t) throws Exception {
        if (fj == null) {
            Class<?> gsonC = cl.loadClass("com.google.gson.Gson");
            gson = gsonC.getConstructor().newInstance();
            for (Method m : gsonC.getMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == String.class && (pt[1] == Class.class || pt[1] == java.lang.reflect.Type.class) && m.getReturnType() == Object.class) { fj = m; break; }
            }
            if (fj == null) throw new NoSuchMethodException("Gson.fromJson(String,Class)");
        }
        return fj.invoke(gson, js, t);
    }

    static java.util.ArrayList<Object> batteryList() throws Exception {
        java.util.ArrayList<Object> l = new java.util.ArrayList<>();
        Class<?> bi = cl.loadClass("com.oplus.mydevices.sdk.device.BatteryInfo");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Enum> bt = (Class<Enum>) cl.loadClass("com.oplus.mydevices.sdk.device.BatteryType");
        String[] types = {"LEFT", "RIGHT", "BOX"};
        int[] vals = {batL, batR, batBox};
        boolean[] chg = {chgL, chgR, chgBox};
        for (int i = 0; i < 3; i++) {
            if (vals[i] < 0) continue;
            Object b = bi.getConstructor().newInstance();
            bi.getMethod("setBatteryType", bt).invoke(b, Enum.valueOf(bt, types[i]));
            bi.getMethod("setValue", int.class).invoke(b, vals[i]);
            bi.getMethod("setCharge", boolean.class).invoke(b, chg[i]);
            l.add(b);
        }
        return l;
    }

    /** op: "h" 插入/更新，"g" 删除。 */
    static void insert(JSONObject j, String op) throws Exception {
        Class<?> di = cl.loadClass("com.oplus.mydevices.sdk.device.DeviceInfo");
        Object info = gsonFrom("{}", di);
        Method fj = Hook.fj;
        for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
            String k = it.next();
            java.lang.reflect.Field f;
            try { f = di.getDeclaredField(k); } catch (NoSuchFieldException e) { log("no field " + k); continue; }
            f.setAccessible(true);
            Object raw = j.get(k);
            String js = raw instanceof String ? JSONObject.quote((String) raw) : String.valueOf(raw);
            f.set(info, fj.invoke(gson, js, f.getGenericType()));
        }
        java.util.ArrayList<Object> bl = batteryList();
        setField(info, "batteryInfoList", bl);
        // mBatteryList 元素类型不是 BatteryInfo，填了宿主解析失败；只填 batteryInfoList
        Class<?> mgr = cl.loadClass("com.oplus.mydevices.sdk.DeviceInfoManager");
        Method h = mgr.getDeclaredMethod(op, di);
        h.setAccessible(true);
        h.invoke(null, info);
        log((op.equals("g") ? "deleted " : "inserted ") + info);
    }
}

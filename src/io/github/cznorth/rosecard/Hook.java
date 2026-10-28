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
    static final int[] TYPES = {T_CLOSE, T_TRANS, T_WEAK, T_MID, T_STRONG};

    static final String ACTION_STATE = "io.github.cznorth.rosecard.STATE";
    static boolean isMain;
    static ClassLoader cl;
    static Context app;
    static volatile int curType = T_STRONG;
    static volatile String mac, name;

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
                if (isMain) h.postDelayed(() -> readState(Hook::refresh), 12000);
                else {
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
                        if (mac == null || !mac.equalsIgnoreCase(m)) return;
                        panel = new java.lang.ref.WeakReference<>(f);
                        new Handler(Looper.getMainLooper()).postDelayed(Hook::fillPanel, 300);
                        readState(() -> new Handler(Looper.getMainLooper()).post(Hook::fillPanel));
                    } catch (Throwable t) { log("onViewCreated " + Log.getStackTraceString(t)); }
                }
            });
        } catch (Throwable t) { log("hook OneSpace failed " + t); }
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
            log("panel filled, cur=" + curType);
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
        if (json == null || mac == null) return false;
        JSONObject j = new JSONObject(json);
        if (!mac.equalsIgnoreCase(j.optString("mac"))) return false;
        int type = j.optInt("type", -1);
        apply(type);
        return true;
    }

    static void apply(int type) {
        int mode, level = -1;           // Rose: mode 0 降噪 1 普通 2 风噪 3 通透；level 0 轻 1 中 2 深
        switch (type) {
            case T_CLOSE: mode = 1; break;
            case T_TRANS: mode = 3; break;
            case T_WEAK: mode = 0; level = 0; break;
            case T_MID: mode = 0; level = 1; break;
            case T_STRONG: mode = 0; level = 2; break;
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
            if (d.getAddress().toUpperCase().endsWith("CD:8D") || (n != null && n.contains("EarFeel"))) return d;
        }
        return null;
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
        return -1;   // 风噪等，卡片上不选中
    }

    /** 从耳机读模式与强度，更新 curType 后执行 then（BLE 线程）。 */
    static void readState(Runnable then) {
        RoseBle ble = RoseBle.get(app);
        ble.readMode((m, err) -> {
            if (err != null) { log("readMode err=" + err); then.run(); return; }
            ble.readLevel((lv, err2) -> {
                if (err2 == null) curType = typeOf(m, lv);
                log("readState mode=" + m + " level=" + lv + " -> " + curType);
                then.run();
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
            insert(build());
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
        JSONArray bat = new JSONArray();   // TODO: 读真实电量（01 01 通知）
        j.put("batteryInfoList", bat).put("mBatteryList", bat);

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
                default: label = "strong_reduction"; icon = "strong"; break;
            }
            String nm = str("melody_mydevices_noise_reduction_action_" + label);
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

    static void insert(JSONObject j) throws Exception {
        Class<?> di = cl.loadClass("com.oplus.mydevices.sdk.device.DeviceInfo");
        Class<?> gsonC = cl.loadClass("com.google.gson.Gson");
        Object gson = gsonC.getConstructor().newInstance();
        Method fj = null;
        for (Method m : gsonC.getMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 2 && pt[0] == String.class && (pt[1] == Class.class || pt[1] == java.lang.reflect.Type.class) && m.getReturnType() == Object.class) { fj = m; break; }
        }
        if (fj == null) throw new NoSuchMethodException("Gson.fromJson(String,Class)");
        Object info = fj.invoke(gson, "{}", di);
        for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
            String k = it.next();
            java.lang.reflect.Field f;
            try { f = di.getDeclaredField(k); } catch (NoSuchFieldException e) { log("no field " + k); continue; }
            f.setAccessible(true);
            Object raw = j.get(k);
            String js = raw instanceof String ? JSONObject.quote((String) raw) : String.valueOf(raw);
            f.set(info, fj.invoke(gson, js, f.getGenericType()));
        }
        Class<?> mgr = cl.loadClass("com.oplus.mydevices.sdk.DeviceInfoManager");
        StringBuilder ms = new StringBuilder();
        for (Method m : mgr.getDeclaredMethods())
            if (Modifier.isStatic(m.getModifiers())) ms.append(m.getName()).append(java.util.Arrays.toString(m.getParameterTypes())).append(' ');
        log("DeviceInfoManager statics: " + ms);
        Method h = mgr.getDeclaredMethod("h", di);
        h.setAccessible(true);
        h.invoke(null, info);
        log("inserted " + info);
    }
}

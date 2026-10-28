package io.github.cznorth.rosecard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Rose EarFeel-i5 BLE control protocol (reverse-engineered from RoseLink 3.8.0).
 *
 * Request:  08 ee 00 00 00 GRP CMD LEN_LO LEN_HI PAYLOAD... SUM
 * Response: 09 ff 00 00 ST  GRP CMD LEN_LO LEN_HI PAYLOAD... SUM
 *   LEN = whole frame length, SUM = sum of all previous bytes & 0xff, ST 01 = ok.
 *   Set command = read command | 0x80.
 * 06 02 / 06 82: noise mode, 4 bytes one-hot [降噪, 普通, 风噪, 通透]
 * 06 07 / 06 87: ANC level, 1 byte 00 轻度 / 01 中度 / 02 深度
 */
public final class RoseBle {
    static final String TAG = "RoseCard";
    static final String DEFAULT_ADDR = "D2:02:03:05:CD:8D";
    static final int H_WRITE = 0x000e, H_NOTIFY = 0x0010;
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final String[] LEVEL_NAMES = {"轻度", "中度", "深度"};
    public static final String[] MODE_NAMES = {"降噪", "普通", "风噪", "通透"};

    private static RoseBle inst;
    public static synchronized RoseBle get(Context c) {
        if (inst == null) inst = new RoseBle(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final Handler h;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic wr;
    private volatile boolean ready;
    private final ArrayBlockingQueue<Object> connQ = new ArrayBlockingQueue<>(4);
    private final ArrayBlockingQueue<byte[]> rxQ = new ArrayBlockingQueue<>(16);
    private final Runnable idleClose = this::close;

    private RoseBle(Context c) {
        ctx = c;
        HandlerThread t = new HandlerThread("rose-ble");
        t.start();
        h = new Handler(t.getLooper());
    }

    public interface Cb { void done(int value, String err); }

    /** Run op on BLE thread; cb on BLE thread. */
    public void readLevel(Cb cb) { run(() -> { byte[] p = txn(0x06, 0x07, null); return p[0] & 0xff; }, cb); }
    public void setLevel(int lv, Cb cb) { run(() -> { txn(0x06, 0x87, new byte[]{(byte) lv}); return lv; }, cb); }
    public void readMode(Cb cb) {
        run(() -> {
            byte[] p = txn(0x06, 0x02, null);
            for (int i = 0; i < 4 && i < p.length; i++) if (p[i] != 0) return i;
            return -1;
        }, cb);
    }
    public void setMode(int m, Cb cb) {
        run(() -> { byte[] p = new byte[4]; p[m] = 1; txn(0x06, 0x82, p); return m; }, cb);
    }

    interface Op { int call() throws Exception; }

    private void run(Op op, Cb cb) {
        h.post(() -> {
            h.removeCallbacks(idleClose);
            int v = -1; String err = null;
            try {
                ensureConnected();
                v = op.call();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w(TAG, "op failed", e);
                if (!ready) close();
            }
            h.postDelayed(idleClose, 20000);
            cb.done(v, err);
        });
    }

    private static byte[] frame(int grp, int cmd, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        int len = 10 + n;
        byte[] f = new byte[len];
        f[0] = 0x08; f[1] = (byte) 0xee;
        f[5] = (byte) grp; f[6] = (byte) cmd;
        f[7] = (byte) (len & 0xff); f[8] = (byte) (len >> 8);
        if (n > 0) System.arraycopy(payload, 0, f, 9, n);
        int s = 0;
        for (int i = 0; i < len - 1; i++) s += f[i] & 0xff;
        f[len - 1] = (byte) s;
        return f;
    }

    private byte[] txn(int grp, int cmd, byte[] payload) throws Exception {
        rxQ.clear();
        byte[] f = frame(grp, cmd, payload);
        Log.i(TAG, "tx " + hex(f));
        int r = gatt.writeCharacteristic(wr, f, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        if (r != BluetoothStatusCodes_SUCCESS) throw new Exception("写入失败(" + r + ")");
        long end = System.currentTimeMillis() + 2500;
        while (true) {
            long left = end - System.currentTimeMillis();
            byte[] rx = left > 0 ? rxQ.poll(left, TimeUnit.MILLISECONDS) : null;
            if (rx == null) throw new Exception("耳机无响应");
            Log.i(TAG, "rx " + hex(rx) + " (want " + Integer.toHexString(grp) + " " + Integer.toHexString(cmd) + ")");
            if (rx.length < 10 || (rx[0] & 0xff) != 0x09 || (rx[1] & 0xff) != 0xff) continue;
            int rc = rx[6] & 0xff;
            if ((rx[5] & 0xff) != grp || (rc != cmd && rc != (cmd & 0x7f))) continue;
            int st = rx[4] & 0xff;
            if (st != 1) throw new Exception(st == 6 ? "耳机拒绝（需取出充电仓）" : "耳机返回错误 " + st);
            int plen = ((rx[7] & 0xff) | ((rx[8] & 0xff) << 8)) - 10;
            if (plen < 0 || 9 + plen > rx.length) plen = Math.max(0, rx.length - 10);
            byte[] p = new byte[Math.max(plen, 1)];
            System.arraycopy(rx, 9, p, 0, plen);
            return p;
        }
    }

    private static final int BluetoothStatusCodes_SUCCESS = 0;

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private void ensureConnected() throws Exception {
        if (ready && gatt != null) return;
        close();
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) throw new Exception("蓝牙未开启");
        String addr = DEFAULT_ADDR;
        BluetoothDevice dev = ad.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        connQ.clear();
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        Object o = connQ.poll(8, TimeUnit.SECONDS);
        if (o == null) throw new Exception("连接耳机超时");
        if (o instanceof Exception) throw (Exception) o;
        ready = true;
    }

    private void close() {
        ready = false; wr = null;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            Log.i(TAG, "conn status=" + status + " state=" + state);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else {
                ready = false;
                connQ.offer(new Exception("连接断开(" + status + ")"));
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattCharacteristic w = null, n = null;
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    Log.i(TAG, "svc " + s.getUuid() + " chr " + c.getUuid() + " h=" + c.getInstanceId() + " p=" + c.getProperties());
                    if (c.getInstanceId() == H_WRITE) w = c;
                    if (c.getInstanceId() == H_NOTIFY) n = c;
                }
            }
            if (w == null || n == null) { connQ.offer(new Exception("找不到控制特征")); return; }
            wr = w;
            g.setCharacteristicNotification(n, true);
            BluetoothGattDescriptor d = n.getDescriptor(CCCD);
            if (d != null) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            else connQ.offer(Boolean.TRUE);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            connQ.offer(Boolean.TRUE);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (c.getInstanceId() == H_NOTIFY) rxQ.offer(v);
        }
    };
}

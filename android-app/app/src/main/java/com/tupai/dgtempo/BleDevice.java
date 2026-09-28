package com.tupai.dgtempo;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;

import java.util.ArrayDeque;

/**
 * One DG-Lab device over GATT. Writes go through a queue (Android allows one outstanding GATT op);
 * frames are written without response for lowest latency. Subclasses implement the per-device frames.
 */
@SuppressLint("MissingPermission")
public abstract class BleDevice {
    public interface Listener {
        void onLog(String msg);
        void onConnected(BleDevice d);
        void onDisconnected(BleDevice d);
    }

    public final String kind, label;
    public final BluetoothDevice device;
    public String name = "";
    protected final Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic wChar, nChar, battChar;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing = false;
    public volatile boolean connected = false;
    public volatile int battery = -1;
    public volatile int actualA = 0, actualB = 0;
    public volatile int strength = -1;       // last strength we commanded (-1 = force a write)
    public volatile long frames = 0, dropped = 0;

    protected BleDevice(String kind, String label, BluetoothDevice device, Listener listener) {
        this.kind = kind; this.label = label; this.device = device; this.listener = listener;
    }

    public String address() { return device.getAddress(); }

    public abstract int cap();
    public abstract int targetStrength(double x, int offset, Settings s);
    /** One 100 ms frame: per-slot frequency + intensity for A and B. */
    public abstract void sendFrame(int strength, boolean setStrength, int[] fa, int[] ia, int[] fb, int[] ib, Settings s);
    protected abstract void afterConnect(Settings s);
    protected abstract void onNotify(byte[] data);
    public boolean strengthChangeAllowed() { return true; }

    private Settings settings;
    private Context ctx;
    private int retries = 0;
    private volatile boolean userDisconnect = false;

    public void connect(Context ctx, Settings s) {
        this.ctx = ctx;
        settings = s;
        userDisconnect = false;
        listener.onLog(label + ": connecting " + device.getAddress());
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    static String gattStatus(int st) {
        switch (st) {
            case 0: return "ok";
            case 8: return "8 connection timeout";
            case 19: return "19 remote closed the link";
            case 22: return "22 local closed the link";
            case 34: return "34 LMP timeout";
            case 62: return "62 failed to establish (device busy / not advertising?)";
            case 133: return "133 GATT error (scan running, or device already connected elsewhere)";
            case 147: return "147 connection failed to establish";
            default: return String.valueOf(st);
        }
    }

    public void disconnect() {
        userDisconnect = true;
        BluetoothGatt g = gatt;
        if (g == null) return;
        if (connected) {
            try { sendFrame(0, true, new int[]{10, 10, 10, 10}, new int[4], new int[]{10, 10, 10, 10}, new int[4], settings); } catch (Exception ignored) {}
        }
        connected = false;
        new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            try { g.disconnect(); } catch (Exception ignored) {}
        }).start();
    }

    /** Queue a write. Old waveform frames are dropped if the queue backs up (keeps latency bounded). */
    protected void enqueue(byte[] data) {
        synchronized (queue) {
            if (queue.size() >= 6) {
                queue.removeIf(d -> (d[0] & 0xFF) == 0xB0);
                dropped++;
            }
            queue.addLast(data);
        }
        writeNext();
    }

    private void writeNext() {
        byte[] d;
        BluetoothGatt g = gatt;
        synchronized (queue) {
            if (writing || g == null || wChar == null) return;
            d = queue.pollFirst();
            if (d == null) return;
            writing = true;
        }
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = g.writeCharacteristic(wChar, d, writeType) == BluetoothStatusCodes.SUCCESS;
        } else {
            wChar.setWriteType(writeType);
            wChar.setValue(d);
            ok = g.writeCharacteristic(wChar);
        }
        if (!ok) {
            synchronized (queue) { writing = false; }
            listener.onLog(label + ": write rejected");
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean was = connected;
                connected = false;
                synchronized (queue) { queue.clear(); writing = false; }
                try { g.close(); } catch (Exception ignored) {}
                gatt = null; wChar = null;
                listener.onLog(label + ": disconnected (" + gattStatus(status) + ")");
                if (!was && !userDisconnect && retries < 3) {
                    retries++;
                    listener.onLog(label + ": retrying connect " + retries + "/3 ...");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!userDisconnect && gatt == null)
                            gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
                    }, 1200);
                    return;
                }
                listener.onDisconnected(BleDevice.this);
                if (!was && status != 0) listener.onLog(label + ": connect failed - power-cycle the box, make sure nothing else (app / laptop) holds it, then tap CONNECT");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(Protocol.SERVICE);
            if (svc == null) {
                listener.onLog(label + ": service 0x180C not found");
                g.disconnect();
                return;
            }
            wChar = svc.getCharacteristic(Protocol.CHAR_WRITE);
            nChar = svc.getCharacteristic(Protocol.CHAR_NOTIFY);
            BluetoothGattService bs = g.getService(Protocol.BATTERY_SERVICE);
            battChar = bs == null ? null : bs.getCharacteristic(Protocol.CHAR_BATTERY);
            if (wChar == null || nChar == null) {
                listener.onLog(label + ": characteristics missing");
                g.disconnect();
                return;
            }
            boolean noRsp = (wChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
            writeType = noRsp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            g.setCharacteristicNotification(nChar, true);
            BluetoothGattDescriptor cccd = nChar.getDescriptor(Protocol.CCCD);
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            } else {
                readBatteryOrFinish(g);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            readBatteryOrFinish(g);
        }

        private void readBatteryOrFinish(BluetoothGatt g) {
            if (battChar != null && g.readCharacteristic(battChar)) return;
            finishConnect();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (value != null && value.length > 0) battery = value[0] & 0xFF;
            finishConnect();
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) onCharacteristicRead(g, c, c.getValue(), status);
        }

        private void finishConnect() {
            retries = 0;
            connected = true;
            strength = -1;
            afterConnect(settings);
            listener.onLog(label + ": connected, battery " + (battery < 0 ? "?" : battery + "%")
                    + (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? ", write-no-rsp" : ", write-rsp"));
            listener.onConnected(BleDevice.this);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (queue) { writing = false; }
            frames++;
            writeNext();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(value);
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onNotify(c.getValue());
        }
    };
}

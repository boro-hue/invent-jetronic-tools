package io.github.borohue.mustangobd;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Bluetooth Low Energy ELM327 clones (Vgate iCar Pro BLE, Veepeak BLE+, many "iOS" adapters).
 * They expose a UART-like GATT service: a notify characteristic (adapter -> phone) and a write
 * characteristic (phone -> adapter).
 */
public class BleTransport extends Transport {
    static final String[][] KNOWN = {   // service, notify, write
            {"0000fff0-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb",
                    "0000fff2-0000-1000-8000-00805f9b34fb"},
            {"0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb",
                    "0000ffe1-0000-1000-8000-00805f9b34fb"},
            {"e7810a71-73ae-499d-8c15-faa9aef0c3f2", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
                    "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"}};
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final BluetoothDevice dev;
    private final String label;
    private final RxBuffer rx = new RxBuffer();
    private final Object lock = new Object();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic wchar;
    private boolean ready, writeDone;
    private String error;

    public BleTransport(Context ctx, BluetoothDevice dev, String label) {
        this.ctx = ctx.getApplicationContext();
        this.dev = dev;
        this.label = label;
    }

    @Override
    public String name() {
        return "BLE " + label + " [" + dev.getAddress() + "]";
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.discoverServices()) fail("service discovery did not start");
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("BLE link lost (status " + status + ")");
                }
            } catch (SecurityException e) {
                fail("Bluetooth permission missing");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            try {
                BluetoothGattCharacteristic notify = null, write = null;
                for (String[] k : KNOWN) {
                    BluetoothGattService svc = g.getService(UUID.fromString(k[0]));
                    if (svc != null) {
                        notify = svc.getCharacteristic(UUID.fromString(k[1]));
                        write = svc.getCharacteristic(UUID.fromString(k[2]));
                        if (notify != null && write != null) break;
                    }
                }
                if (notify == null || write == null) {          // generic fallback
                    notify = write = null;
                    for (BluetoothGattService svc : g.getServices()) {
                        String u = svc.getUuid().toString();
                        if (u.startsWith("00001800") || u.startsWith("00001801") || u.startsWith("0000180a"))
                            continue;
                        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                            int p = c.getProperties();
                            if (notify == null && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                    | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify = c;
                            if (write == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) write = c;
                        }
                    }
                }
                if (notify == null || write == null) {
                    fail("this BLE device has no OBD data channel (not an OBD adapter?)");
                    return;
                }
                wchar = write;
                wchar.setWriteType((write.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                g.setCharacteristicNotification(notify, true);
                BluetoothGattDescriptor d = notify.getDescriptor(CCCD);
                if (d == null) {
                    setReady();
                    return;
                }
                d.setValue((notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                if (!g.writeDescriptor(d)) setReady();
            } catch (SecurityException e) {
                fail("Bluetooth permission missing");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            setReady();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();                              // Android 12 and older
            if (v != null) rx.put(v, 0, v.length);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value != null) rx.put(value, 0, value.length);   // Android 13+
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (lock) {
                writeDone = true;
                lock.notifyAll();
            }
        }
    };

    private void setReady() {
        synchronized (lock) {
            ready = true;
            lock.notifyAll();
        }
    }

    private void fail(String why) {
        synchronized (lock) {
            error = why;
            lock.notifyAll();
        }
        rx.fail(new IOException(why));
    }

    @Override
    public void open() throws IOException {
        rx.reset();
        synchronized (lock) {
            ready = false;
            error = null;
        }
        try {
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission missing");
        }
        if (gatt == null) throw new IOException("BLE connect failed to start");
        long deadline = System.currentTimeMillis() + 20000;
        synchronized (lock) {
            while (!ready && error == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    close();
                    throw new IOException("BLE adapter " + label + " did not respond (powered? in range?)");
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    close();
                    throw new IOException("interrupted");
                }
            }
            if (error != null) {
                String e = error;
                close();
                throw new IOException(e);
            }
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (gatt == null || wchar == null) throw new IOException("BLE not connected");
        for (int i = 0; i < data.length; i += 20) {
            byte[] chunk = Arrays.copyOfRange(data, i, Math.min(data.length, i + 20));
            synchronized (lock) {
                if (error != null) throw new IOException(error);
                writeDone = false;
                boolean started = false;
                for (int tries = 0; tries < 20 && !started; tries++) {
                    try {
                        wchar.setValue(chunk);
                        started = gatt.writeCharacteristic(wchar);
                    } catch (SecurityException e) {
                        throw new IOException("Bluetooth permission missing");
                    }
                    if (!started) waitLock(10);       // previous GATT operation still busy
                }
                if (!started) throw new IOException("BLE write failed");
                long deadline = System.currentTimeMillis() + 2000;
                while (!writeDone && error == null && System.currentTimeMillis() < deadline) waitLock(20);
            }
        }
    }

    private void waitLock(long ms) throws IOException {
        try {
            lock.wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    @Override
    public byte[] readSome(int timeoutMs) throws IOException {
        return rx.take(timeoutMs);
    }

    @Override
    public void drain() {
        rx.clear();
    }

    @Override
    public void close() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (SecurityException ignored) {
            }
        }
        rx.fail(new IOException("closed"));
    }
}

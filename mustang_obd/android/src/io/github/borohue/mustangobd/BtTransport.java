package io.github.borohue.mustangobd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.util.UUID;

/** Classic Bluetooth (SPP / RFCOMM) ELM327 adapter - the common "OBDII" Bluetooth dongle. */
public class BtTransport extends Transport.StreamTransport {
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final BluetoothDevice dev;
    private final String label;
    private BluetoothSocket sock;

    public BtTransport(BluetoothDevice dev, String label) {
        this.dev = dev;
        this.label = label;
    }

    @Override
    public String name() {
        return "Bluetooth " + label + " [" + dev.getAddress() + "]";
    }

    @Override
    protected void connect() throws IOException {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null) ad.cancelDiscovery();   // discovery slows RFCOMM connects down
        } catch (SecurityException ignored) {
            // BLUETOOTH_SCAN not granted - harmless, we never start discovery
        }
        IOException first = null;
        // Clones differ: secure SPP, insecure SPP, then the classic channel-1 fallback.
        for (int attempt = 0; attempt < 3; attempt++) {
            BluetoothSocket s = null;
            try {
                if (attempt == 0) s = dev.createRfcommSocketToServiceRecord(SPP);
                else if (attempt == 1) s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                else s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                s.connect();
                sock = s;
                in = s.getInputStream();
                out = s.getOutputStream();
                return;
            } catch (SecurityException e) {
                throw new IOException("Bluetooth permission missing (" + e.getMessage() + ")");
            } catch (IOException e) {
                if (first == null) first = e;
                close(s);
            } catch (Exception e) {      // reflection fallback unavailable on this phone
                close(s);
            }
        }
        throw new IOException("cannot connect to " + label + ": "
                + (first != null ? first.getMessage() : "no socket") + " - adapter plugged in and powered? "
                + "no other app connected to it?");
    }

    private static void close(BluetoothSocket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void disconnect() {
        close(sock);
        sock = null;
    }
}

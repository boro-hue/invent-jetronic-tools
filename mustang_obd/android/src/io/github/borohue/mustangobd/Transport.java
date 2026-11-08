package io.github.borohue.mustangobd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** A byte link to an ELM327 adapter (Bluetooth, BLE, WiFi or the built-in simulator). */
public abstract class Transport {
    public abstract String name();

    public abstract void open() throws IOException;

    public abstract void write(byte[] data) throws IOException;

    /** Waits up to timeoutMs for data; returns what arrived (empty array on timeout). */
    public abstract byte[] readSome(int timeoutMs) throws IOException;

    public abstract void drain();

    public abstract void close();

    /** Shared receive buffer: filled by a reader thread or a callback, drained by readSome. */
    public static class RxBuffer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private IOException failure;

        public synchronized void put(byte[] b, int off, int len) {
            buf.write(b, off, len);
            notifyAll();
        }

        public synchronized void fail(IOException e) {
            if (failure == null) failure = e;
            notifyAll();
        }

        public synchronized void reset() {
            buf.reset();
            failure = null;
        }

        public synchronized void clear() {
            buf.reset();
        }

        public synchronized byte[] take(int timeoutMs) throws IOException {
            if (buf.size() == 0 && failure == null && timeoutMs > 0) {
                try {
                    wait(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted");
                }
            }
            if (buf.size() > 0) {
                byte[] r = buf.toByteArray();
                buf.reset();
                return r;
            }
            if (failure != null) throw failure;
            return new byte[0];
        }
    }

    /** Transport over a pair of blocking streams, with a reader thread (Bluetooth SPP, TCP). */
    public abstract static class StreamTransport extends Transport {
        protected InputStream in;
        protected OutputStream out;
        protected final RxBuffer rx = new RxBuffer();
        private volatile boolean closed;

        /** Establish the connection and assign {@link #in} / {@link #out}. */
        protected abstract void connect() throws IOException;

        protected abstract void disconnect();

        @Override
        public void open() throws IOException {
            closed = false;
            rx.reset();
            connect();
            final InputStream stream = in;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] b = new byte[1024];
                    try {
                        while (!closed) {
                            int n = stream.read(b);
                            if (n < 0) throw new IOException("adapter closed the connection");
                            rx.put(b, 0, n);
                        }
                    } catch (IOException e) {
                        rx.fail(closed ? new IOException("closed") : e);
                    }
                }
            }, "obd-reader");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (closed) throw new IOException("closed");
            out.write(data);
            out.flush();
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
            closed = true;
            rx.fail(new IOException("closed"));
            disconnect();
        }
    }

    /** WiFi adapters (usually 192.168.0.10:35000) and the test harness. */
    public static class Tcp extends StreamTransport {
        private final String host;
        private final int port;
        private final javax.net.SocketFactory factory;
        private Socket sock;

        public Tcp(String host, int port) {
            this(host, port, null);
        }

        /** factory: e.g. the WiFi network's socket factory, so traffic is not routed via mobile data */
        public Tcp(String host, int port, javax.net.SocketFactory factory) {
            this.host = host;
            this.port = port;
            this.factory = factory;
        }

        @Override
        public String name() {
            return "WiFi " + host + ":" + port;
        }

        @Override
        protected void connect() throws IOException {
            sock = factory != null ? factory.createSocket() : new Socket();
            sock.connect(new InetSocketAddress(host, port), 10000);
            sock.setTcpNoDelay(true);
            in = sock.getInputStream();
            out = sock.getOutputStream();
        }

        @Override
        protected void disconnect() {
            try {
                if (sock != null) sock.close();
            } catch (IOException ignored) {
            }
        }
    }
}

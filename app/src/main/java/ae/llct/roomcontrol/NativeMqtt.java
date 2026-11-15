package ae.llct.roomcontrol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal MQTT 3.1.1 client over plain TCP (port 1883) — no external libraries.
 * QoS 0 publish/subscribe, keep-alive pings, automatic reconnect every 3 s.
 * Events go to the Listener: connect, close, reconnect, error(msg), message(topic, payload).
 */
public class NativeMqtt {

    public interface Listener { void onEvent(String event, String a, String b); }

    private final Listener listener;
    private final Object writeLock = new Object();
    private volatile int gen = 0;                 // bumps on every connect/stop; old threads exit
    private volatile Socket sock;
    private volatile OutputStream out;
    private volatile boolean connected;
    private String host, user, pass, clientId;
    private int port;
    private int packetId = 1;

    public NativeMqtt(Listener l) { this.listener = l; }

    public boolean isConnected() { return connected; }

    public synchronized void connect(String host, int port, String user, String pass, String clientId) {
        stop();
        this.host = host; this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.clientId = (clientId == null || clientId.isEmpty()) ? "roomctl_" + System.currentTimeMillis() : clientId;
        final int g = ++gen;
        new Thread(() -> loop(g), "mqtt-" + g).start();
    }

    public synchronized void stop() {
        gen++;
        connected = false;
        closeSock();
    }

    public void disconnect() {
        try { if (connected) write(new byte[]{(byte) 0xE0, 0}); } catch (IOException ignored) { }
        stop();
    }

    public void publish(String topic, String payload) {
        if (!connected) { emit("error", "Not connected", ""); return; }
        try { write(packet(0x30, str(topic), payload.getBytes(StandardCharsets.UTF_8))); }
        catch (IOException e) { emit("error", msg(e), ""); closeSock(); }
    }

    public void subscribe(String topic) {
        if (!connected) return;
        int id;
        synchronized (this) { id = packetId++; if (packetId > 65000) packetId = 1; }
        try { write(packet(0x82, new byte[]{(byte) (id >> 8), (byte) id}, str(topic), new byte[]{0})); }
        catch (IOException e) { emit("error", msg(e), ""); closeSock(); }
    }

    /* ------------------------------------------------------------------ */

    private void loop(final int g) {
        boolean first = true;
        while (g == gen) {
            if (!first) emit("reconnect", "", "");
            first = false;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 8000);
                s.setSoTimeout(75000);
                s.setTcpNoDelay(true);
                if (g != gen) { s.close(); return; }
                sock = s;
                out = new BufferedOutputStream(s.getOutputStream());
                InputStream in = new BufferedInputStream(s.getInputStream());

                // CONNECT (clean session, keep-alive 60 s)
                boolean hasUser = !user.isEmpty(), hasPass = hasUser && !pass.isEmpty();
                int flags = 0x02 | (hasUser ? 0x80 : 0) | (hasPass ? 0x40 : 0);
                byte[] vh = {0, 4, 'M', 'Q', 'T', 'T', 4, (byte) flags, 0, 60};
                write(packet(0x10, vh, str(clientId),
                        hasUser ? str(user) : new byte[0], hasPass ? str(pass) : new byte[0]));

                // CONNACK
                int h = in.read();
                if (h < 0) throw new IOException("Broker closed the connection");
                byte[] b = readN(in, readLen(in));
                if ((h & 0xF0) != 0x20 || b.length < 2) throw new IOException("Unexpected reply from broker");
                if (b[1] != 0) throw new IOException(connackMsg(b[1]));

                connected = true;
                emit("connect", "", "");

                // keep-alive pinger
                final Socket mine = s;
                new Thread(() -> {
                    try {
                        while (g == gen && connected && sock == mine) {
                            Thread.sleep(25000);
                            if (g == gen && connected && sock == mine) write(new byte[]{(byte) 0xC0, 0});
                        }
                    } catch (Exception ignored) { }
                }, "mqtt-ping").start();

                // read loop
                while (g == gen) {
                    h = in.read();
                    if (h < 0) throw new IOException("Connection lost");
                    b = readN(in, readLen(in));
                    if ((h & 0xF0) == 0x30) {                       // PUBLISH
                        int qos = (h >> 1) & 3;
                        int tl = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                        String topic = new String(b, 2, tl, StandardCharsets.UTF_8);
                        int p = 2 + tl;
                        if (qos > 0) {
                            if (qos == 1) write(new byte[]{0x40, 2, b[p], b[p + 1]});   // PUBACK
                            p += 2;
                        }
                        emit("message", topic, new String(b, p, b.length - p, StandardCharsets.UTF_8));
                    }
                    // SUBACK, PINGRESP etc. need no action
                }
            } catch (Exception e) {
                if (g != gen) return;
                connected = false;
                closeSock();
                emit("error", msg(e), "");
                emit("close", "", "");
            }
            try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
        }
    }

    private void emit(String ev, String a, String b) {
        try { listener.onEvent(ev, a, b); } catch (Exception ignored) { }
    }

    private void write(byte[] p) throws IOException {
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) throw new IOException("Not connected");
            o.write(p);
            o.flush();
        }
    }

    private void closeSock() {
        Socket s = sock;
        sock = null; out = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        if (m.contains("ECONNREFUSED")) return "Connection refused (is the broker running?)";
        if (m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH")) return "Host unreachable (same Wi-Fi?)";
        if (m.toLowerCase().contains("timed out") || m.contains("timeout")) return "Timed out (same Wi-Fi?)";
        return m;
    }

    private static String connackMsg(int code) {
        switch (code) {
            case 1: return "Broker refused: protocol version";
            case 2: return "Broker refused: client id";
            case 3: return "Broker unavailable";
            case 4: return "Wrong username / password";
            case 5: return "Not authorized (check user/password)";
            default: return "Broker refused (" + code + ")";
        }
    }

    private static byte[] str(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] r = new byte[b.length + 2];
        r[0] = (byte) (b.length >> 8);
        r[1] = (byte) b.length;
        System.arraycopy(b, 0, r, 2, b.length);
        return r;
    }

    private static byte[] packet(int header, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] bb = body.toByteArray();
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(header);
        int len = bb.length;
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 0x80;
            p.write(d);
        } while (len > 0);
        p.write(bb, 0, bb.length);
        return p.toByteArray();
    }

    private static int readLen(InputStream in) throws IOException {
        int mult = 1, val = 0, d;
        do {
            d = in.read();
            if (d < 0) throw new IOException("Connection lost");
            val += (d & 127) * mult;
            mult *= 128;
        } while ((d & 128) != 0);
        return val;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("Connection lost");
            off += r;
        }
        return b;
    }
}

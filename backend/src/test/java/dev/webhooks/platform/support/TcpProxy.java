package dev.webhooks.platform.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A TCP forwarder that can simulate a database outage: {@link #cut()} closes every open connection and
 * stops accepting (connections are refused), {@link #restore()} listens again on the same port.
 */
public final class TcpProxy implements AutoCloseable {

    private final String targetHost;
    private final int targetPort;
    private final int port;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;

    public TcpProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.port = PlatformProcess.freePort();
        restore();
    }

    public int port() {
        return port;
    }

    public synchronized void restore() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        server = s;
        Thread.ofVirtual().start(() -> accept(s));
    }

    public synchronized void cut() throws IOException {
        server.close();
        for (Socket socket : open) {
            socket.close();
        }
        open.clear();
    }

    private void accept(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket client = s.accept();
                Socket upstream = new Socket(targetHost, targetPort);
                open.add(client);
                open.add(upstream);
                Thread.ofVirtual().start(() -> pump(client, upstream));
                Thread.ofVirtual().start(() -> pump(upstream, client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void pump(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException e) {
            // connection closed
        } finally {
            try {
                from.close();
                to.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    public void close() throws IOException {
        cut();
    }
}

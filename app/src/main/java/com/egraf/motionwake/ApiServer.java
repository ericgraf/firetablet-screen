/*
 * ApiServer — tiny local HTTP/1.1 control API for MotionWake.
 *
 * No library dependencies: one daemon accept thread + hand-rolled HTTP.
 * Designed for the Home Assistant integration (see ha/ in the repo) and
 * plain curl. The service lives for a long time, so the socket is re-bound
 * with backoff if the port is busy at start.
 *
 *   GET    /health                 liveness, {"ok":true}
 *   GET    /status                 state + settings JSON
 *   POST   /status                 toggle the monitor (rest_switch compat)
 *   GET    /settings               current settings only
 *   POST   /settings               {"idle_minutes":n,"threshold":f,"check_seconds":n}
 *                                  (subset ok, 400 with no recognized keys)
 *   POST   /start                  start the motion monitor
 *   POST   /stop                   stop the monitor (API stays up)
 *   POST   /shutdown               stop monitor + service (API goes away)
 *   POST   /wake                   wake the display now (+ resets idle timer)
 *   POST   /sleep                  sleep the display now
 *   POST   /update                 self-update: binary APK body; installs via
 *                                  PackageInstaller and self-restarts
 *   GET    /update/status          version + last OTA attempt/result
 *
 * Auth: `X-MotionWake-Token: <token>` header, or `?token=<token>` query param
 * (handy for plain curl). Token lives in the `motionwatch` prefs under
 * `api_token`; regenerate from the app UI ("New token"). 401 on mismatch.
 */
package com.egraf.motionwake;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

final class ApiServer {

    static final int DEFAULT_PORT = 8587;
    static final String TOKEN_HEADER = "X-MotionWake-Token";

    private static final String TAG = "MotionApi";
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    /** APK upload (/update) — release build is ~200 KB; bound well above any
     *  realistic build. */
    private static final int MAX_UPDATE_BYTES = 64 * 1024 * 1024;
    private static final int SO_TIMEOUT_MS = 6000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final MotionWakeService mService;
    private volatile boolean mRunning = false;
    private ServerSocket mServerSocket;

    ApiServer(MotionWakeService service) {
        mService = service;
    }

    synchronized void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        Thread t = new Thread(new AcceptLoop(), "motionwatch-api");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        mRunning = false;
        ServerSocket ss = mServerSocket;
        if (ss != null) {
            try {
                ss.close(); // unblocks a pending accept()
            } catch (Exception ignored) {
            }
        }
    }

    private final class AcceptLoop implements Runnable {
        @Override
        public void run() {
            while (mRunning) {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("0.0.0.0", DEFAULT_PORT), 8);
                    mServerSocket = ss;
                    Log.i(TAG, "HTTP API listening on 0.0.0.0:" + DEFAULT_PORT);
                    while (mRunning) {
                        Socket client = ss.accept();
                        handleClient(client);
                    }
                } catch (Exception e) {
                    if (mRunning) {
                        Log.w(TAG, "API accept loop: " + e);
                        try {
                            Thread.sleep(3000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } finally {
                    if (ss != null) {
                        try {
                            ss.close();
                        } catch (Exception ignored) {
                        }
                    }
                    mServerSocket = null;
                }
            }
        }
    }

    // ------------------------------------------------------------ request

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(SO_TIMEOUT_MS);
            InputStream in = client.getInputStream();

            // 1) request head, up to the blank line. The read may pull surplus
            //    bytes from a body sent in the same packet (typical curl) —
            //    those are kept and replayed as the start of the body below.
            byte[] headBytes = readUntilDoubleCrlf(in, MAX_HEADER_BYTES);
            int sep = headBytes != null ? findDoubleCrlf(headBytes) : -1;
            if (headBytes == null || sep < 0) {
                writeResponse(client, 400, "{\"ok\":false,\"error\":\"bad request\"}");
                return;
            }
            byte[] surplus = java.util.Arrays.copyOfRange(headBytes, sep + 4, headBytes.length);
            String[] lines = new String(headBytes, 0, sep + 4, "UTF-8").split("\r\n");
            if (lines.length == 0 || lines[0].length() < 3) {
                writeResponse(client, 400, "{\"ok\":false,\"error\":\"bad request line\"}");
                return;
            }
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) {
                writeResponse(client, 400, "{\"ok\":false,\"error\":\"bad request line\"}");
                return;
            }
            String method = requestLine[0];
            String path = requestLine[1];

            // 2) token: query param wins over header if both present
            int q = path.indexOf('?');
            String token = null;
            if (q >= 0) {
                for (String pair : path.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                        token = pair.substring(eq + 1);
                    }
                }
                path = path.substring(0, q);
            }
            int contentLength = 0;
            for (int li = 1; li < lines.length; li++) {
                int colon = lines[li].indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = lines[li].substring(0, colon).trim();
                String value = lines[li].substring(colon + 1).trim();
                if (TOKEN_HEADER.equalsIgnoreCase(name) && value.length() > 0) {
                    token = value;
                } else if ("Content-Length".equalsIgnoreCase(name)) {
                    try {
                        contentLength = Math.max(0, Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (!tokenEquals(token)) {
                Log.d(TAG, "401 unauthorized for " + method + " " + path);
                writeResponse(client, 401, "{\"ok\":false,\"error\":\"bad or missing token\"}");
                return;
            }

            // 3) body (already-bounded). Surplus bytes from step 1 count.
            byte[] body = new byte[0];
            if (contentLength > 0) {
                int maxBody = "/update".equals(path)
                        ? MAX_UPDATE_BYTES : MAX_BODY_BYTES;
                if (contentLength > maxBody) {
                    writeResponse(client, 413, "{\"ok\":false,\"error\":\"body too large\"}");
                    return;
                }
                body = new byte[contentLength];
                int copied = Math.min(surplus.length, contentLength);
                System.arraycopy(surplus, 0, body, 0, copied);
                int off = copied;
                while (off < contentLength) {
                    int r = in.read(body, off, contentLength - off);
                    if (r < 0) {
                        break;
                    }
                    off += r;
                }
                if (off < contentLength) {
                    writeResponse(client, 400, "{\"ok\":false,\"error\":\"incomplete body\"}");
                    return;
                }
            }

            // 4) dispatch
            String json = null;
            int status = 200;
            String bodyJson = new String(body, UTF8);
            boolean isGet = "GET".equals(method);
            boolean isPost = "POST".equals(method);
            switch (path) {
                case "/":
                case "/status":
                    if (isGet) {
                        json = mService.statusJson();
                    } else if (isPost) {
                        json = mService.apiToggle();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/health":
                    if (isGet) {
                        json = "{\"ok\":true}";
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/settings":
                    if (isGet) {
                        json = mService.settingsJson();
                    } else if (isPost) {
                        json = mService.apiSetSettings(bodyJson);
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/start":
                    if (isPost) {
                        json = mService.apiStart();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/stop":
                    if (isPost) {
                        json = mService.apiStop();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/shutdown":
                    if (isPost) {
                        json = mService.apiShutdown();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/wake":
                    if (isPost) {
                        json = mService.apiWake();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/sleep":
                    if (isPost) {
                        json = mService.apiSleep();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/browser":
                    if (isGet) {
                        json = mService.browserJson();
                    } else if (isPost) {
                        json = mService.apiBrowser(bodyJson);
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/browser/reload":
                    if (isPost) {
                        json = mService.apiBrowserReload();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/update":
                    if (isPost) {
                        json = mService.apiUpdate(body);
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/update/status":
                    if (isGet) {
                        json = mService.updateStatusJson();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                case "/update/diag":
                    if (isGet) {
                        json = mService.diagJson();
                    } else {
                        status = 405;
                        json = methodNotAllowed(method);
                    }
                    break;
                default:
                    status = 404;
                    json = "{\"ok\":false,\"error\":\"not found: " + path + "\"}";
            }
            if (status == 200) {
                Log.d(TAG, method + " " + path + " -> ok");
            }
            writeResponse(client, status, json);
        } catch (Exception e) {
            Log.w(TAG, "client error: " + e);
            writeResponse(client, 500, "{\"ok\":false,\"error\":\"server error\"}");
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String methodNotAllowed(String method) {
        return "{\"ok\":false,\"error\":\"" + method + " not allowed here (try GET or POST)\"}";
    }

    private boolean tokenEquals(String candidate) {
        String expected = mService.getApiToken();
        if (expected == null || expected.length() == 0) {
            return false;
        }
        if (candidate == null) {
            return false;
        }
        // constant-time-ish compare (token is hex, sizes equal)
        if (candidate.length() != expected.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ candidate.charAt(i);
        }
        return diff == 0;
    }

    /** Reads until the blank line (or the limit); returns all bytes read, or null. */
    private static byte[] readUntilDoubleCrlf(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] scratch = new byte[1024];
        int total = 0;
        while (total < limit) {
            int r = in.read(scratch, 0, Math.min(scratch.length, limit - total));
            if (r < 0) {
                break;
            }
            buf.write(scratch, 0, r);
            total += r;
            byte[] cur = buf.toByteArray();
            if (findDoubleCrlf(cur) >= 0) {
                return cur;
            }
        }
        byte[] cur = buf.toByteArray();
        return findDoubleCrlf(cur) >= 0 ? cur : null;
    }

    /** Start index of "\r\n\r\n" in data, or -1. */
    private static int findDoubleCrlf(byte[] data) {
        for (int i = 0; i + 4 <= data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static void writeResponse(Socket client, int status, String json) {
        String statusText;
        switch (status) {
            case 200:
                statusText = "OK";
                break;
            case 400:
                statusText = "Bad Request";
                break;
            case 401:
                statusText = "Unauthorized";
                break;
            case 413:
                statusText = "Payload Too Large";
                break;
            case 404:
                statusText = "Not Found";
                break;
            case 405:
                statusText = "Method Not Allowed";
                break;
            default:
                statusText = "Internal Server Error";
        }
        byte[] body = json.getBytes(UTF8);
        String head = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        try {
            OutputStream out = client.getOutputStream();
            out.write(head.getBytes(UTF8));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {
        }
    }
}

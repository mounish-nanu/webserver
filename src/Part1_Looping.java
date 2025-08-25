// Part1_Looping.java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

public class Part1_Looping {

    // ---- Tunables / limits ----
    static final int PORT = 8080;
    static final Path DOCROOT = Paths.get("www").toAbsolutePath().normalize();
    static final int THREADS = 8;

    static final int MAX_HEADER_BYTES = 16 * 1024;     // 16KB
    static final int MAX_HEADER_LINES = 200;           // safety cap
    static final int MAX_URL_LENGTH   = 2048;          // safety cap
    static final int MAX_BODY_BYTES   = 1_000_000;     // 1MB simple cap

    // Keep-Alive
    static final int KEEPALIVE_TIMEOUT_MS = 5000;      // idle timeout
    static final int KEEPALIVE_MAX_REQS   = 100;       // per connection

    // Small gzip (optional, safe default off for big files)
    static final boolean ENABLE_GZIP = true;
    static final long GZIP_UP_TO_BYTES = 512 * 1024;   // only gzip files <= 512KB

    // Metrics
    static final AtomicLong TOTAL = new AtomicLong();

    public static void main(String[] args) throws Exception {
        System.out.println("Listening on http://localhost:" + PORT);
        System.out.println("Serving " + DOCROOT + " on http://localhost:" + PORT);

        try (ServerSocket server = new ServerSocket(PORT)) {
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);

            while (true) {
                Socket s = server.accept();
                pool.execute(() -> handleConnection(s));
            }
        }
    }

    private static void handleConnection(Socket sock) {
        long remoteId = System.nanoTime(); // unique-ish tag for this connection
        try (Socket s = sock) {
            s.setSoTimeout(KEEPALIVE_TIMEOUT_MS); // idle timeout for keep-alive
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            int handledOnConn = 0;

            while (handledOnConn < KEEPALIVE_MAX_REQS) {
                long t0 = System.nanoTime();

                // ---- Read & parse headers (until CRLFCRLF) ----
                byte[] headBytes;
                try {
                    headBytes = readHeaders(in, MAX_HEADER_BYTES);
                } catch (SocketTimeoutException idle) {
                    // idle during keep-alive -> close
                    break;
                }
                if (headBytes == null) {
                    // EOF or too big before a request arrived -> close
                    break;
                }
                String head = new String(headBytes, StandardCharsets.US_ASCII);

                // First line (request line)
                int firstCRLF = head.indexOf("\r\n");
                if (firstCRLF < 0) {
                    sendSimple(out, 400, "Bad Request", "no request line", false, true, 0);
                    logDone(t0, 400, 0);
                    break; // protocol error -> close
                }
                String requestLine = head.substring(0, firstCRLF);
                // Basic request-line length guard
                if (requestLine.length() > MAX_URL_LENGTH + 20) {
                    sendSimple(out, 414, "URI Too Long", "request line too long", false, true, 0);
                    logDone(t0, 414, 0);
                    break;
                }

                String[] parts = requestLine.split(" ", 3);
                if (parts.length != 3) {
                    sendSimple(out, 400, "Bad Request", "bad request line", false, true, 0);
                    logDone(t0, 400, 0);
                    break;
                }
                String method  = parts[0];
                String target  = parts[1];
                String version = parts[2];

                // Parse header lines into case-insensitive map + count
                Map<String, String> headers = new LinkedHashMap<>();
                int lines = 0;
                int idx = firstCRLF + 2;
                while (idx < head.length()) {
                    int next = head.indexOf("\r\n", idx);
                    if (next == -1 || next == idx) break; // blank line = end
                    String line = head.substring(idx, next);
                    lines++;
                    if (lines > MAX_HEADER_LINES) {
                        sendSimple(out, 431, "Request Header Fields Too Large", "too many headers", false, true, 0);
                        logDone(t0, 431, 0);
                        return;
                    }
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String v = line.substring(colon + 1).trim();
                        headers.put(k, v);
                    }
                    idx = next + 2;
                }

                // Log succinct request line
                // (Example: "→ GET /index.html HTTP/1.1 from /127.0.0.1:54321")
                System.out.println("→ " + method + " " + target + " " + version +
                        " from " + s.getRemoteSocketAddress());

                // ---- Minimal validation ----
                if (!"HTTP/1.1".equalsIgnoreCase(version)) {
                    sendSimple(out, 400, "Bad Request", "only HTTP/1.1 supported", false, true, 0);
                    logDone(t0, 400, 0);
                    break;
                }

                // ---- Determine keep-alive for this response ----
                // HTTP/1.1 default is keep-alive unless client says "Connection: close".
                boolean clientWantsClose = "close".equalsIgnoreCase(headers.getOrDefault("connection", ""));
                boolean keepAlive = !clientWantsClose && (handledOnConn + 1 < KEEPALIVE_MAX_REQS);

                // ---- Routing ----
                String pathOnly = target.split("\\?", 2)[0];

                int status = 500;       // safe default so it's always initialized
                long bytesSent = 0;     // default until we actually send something

                try {
                    // APIs first
                    if ("/api/time".equals(pathOnly) && "GET".equals(method)) {
                        String json = "{\"epochMs\":" + System.currentTimeMillis() + "}";
                        byte[] body = json.getBytes(StandardCharsets.UTF_8);
                        Map<String, String> h = baseHeaders(keepAlive);
                        h.put("Content-Type", "application/json; charset=utf-8");
                        writeResponseWithBody(out, 200, "OK", h, body, false);
                        status = 200;
                        bytesSent = body.length;
                    } else if ("/api/echo".equals(pathOnly) && "POST".equals(method)) {
                        String cl = headers.get("content-length");
                        if (cl == null) {
                            sendSimple(out, 411, "Length Required", "missing Content-Length", false, keepAlive, 0);
                            status = 411; bytesSent = 0;
                        } else {
                            int bodyLen;
                            try { bodyLen = Integer.parseInt(cl); }
                            catch (NumberFormatException e) {
                                sendSimple(out, 400, "Bad Request", "bad Content-Length", false, keepAlive, 0);
                                status = 400; bytesSent = 0;
                                bodyLen = -1;
                            }
                            if (bodyLen >= 0) {
                                if (bodyLen > MAX_BODY_BYTES) {
                                    sendSimple(out, 413, "Payload Too Large", "body too big", false, keepAlive, 0);
                                    status = 413; bytesSent = 0;
                                } else {
                                    byte[] bodyIn = readN(in, bodyLen);
                                    String ctype = headers.getOrDefault("content-type", "application/octet-stream");
                                    Map<String, String> h = baseHeaders(keepAlive);
                                    h.put("Content-Type", ctype);
                                    writeResponseWithBody(out, 200, "OK", h, bodyIn, false);
                                    status = 200; bytesSent = bodyIn.length;
                                }
                            }
                        }
                    } else if (pathOnly.startsWith("/api/")) {
                        // Wrong method or unknown API
                        sendSimple(out, 405, "Method Not Allowed", "unsupported API route/method", false, keepAlive, 0);
                        status = 405; bytesSent = 0;
                    } else {
                        // ---- Static files ----
                        boolean isHead = "HEAD".equals(method);
                        if (!"GET".equals(method) && !isHead) {
                            sendSimple(out, 405, "Method Not Allowed", "only GET/HEAD for static files", false, keepAlive, 0);
                            status = 405; bytesSent = 0;
                        } else {
                            if (pathOnly.length() > MAX_URL_LENGTH) {
                                sendSimple(out, 414, "URI Too Long", "path too long", false, keepAlive, 0);
                                status = 414; bytesSent = 0;
                            } else {
                                if ("/".equals(pathOnly)) pathOnly = "/index.html";
                                Path file = safeResolve(DOCROOT, pathOnly);
                                if (file == null || !Files.isRegularFile(file)) {
                                    sendSimple(out, 404, "Not Found", "no such file", false, keepAlive, 0);
                                    status = 404; bytesSent = 0;
                                } else {
                                    // Caching: Last-Modified + weak ETag
                                    long size = Files.size(file);
                                    long mtime = Files.getLastModifiedTime(file).toMillis();
                                    String lastMod = httpDate(Instant.ofEpochMilli(mtime));
                                    String etag = "W/\"" + size + "-" + mtime + "\"";

                                    // 304 checks
                                    String ifNoneMatch = headers.get("if-none-match");
                                    String ifModSince = headers.get("if-modified-since");
                                    boolean notModified = false;
                                    if (ifNoneMatch != null && ifNoneMatch.trim().equals(etag)) {
                                        notModified = true;
                                    } else if (ifModSince != null) {
                                        try {
                                            Instant ims = ZonedDateTime.parse(ifModSince, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                                            if (mtime / 1000 <= ims.toEpochMilli() / 1000) {
                                                notModified = true;
                                            }
                                        } catch (Exception ignore) { /* ignore parse errors */ }
                                    }

                                    Map<String, String> h = baseHeaders(keepAlive);
                                    h.put("Content-Type", guessMime(file));
                                    h.put("Last-Modified", lastMod);
                                    h.put("ETag", etag);
                                    // static content cache hint (tweak as you like)
                                    h.put("Cache-Control", "public, max-age=60");

                                    if (notModified) {
                                        writeResponseWithBody(out, 304, "Not Modified", h, new byte[0], true);
                                        status = 304; bytesSent = 0;
                                    } else {
                                        // Optional gzip for small texty files
                                        boolean wantsGzip = ENABLE_GZIP && clientAcceptsGzip(headers) &&
                                                isCompressible(h.get("Content-Type")) &&
                                                size <= GZIP_UP_TO_BYTES;

                                        if (isHead) {
                                            // HEAD: send only headers + Content-Length of uncompressed size
                                            h.put("Content-Length", String.valueOf(size));
                                            writeHeadOnly(out, 200, "OK", h);
                                            status = 200; bytesSent = 0;
                                        } else if (wantsGzip) {
                                            byte[] raw = Files.readAllBytes(file);
                                            byte[] gz = gzip(raw);
                                            h.put("Content-Encoding", "gzip");
                                            h.put("Vary", "Accept-Encoding");
                                            writeResponseWithBody(out, 200, "OK", h, gz, false);
                                            status = 200; bytesSent = gz.length;
                                        } else {
                                            h.put("Content-Length", String.valueOf(size));
                                            writeHeadOnly(out, 200, "OK", h);
                                            try (InputStream fileIn = Files.newInputStream(file)) {
                                                fileIn.transferTo(out);
                                            }
                                            status = 200; bytesSent = size;
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    // Unexpected per-request error
                    try {
                        sendSimple(out, 500, "Internal Server Error", "unexpected error", false, false, 0);
                    } catch (IOException ignored) {}
                    status = 500;
                    bytesSent = 0;
                    logDone(t0, status, bytesSent);
                    break; // close on server error
                }

                out.flush();
                logDone(t0, status, bytesSent);
                handledOnConn++;
                TOTAL.incrementAndGet();

                if (!keepAlive) {
                    break; // respect Connection: close or max-reqs
                }
            }
        } catch (IOException e) {
            System.out.println("Conn error: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    // Read until first CRLFCRLF (end of headers). Returns header bytes incl. CRLFCRLF,
    // or null on EOF/limit. Throws SocketTimeoutException on idle timeout.
    private static byte[] readHeaders(InputStream in, int capBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int count = 0;
        int b0 = -1, b1 = -1, b2 = -1, b3 = -1;
        while (true) {
            int ch = in.read();                 // blocks until a byte or timeout
            if (ch == -1) return null;          // EOF before any header
            buf.write(ch);
            count++;
            if (count > capBytes) return null;  // too big
            b0 = b1; b1 = b2; b2 = b3; b3 = ch;
            if (b0 == '\r' && b1 == '\n' && b2 == '\r' && b3 == '\n') {
                return buf.toByteArray();
            }
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int k = in.read(buf, off, n - off);
            if (k == -1) throw new EOFException("client closed during body");
            off += k;
        }
        return buf;
    }

    private static Map<String, String> baseHeaders(boolean keepAlive) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Date", httpDate(Instant.now()));
        h.put("Server", "TinyHTTP-Java");
        h.put("Connection", keepAlive ? "keep-alive" : "close");
        if (keepAlive) {
            h.put("Keep-Alive", "timeout=" + (KEEPALIVE_TIMEOUT_MS / 1000) + ", max=" + KEEPALIVE_MAX_REQS);
        }
        return h;
    }

    private static void writeHeadOnly(OutputStream out, int code, String text, Map<String, String> headers) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(text).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeResponseWithBody(OutputStream out, int code, String text,
                                              Map<String, String> headers, byte[] body, boolean noLength) throws IOException {
        if (!noLength) {
            headers.put("Content-Length", String.valueOf(body.length));
        }
        writeHeadOnly(out, code, text, headers);
        if (body.length > 0) out.write(body);
    }

    private static void sendSimple(OutputStream out, int code, String text, String msg,
                                   boolean isHead, boolean keepAlive, int contentLengthOverride) throws IOException {
        String html = "<h1>" + code + " " + text + "</h1><p>" + escape(msg) + "</p>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = baseHeaders(keepAlive);
        h.put("Content-Type", "text/html; charset=utf-8");
        if (contentLengthOverride > 0) {
            h.put("Content-Length", String.valueOf(contentLengthOverride));
        } else {
            h.put("Content-Length", String.valueOf(isHead ? 0 : body.length));
        }
        writeHeadOnly(out, code, text, h);
        if (!isHead) out.write(body);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Path safeResolve(Path docroot, String urlPath) {
        String decoded = urlDecode(urlPath);
        Path p = docroot.resolve("." + decoded).normalize();
        return p.startsWith(docroot) ? p : null;
    }

    private static String urlDecode(String s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = hex(s.charAt(i + 1)), lo = hex(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) { bos.write((hi << 4) + lo); i += 2; continue; }
            }
            bos.write((byte) c);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if ('0' <= c && c <= '9') return c - '0';
        if ('a' <= c && c <= 'f') return 10 + (c - 'a');
        if ('A' <= c && c <= 'F') return 10 + (c - 'A');
        return -1;
    }

    private static String guessMime(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif"))  return "image/gif";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".txt"))  return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static String httpDate(Instant t) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(t, ZoneOffset.UTC));
    }

    private static boolean clientAcceptsGzip(Map<String, String> headers) {
        String ae = headers.get("accept-encoding");
        return ae != null && ae.toLowerCase(Locale.ROOT).contains("gzip");
    }

    private static boolean isCompressible(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.startsWith("text/") || ct.contains("json") || ct.contains("javascript") || ct.contains("svg+xml");
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(128, raw.length / 2));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        return bos.toByteArray();
    }

    private static void logDone(long t0, int status, long bytes) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long total = TOTAL.get();
        System.out.println("Done " + status + " in " + ms + "ms   bytes=" + bytes + "   total=" + total);
    }
}

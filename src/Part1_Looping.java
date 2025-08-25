// Part1_Looping.java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.*;


public class Part1_Looping {
    public static void main(String[] args) throws Exception {
        int port = 8080;
        int maxCons = 2;              // stop after handling 2 connections
        int handled = 0;

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Listening on http://localhost:" + port);
            Path docroot = Paths.get("www").toAbsolutePath().normalize();
            System.out.println("Serving " + docroot + " on http://localhost:" + port);
            while (handled < maxCons) {
                try (Socket sock = server.accept()) {
                    System.out.println("Client: " + sock.getRemoteSocketAddress());
                    sock.setSoTimeout(5000);

                    InputStream in = sock.getInputStream();
                    OutputStream out = sock.getOutputStream();

                    // 1) Read headers fully (until \r\n\r\n), cap at 16KB
                    byte[] headBytes = readHeaders(in, 16 * 1024);
                    if (headBytes == null) { // EOF/timeout/too large
                        sendSimple(out, 400, "Bad Request", "incomplete or too large headers");
                        handled++;            // count this connection
                        continue;             // go accept the next one
                    }
                    String head = new String(headBytes, StandardCharsets.US_ASCII);

                    // 2) Parse request line
                    int firstCRLF = head.indexOf("\r\n");
                    if (firstCRLF < 0) {
                        sendSimple(out, 400, "Bad Request", "no request line");
                        handled++; continue;
                    }
                    String requestLine = head.substring(0, firstCRLF);
                    String[] parts = requestLine.split(" ", 3);
                    if (parts.length != 3) {
                        sendSimple(out, 400, "Bad Request", "bad request line");
                        handled++; continue;
                    }
                    String method  = parts[0];  // e.g., GET
                    String target  = parts[1];  // e.g., /hello?x=1
                    String version = parts[2];  // e.g., HTTP/1.1

                    // 3) Parse headers into a case-insensitive map
                    Map<String,String> headers = new LinkedHashMap<>();
                    int idx = firstCRLF + 2;
                    while (idx < head.length()) {
                        int next = head.indexOf("\r\n", idx);
                        if (next == -1 || next == idx) break; // blank line
                        String line = head.substring(idx, next);
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                            String v = line.substring(colon + 1).trim();
                            headers.put(k, v);
                        }
                        idx = next + 2;
                    }

                    // 4) Minimal validation
                    if (!"HTTP/1.1".equalsIgnoreCase(version)) {
                        sendSimple(out, 400, "Bad Request", "only HTTP/1.1 supported");
                        handled++; continue;
                    }
                    if (!"GET".equals(method)) {
                        sendSimple(out, 405, "Method Not Allowed", "only GET for now");
                        handled++; continue;
                    }

                    // 5) Echo back what we parsed
                    // 5) Static file routing
// Strip query string, map "/" to "/index.html"
                    String pathPart = target.split("\\?", 2)[0];
                    if (pathPart.equals("/")) pathPart = "/index.html";

// Resolve safely under docroot; block ../ traversal
                    Path file = safeResolve(docroot, pathPart);
                    if (file == null || !Files.isRegularFile(file)) {
                        sendSimple(out, 404, "Not Found", "no such file");
                        handled++;    // if you keep a counter
                        continue;     // go accept next connection
                    }

// Send 200 with correct Content-Type and Content-Length
                    String contentType = guessMime(file);
                    long len = Files.size(file);
                    String resp =
                            "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: " + len + "\r\n" +
                                    "Content-Type: " + contentType + "\r\n" +
                                    "Connection: close\r\n" +
                                    "\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));

// Stream the file body
                    try (InputStream fileIn = Files.newInputStream(file)) {
                        fileIn.transferTo(out); // Java 9+
                    }
                    out.flush();

                    handled++; // if you’re counting connections

                } catch (IOException e) {
                    System.out.println("Conn error: " + e.getMessage());
                    // optionally handled++ here if you want to count errored conns
                }
            }
        }
    }

    // --- helpers ---

    // Read until \r\n\r\n (end of headers), or return null on EOF/timeout/too big
    private static byte[] readHeaders(InputStream in, int capBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int total = 0;
        while (true) {
            int n = in.read(tmp);           // blocks until some bytes or EOF/timeout
            if (n == -1) return null;       // client closed early
            buf.write(tmp, 0, n);
            total += n;
            if (total > capBytes) return null;
            if (endsWithCRLFCRLF(buf)) return buf.toByteArray();
        }
    }

    private static boolean endsWithCRLFCRLF(ByteArrayOutputStream buf) {
        byte[] b = buf.toByteArray();
        int n = b.length;
        return n >= 4 && b[n-4]=='\r' && b[n-3]=='\n' && b[n-2]=='\r' && b[n-1]=='\n';
    }

    // Minimal HTML error/utility responder
    private static void sendSimple(OutputStream out, int code, String text, String msg) throws IOException {
        byte[] body = ("<h1>" + code + " " + text + "</h1><p>" + escape(msg) + "</p>")
                .getBytes(StandardCharsets.UTF_8);
        String h =
                "HTTP/1.1 " + code + " " + text + "\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String escape(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
    // Prevent path traversal; return null if resolved path escapes docroot
    private static Path safeResolve(Path docroot, String urlPath) {
        String decoded = urlDecode(urlPath);              // handle %20 etc.
        Path p = docroot.resolve("." + decoded).normalize();
        return p.startsWith(docroot) ? p : null;
    }

    // Minimal percent-decoder (ASCII)
    private static String urlDecode(String s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = hex(s.charAt(i+1)), lo = hex(s.charAt(i+2));
                if (hi >= 0 && lo >= 0) { bos.write((hi << 4) + lo); i += 2; continue; }
            }
            // For path decoding it’s okay to pass other characters through unchanged
            bos.write((byte)c);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
    private static int hex(char c) {
        if ('0' <= c && c <= '9') return c - '0';
        if ('a' <= c && c <= 'f') return 10 + (c - 'a');
        if ('A' <= c && c <= 'F') return 10 + (c - 'A');
        return -1;
    }

    // Tiny MIME map (expand as needed)
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
        // Fallback (you could also try Files.probeContentType(p))
        return "application/octet-stream";
    }

}

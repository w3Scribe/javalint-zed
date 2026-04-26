import com.puppycrawl.tools.checkstyle.*;
import com.puppycrawl.tools.checkstyle.api.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * checkstyle-lsp-shim
 * --------------------
 * A minimal Language Server Protocol (LSP) server that wraps Checkstyle
 * and speaks JSON-RPC over stdin/stdout so Zed can consume diagnostics.
 *
 * Compile:
 *   javac -cp checkstyle-all.jar CheckstyleLspShim.java
 *   jar cfm checkstyle-lsp-shim.jar MANIFEST.MF CheckstyleLspShim*.class
 *
 * MANIFEST.MF contents:
 *   Main-Class: CheckstyleLspShim
 *   Class-Path: checkstyle.jar
 */
public class CheckstyleLspShim {

    // ── LSP message framing ────────────────────────────────────────────────
    private static final PrintStream OUT = System.out;
    private static final BufferedReader IN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    // ── state ──────────────────────────────────────────────────────────────
    private static String checkstyleJar;
    private static String configPath;
    private static final Map<String, String> openFiles = new HashMap<>();

    public static void main(String[] args) throws Exception {
        // parse CLI flags: --checkstyle-jar <path> --config <path>
        for (int i = 0; i < args.length - 1; i++) {
            if ("--checkstyle-jar".equals(args[i])) checkstyleJar = args[i + 1];
            if ("--config".equals(args[i]))         configPath     = args[i + 1];
        }

        // Redirect stderr so it doesn't pollute stdout LSP stream
        System.setErr(new PrintStream(new FileOutputStream("java-lint.log"), true, StandardCharsets.UTF_8));

        // ── main LSP loop ──────────────────────────────────────────────────
        while (true) {
            String message = readMessage();
            if (message == null) break;
            handleMessage(message);
        }
    }

    // ── LSP framing ────────────────────────────────────────────────────────

    private static String readMessage() throws IOException {
        int contentLength = -1;
        String line;
        while ((line = IN.readLine()) != null) {
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring(16).trim());
            } else if (line.isEmpty() && contentLength > 0) {
                char[] buf = new char[contentLength];
                IN.read(buf, 0, contentLength);
                return new String(buf);
            }
        }
        return null;
    }

    private static void sendMessage(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        OUT.print("Content-Length: " + bytes.length + "\r\n\r\n");
        OUT.print(json);
        OUT.flush();
    }

    // ── LSP message dispatch ───────────────────────────────────────────────

    private static void handleMessage(String raw) {
        String method  = extract(raw, "\"method\"");
        String id      = extractId(raw);
        String uri     = extract(raw, "\"uri\"");
        String text    = extractText(raw);

        if (method == null) return;

        switch (method) {
            case "initialize" -> sendMessage(initializeResponse(id));
            case "initialized" -> { /* nothing */ }
            case "shutdown"   -> sendMessage(nullResponse(id));
            case "exit"       -> System.exit(0);

            case "textDocument/didOpen" -> {
                if (uri != null && text != null) {
                    openFiles.put(uri, text);
                    publishDiagnostics(uri, text);
                }
            }
            case "textDocument/didChange" -> {
                if (uri != null && text != null) {
                    openFiles.put(uri, text);
                    publishDiagnostics(uri, text);
                }
            }
            case "textDocument/didSave" -> {
                if (uri != null) {
                    String saved = openFiles.getOrDefault(uri, "");
                    publishDiagnostics(uri, saved);
                }
            }
            case "textDocument/didClose" -> {
                if (uri != null) openFiles.remove(uri);
            }
        }
    }

    // ── Checkstyle integration ─────────────────────────────────────────────

    private static void publishDiagnostics(String uri, String source) {
        List<String> diags = new ArrayList<>();
        try {
            // Write source to a temp file so Checkstyle can read it
            Path tmp = Files.createTempFile("java-lint-", ".java");
            Files.writeString(tmp, source, StandardCharsets.UTF_8);

            // Run Checkstyle programmatically
            Properties props = new Properties();
            Configuration config = ConfigurationLoader.loadConfiguration(
                    configPath,
                    new PropertiesExpander(props)
            );

            RootModule checker = new Checker();
            checker.setModuleClassLoader(ClassLoader.getSystemClassLoader());
            checker.configure(config);

            List<AuditEvent> events = new ArrayList<>();
            checker.addListener(new AuditListener() {
                public void auditStarted(AuditEvent e) {}
                public void auditFinished(AuditEvent e) {}
                public void fileStarted(AuditEvent e) {}
                public void fileFinished(AuditEvent e) {}
                public void addError(AuditEvent e) { events.add(e); }
                public void addException(AuditEvent e, Throwable t) {}
            });

            checker.process(List.of(tmp.toFile()));
            checker.destroy();
            Files.delete(tmp);

            // Convert each AuditEvent → LSP Diagnostic JSON
            for (AuditEvent e : events) {
                int line   = Math.max(0, e.getLine() - 1);
                int col    = Math.max(0, e.getColumn() - 1);
                int sev    = e.getSeverityLevel() == SeverityLevel.ERROR ? 1 : 2;
                String msg = jsonEscape(e.getMessage());
                String src = jsonEscape(e.getSourceName()
                        .replaceAll("^.*\\.", "")   // strip package prefix
                        .replace("Check", ""));     // trim "Check" suffix

                diags.add(String.format("""
                    {
                      "range": {
                        "start": {"line": %d, "character": %d},
                        "end":   {"line": %d, "character": %d}
                      },
                      "severity": %d,
                      "source": "checkstyle/%s",
                      "message": "%s"
                    }""", line, col, line, col + 1, sev, src, msg));
            }

        } catch (Exception ex) {
            System.err.println("Checkstyle error: " + ex.getMessage());
        }

        String diagArray = "[" + String.join(",", diags) + "]";
        sendMessage(String.format("""
            {
              "jsonrpc": "2.0",
              "method": "textDocument/publishDiagnostics",
              "params": {
                "uri": "%s",
                "diagnostics": %s
              }
            }""", jsonEscape(uri), diagArray));
    }

    // ── LSP response builders ──────────────────────────────────────────────

    private static String initializeResponse(String id) {
        return String.format("""
            {
              "jsonrpc": "2.0",
              "id": %s,
              "result": {
                "capabilities": {
                  "textDocumentSync": {
                    "openClose": true,
                    "change": 1,
                    "save": { "includeText": true }
                  }
                },
                "serverInfo": {
                  "name": "java-lint (checkstyle)",
                  "version": "0.1.0"
                }
              }
            }""", id);
    }

    private static String nullResponse(String id) {
        return String.format("""
            {"jsonrpc":"2.0","id":%s,"result":null}""", id);
    }

    // ── tiny JSON helpers (no deps) ────────────────────────────────────────

    private static String extract(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static String extractId(String json) {
        int i = json.indexOf("\"id\"");
        if (i < 0) return "null";
        int colon = json.indexOf(':', i + 4);
        int end   = json.indexOf(',', colon);
        if (end < 0) end = json.indexOf('}', colon);
        return json.substring(colon + 1, end).trim();
    }

    private static String extractText(String json) {
        // looks for "text":"..." in didOpen / didChange payloads
        return extract(json, "\"text\"");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

package ru.miacomsoft.ollama;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.bridge.Attachment;
import ru.miacomsoft.bridge.BridgeCommands;
import ru.miacomsoft.bridge.StreamListener;
import ru.miacomsoft.session.SessionManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static ru.miacomsoft.ollama.OllamaConstants.MODEL_NAME;
import static ru.miacomsoft.ollama.OllamaConstants.MODIFIED_AT;
import static ru.miacomsoft.ollama.OllamaConstants.DIGEST;
import static ru.miacomsoft.ollama.OllamaHttpUtils.noContent;
import static ru.miacomsoft.ollama.OllamaHttpUtils.methodNotAllowed;
import static ru.miacomsoft.ollama.OllamaHttpUtils.writeJson;
import static ru.miacomsoft.ollama.OllamaHttpUtils.writeError;
import static ru.miacomsoft.ollama.OllamaHttpUtils.readBody;
import static ru.miacomsoft.ollama.OllamaHttpUtils.logIncomingRequest;
import static ru.miacomsoft.ollama.OllamaHttpUtils.setCors;
import static ru.miacomsoft.ollama.OllamaHttpUtils.nowIso;

/**
 * Нативные эндпоинты Ollama: /api/* и корень "/".
 */
public final class OllamaNativeApi {

    private OllamaNativeApi() {}

    // ------------------------------------------------------------
    //  "/" — "Ollama is running"
    // ------------------------------------------------------------
    public static void handleRoot(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }
        byte[] body = "Ollama is running".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    // ------------------------------------------------------------
    //  /api/version
    // ------------------------------------------------------------
    public static void handleVersion(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        JSONObject o = new JSONObject();
        o.put("version", "0.1.0");
        writeJson(ex, 200, o);
    }

    // ------------------------------------------------------------
    //  /api/tags
    // ------------------------------------------------------------
    public static void handleTags(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }

        JSONObject model = new JSONObject();
        model.put("name", MODEL_NAME);
        model.put("model", MODEL_NAME);
        model.put("modified_at", MODIFIED_AT);
        model.put("size", 0);
        model.put("digest", DIGEST);

        JSONObject details = new JSONObject();
        details.put("format", "gguf");
        details.put("family", "deepseek");
        details.put("families", new JSONArray().put("deepseek"));
        details.put("parameter_size", "0B");
        details.put("quantization_level", "Q4_K_M");
        model.put("details", details);

        JSONObject resp = new JSONObject();
        resp.put("models", new JSONArray().put(model));
        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  /api/ps
    // ------------------------------------------------------------
    public static void handlePs(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        JSONObject resp = new JSONObject();
        resp.put("models", new JSONArray());
        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  /api/chat
    // ------------------------------------------------------------
    public static void handleChat(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        String raw = readBody(ex);
        JSONObject req;
        try { req = new JSONObject(raw); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        SessionManager.resetIfNewDialog(req.optJSONArray("messages"));

        OllamaInputExtractor.UserInput ui = OllamaInputExtractor.extractUserInput(req);

        // ← НОВОЕ: файлы, переданные веб-клиентом отдельным массивом "files"
        JSONArray filesArr = req.optJSONArray("files");
        if (filesArr != null) {
            for (int i = 0; i < filesArr.length(); i++) {
                JSONObject f = filesArr.optJSONObject(i);
                if (f == null) continue;
                String name = f.optString("name", "file.bin");
                String mime = f.optString("mimeType",
                        f.optString("mime", "application/octet-stream"));
                String b64  = f.optString("dataBase64", "");
                if (!b64.isEmpty()) {
                    ui.attachments.add(new Attachment(name, mime, b64));
                }
            }
        }

        if (!ui.hasContent()) {
            writeError(ex, 400, "no user content (text or images) in 'messages'");
            return;
        }

        SessionManager.get().addMessage("user", ui.text);

        boolean stream       = req.optBoolean("stream", true);
        boolean clipboard    = req.optBoolean("clipboard", false);
        boolean skipAttach   = req.optBoolean("skipAttach", false);
        boolean strict       = req.optBoolean("strict", false);
        boolean setTextFirst = req.optBoolean("setTextFirst", true);

        if (stream) handleChatStream(ex, ui, clipboard, skipAttach, strict, setTextFirst);
        else        handleChatNonStream(ex, ui);
    }

    static void handleChatStream(HttpExchange ex,
                                 OllamaInputExtractor.UserInput ui,
                                 boolean clipboard,
                                 boolean skipAttach,
                                 boolean strict,
                                 boolean setTextFirst) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        BridgeCommands.sendChatStream(ui.text, ui.attachments, new StreamListener() {
            @Override
            public void onChunk(String text, String type) {
                JSONObject chunk = new JSONObject();
                chunk.put("model", MODEL_NAME);
                chunk.put("created_at", nowIso());
                chunk.put("type", type); // RESPONSE / THINK
                JSONObject msg = new JSONObject();
                msg.put("role", "assistant");
                msg.put("content", text);
                chunk.put("message", msg);
                chunk.put("done", false);
                try {
                    os.write((chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
            }

            @Override
            public void onDone() {
                JSONObject done = doneObject("");
                try {
                    os.write((done.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
                try { os.close(); } catch (Exception ignored) {}
            }

            @Override
            public void onError(String message) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("error", message == null ? "error" : message);
                    os.write((err.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
                try { os.close(); } catch (Exception ignored) {}
            }
        }, clipboard, strict, skipAttach, setTextFirst);
    }

    static void handleChatNonStream(HttpExchange ex, OllamaInputExtractor.UserInput ui) {
        BridgeCommands.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
            try {
                if (err != null) {
                    writeError(ex, 500, err.getMessage() == null ? "error" : err.getMessage());
                    return;
                }
                JSONObject resp = doneObject(fullText);
                byte[] b = resp.toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            } catch (Exception ignored) {}
        });
    }

    // ------------------------------------------------------------
    //  /api/generate
    // ------------------------------------------------------------
    public static void handleGenerate(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        String raw = readBody(ex);
        JSONObject req;
        try { req = new JSONObject(raw); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        OllamaInputExtractor.UserInput ui = OllamaInputExtractor.extractTopLevelInput(req);

        if (!ui.hasContent()) {
            writeError(ex, 400, "empty 'prompt' and no 'images'");
            return;
        }

        boolean stream = req.optBoolean("stream", true);

        if (stream) {
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();

            BridgeCommands.sendChatStream(ui.text, ui.attachments, new StreamListener() {
                @Override
                public void onChunk(String text, String type) {
                    if (!"RESPONSE".equals(type)) return; // generate не поддерживает THINK
                    JSONObject chunk = new JSONObject();
                    chunk.put("model", MODEL_NAME);
                    chunk.put("created_at", nowIso());
                    chunk.put("response", text);
                    chunk.put("done", false);
                    try {
                        os.write((chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (Exception ignored) {}
                }
                @Override
                public void onDone() {
                    JSONObject done = new JSONObject();
                    done.put("model", MODEL_NAME);
                    done.put("created_at", nowIso());
                    done.put("response", "");
                    done.put("done", true);
                    done.put("done_reason", "stop");
                    done.put("total_duration", 0);
                    done.put("prompt_eval_count", 0);
                    done.put("eval_count", 0);
                    try { os.write((done.toString() + "\n").getBytes(StandardCharsets.UTF_8)); }
                    catch (Exception ignored) {}
                    try { os.close(); } catch (Exception ignored) {}
                }
                @Override
                public void onError(String message) {
                    try { os.close(); } catch (Exception ignored) {}
                }
            });
        } else {
            BridgeCommands.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
                try {
                    if (err != null) {
                        writeError(ex, 500, err.getMessage() == null ? "error" : err.getMessage());
                        return;
                    }
                    JSONObject resp = new JSONObject();
                    resp.put("model", MODEL_NAME);
                    resp.put("created_at", nowIso());
                    resp.put("response", fullText);
                    resp.put("done", true);
                    resp.put("done_reason", "stop");
                    resp.put("total_duration", 0);
                    resp.put("prompt_eval_count", 0);
                    resp.put("eval_count", 0);
                    byte[] b = resp.toString().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                } catch (Exception ignored) {}
            });
        }
    }

    // ------------------------------------------------------------
    //  /api/show
    // ------------------------------------------------------------
    public static void handleShow(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject resp = new JSONObject();
        resp.put("modelfile", "FROM " + MODEL_NAME);
        resp.put("parameters", "");
        resp.put("template", "");
        resp.put("license", "");

        JSONObject details = new JSONObject();
        details.put("format", "gguf");
        details.put("family", "deepseek");
        details.put("families", new JSONArray().put("deepseek"));
        details.put("parameter_size", "0B");
        details.put("quantization_level", "Q4_K_M");
        resp.put("details", details);

        JSONObject info = new JSONObject();
        info.put("general.architecture", "deepseek");
        resp.put("model_info", info);

        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  /api/embed
    // ------------------------------------------------------------
    public static void handleEmbed(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        String raw = readBody(ex);
        JSONObject req;
        try { req = new JSONObject(raw); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        JSONArray inputs = new JSONArray();
        if (req.has("input")) {
            Object in = req.get("input");
            if (in instanceof JSONArray) inputs = (JSONArray) in;
            else inputs.put(in);
        }
        JSONArray embeddings = new JSONArray();
        for (int i = 0; i < inputs.length(); i++) {
            embeddings.put(new JSONArray().put(0.0));
        }
        JSONObject resp = new JSONObject();
        resp.put("model", MODEL_NAME);
        resp.put("embeddings", embeddings);
        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  /api/pull
    // ------------------------------------------------------------
    public static void handlePull(HttpExchange ex) throws IOException {
        handleModelOp(ex, "success", true);
    }

    // ------------------------------------------------------------
    //  /api/push
    // ------------------------------------------------------------
    public static void handlePush(HttpExchange ex) throws IOException {
        handleModelOp(ex, "success", true);
    }

    // ------------------------------------------------------------
    //  /api/create
    // ------------------------------------------------------------
    public static void handleCreate(HttpExchange ex) throws IOException {
        handleModelOp(ex, "success", true);
    }

    // ------------------------------------------------------------
    //  /api/copy
    // ------------------------------------------------------------
    public static void handleCopy(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        String raw = readBody(ex);
        JSONObject req;
        try { req = new JSONObject(raw); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        String source = req.optString("source", "");
        String dest   = req.optString("destination", "");
        if (source.isEmpty() || dest.isEmpty()) {
            writeError(ex, 400, "missing 'source' or 'destination'");
            return;
        }

        JSONObject resp = new JSONObject();
        resp.put("status", "success");
        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  /api/delete
    // ------------------------------------------------------------
    public static void handleDelete(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        // Ollama принимает DELETE и (в некоторых версиях) POST
        if (!"DELETE".equalsIgnoreCase(ex.getRequestMethod())
                && !"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            methodNotAllowed(ex);
            return;
        }

        String raw = readBody(ex);
        JSONObject req;
        try { req = new JSONObject(raw); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        String name = req.optString("name", "");
        if (name.isEmpty()) {
            writeError(ex, 400, "missing 'name'");
            return;
        }

        JSONObject resp = new JSONObject();
        resp.put("status", "success");
        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  helper: общий обработчик pull/push/create
    //  Поддерживает stream (NDJSON, одна строка status:success)
    //  и не-stream (обычный JSON).
    // ------------------------------------------------------------
    private static void handleModelOp(HttpExchange ex, String status, boolean allowEmptyBody)
            throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        String raw = readBody(ex);
        JSONObject req;
        try { req = raw.isEmpty() ? new JSONObject() : new JSONObject(raw); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        boolean stream = req.optBoolean("stream", true);

        if (stream) {
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            JSONObject st = new JSONObject();
            st.put("status", status);
            try {
                os.write((st.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            } catch (Exception ignored) {}
        } else {
            JSONObject resp = new JSONObject();
            resp.put("status", status);
            writeJson(ex, 200, resp);
        }
    }

    // ------------------------------------------------------------
    //  helper: объект "done" для /api/chat
    // ------------------------------------------------------------
    static JSONObject doneObject(String content) {
        JSONObject done = new JSONObject();
        done.put("model", MODEL_NAME);
        done.put("created_at", nowIso());
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", content == null ? "" : content);
        done.put("message", msg);
        done.put("done", true);
        done.put("done_reason", "stop");
        done.put("total_duration", 0);
        done.put("prompt_eval_count", 0);
        done.put("eval_count", 0);
        return done;
    }
}
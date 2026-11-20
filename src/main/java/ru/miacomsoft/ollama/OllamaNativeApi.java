package ru.miacomsoft.ollama;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
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

        if (!ui.hasContent()) {
            writeError(ex, 400, "no user content (text or images) in 'messages'");
            return;
        }

        SessionManager.get().addMessage("user", ui.text);

        boolean stream = req.optBoolean("stream", true);
        if (stream) handleChatStream(ex, ui);
        else        handleChatNonStream(ex, ui);
    }

    static void handleChatStream(HttpExchange ex, OllamaInputExtractor.UserInput ui) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        BridgeCommands.sendChatStream(ui.text, ui.attachments, new StreamListener() {
            @Override
            public void onChunk(String text, String type) {
                if (!"RESPONSE".equals(type)) return;
                JSONObject chunk = new JSONObject();
                chunk.put("model", MODEL_NAME);
                chunk.put("created_at", nowIso());
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
                try { os.write((done.toString() + "\n").getBytes(StandardCharsets.UTF_8)); }
                catch (Exception ignored) {}
                try { os.close(); } catch (Exception ignored) {}
            }
            @Override
            public void onError(String message) {
                try { os.close(); } catch (Exception ignored) {}
            }
        });
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
                    if (!"RESPONSE".equals(type)) return;
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
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        try {
            JSONObject start = new JSONObject();
            start.put("status", "pulling manifest");
            os.write((start.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            JSONObject ok = new JSONObject();
            ok.put("status", "success");
            os.write((ok.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception ignored) {}

        try { os.close(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    //  /api/push
    // ------------------------------------------------------------
    public static void handlePush(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }
        JSONObject resp = new JSONObject();
        resp.put("status", "success");
        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  /api/create
    // ------------------------------------------------------------
    public static void handleCreate(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        try {
            JSONObject ok = new JSONObject();
            ok.put("status", "success");
            os.write((ok.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        try { os.close(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    //  /api/copy  /  /api/delete
    // ------------------------------------------------------------
    public static void handleCopy(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        writeError(ex, 404, "model copy not supported");
    }

    public static void handleDelete(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        JSONObject resp = new JSONObject();
        resp.put("status", "success");
        writeJson(ex, 200, resp);
    }

    // ------------------------------------------------------------
    //  Финальный кадр для /api/chat (non-stream и stream-onDone)
    // ------------------------------------------------------------
    static JSONObject doneObject(String fullText) {
        JSONObject resp = new JSONObject();
        resp.put("model", MODEL_NAME);
        resp.put("created_at", nowIso());
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", fullText == null ? "" : fullText);
        resp.put("message", msg);
        resp.put("done", true);
        resp.put("done_reason", "stop");
        resp.put("total_duration", 0);
        resp.put("prompt_eval_count", 0);
        resp.put("eval_count", 0);
        return resp;
    }
}
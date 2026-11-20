package ru.miacomsoft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Эмуляция Ollama API поверх BridgeService.
 *
 * Поддерживает мультимодальные запросы:
 *   - Ollama native:     messages[].images = ["base64..."]  (или generate.images)
 *   - OpenAI:            messages[].content = [{type:"text"}, {type:"image_url"}]
 *   - Anthropic-style:   messages[].content = [{type:"image", source:{...}}]
 *   - Расширенный:       messages[].content = [{type:"file", file:{name, mimeType, dataBase64}}]
 */
public class OllamaApiService {

    static final String MODEL_NAME = "DeepSeekCloud";
    static final String MODIFIED_AT = "2024-01-01T00:00:00Z";
    static final String DIGEST = "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    static final long   CREATED_EPOCH = 1704067200L;

    // ---------- извлечённый пользовательский ввод ----------
    static class UserInput {
        String text = "";
        final List<BridgeService.Attachment> attachments = new ArrayList<>();
        boolean hasContent() {
            return (text != null && !text.trim().isEmpty()) || !attachments.isEmpty();
        }
    }

    public static void register(HttpServer server) {
        server.createContext("/",              OllamaApiService::handleRoot);
        server.createContext("/api/version",   OllamaApiService::handleVersion);
        server.createContext("/api/tags",      OllamaApiService::handleTags);
        server.createContext("/api/ps",        OllamaApiService::handlePs);
        server.createContext("/api/chat",      OllamaApiService::handleChat);
        server.createContext("/api/generate",  OllamaApiService::handleGenerate);
        server.createContext("/api/show",      OllamaApiService::handleShow);
        server.createContext("/api/embed",     OllamaApiService::handleEmbed);
        server.createContext("/api/pull",      OllamaApiService::handlePull);
        server.createContext("/api/push",      OllamaApiService::handlePush);
        server.createContext("/api/create",    OllamaApiService::handleCreate);
        server.createContext("/api/copy",      OllamaApiService::handleCopy);
        server.createContext("/api/delete",    OllamaApiService::handleDelete);

        server.createContext("/v1", OllamaApiService::handleV1);
    }

    // =====================================================================
    //  ИЗВЛЕЧЕНИЕ ПОЛЬЗОВАТЕЛЬСКОГО ВВОДА
    // =====================================================================

    static UserInput extractUserInput(JSONObject req) {
        UserInput out = new UserInput();
        if (req == null || !req.has("messages")) return out;
        JSONArray msgs = req.optJSONArray("messages");
        if (msgs == null) return out;

        JSONObject userMsg = null;
        for (int i = msgs.length() - 1; i >= 0; i--) {
            Object mo = msgs.opt(i);
            if (!(mo instanceof JSONObject)) continue;
            JSONObject m = (JSONObject) mo;
            if ("user".equals(m.optString("role"))) { userMsg = m; break; }
        }
        if (userMsg == null) return out;

        // content: строка или массив частей
        Object content = userMsg.opt("content");
        if (content instanceof String) {
            out.text = (String) content;
        } else if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                Object po = parts.opt(i);
                if (!(po instanceof JSONObject)) continue;
                JSONObject p = (JSONObject) po;
                String type = p.optString("type", "");
                if ("text".equals(type) || "input_text".equals(type)) {
                    String t = p.optString("text", "");
                    if (!t.isEmpty()) {
                        if (!out.text.isEmpty()) out.text += "\n";
                        out.text += t;
                    }
                } else if ("image_url".equals(type) || "input_image".equals(type)) {
                    Object imgObj = p.opt("image_url");
                    String url = null;
                    if (imgObj instanceof JSONObject) {
                        url = ((JSONObject) imgObj).optString("url", null);
                    } else if (imgObj instanceof String) {
                        url = (String) imgObj;
                    }
                    if (url != null && url.startsWith("data:")) {
                        BridgeService.Attachment a = parseDataUrl(url, "image");
                        if (a != null) out.attachments.add(a);
                    }
                } else if ("image".equals(type)) {
                    // Anthropic-style
                    Object srcObj = p.opt("source");
                    if (srcObj instanceof JSONObject) {
                        JSONObject src = (JSONObject) srcObj;
                        if ("base64".equals(src.optString("type"))) {
                            String data  = src.optString("data", "");
                            String media = src.optString("media_type", "image/png");
                            if (!data.isEmpty()) {
                                String ext = media.contains("/")
                                        ? media.substring(media.indexOf('/') + 1)
                                        : "png";
                                out.attachments.add(new BridgeService.Attachment(
                                        "image." + ext, media, data));
                            }
                        }
                    }
                } else if ("file".equals(type)) {
                    // Расширенный формат: {type:"file", file:{name, mimeType, dataBase64}}
                    Object fObj = p.opt("file");
                    if (fObj instanceof JSONObject) {
                        JSONObject f = (JSONObject) fObj;
                        String data = f.optString("dataBase64", "");
                        if (!data.isEmpty()) {
                            out.attachments.add(new BridgeService.Attachment(
                                    f.optString("name", "file.bin"),
                                    f.optString("mimeType", "application/octet-stream"),
                                    data));
                        }
                    }
                }
            }
        }

        // Ollama images[]
        JSONArray imgs = userMsg.optJSONArray("images");
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                String b64 = imgs.optString(i, "");
                if (!b64.isEmpty()) {
                    String clean = b64.startsWith("data:") ? stripDataUrl(b64) : b64;
                    out.attachments.add(new BridgeService.Attachment(
                            "image-" + (i + 1) + ".png", "image/png", clean));
                }
            }
        }

        return out;
    }

    static UserInput extractTopLevelInput(JSONObject req) {
        UserInput out = new UserInput();
        out.text = req.optString("prompt", "");
        JSONArray imgs = req.optJSONArray("images");
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                String b64 = imgs.optString(i, "");
                if (!b64.isEmpty()) {
                    String clean = b64.startsWith("data:") ? stripDataUrl(b64) : b64;
                    out.attachments.add(new BridgeService.Attachment(
                            "image-" + (i + 1) + ".png", "image/png", clean));
                }
            }
        }
        return out;
    }

    static BridgeService.Attachment parseDataUrl(String url, String fallbackBase) {
        try {
            int comma = url.indexOf(',');
            if (comma < 0) return null;
            String meta = url.substring("data:".length(), comma);
            String data = url.substring(comma + 1);
            String mime = "application/octet-stream";
            boolean isBase64 = false;
            for (String part : meta.split(";")) {
                if ("base64".equalsIgnoreCase(part)) isBase64 = true;
                else if (part.contains("/")) mime = part;
            }
            if (!isBase64 || data.isEmpty()) return null;
            String ext = mime.contains("/")
                    ? mime.substring(mime.indexOf('/') + 1)
                    : "bin";
            return new BridgeService.Attachment(fallbackBase + "." + ext, mime, data);
        } catch (Exception e) {
            return null;
        }
    }

    static String stripDataUrl(String s) {
        int comma = s.indexOf(',');
        return comma < 0 ? s : s.substring(comma + 1);
    }

    // =====================================================================
    //  NATIVE OLLAMA
    // =====================================================================

    static void handleRoot(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }
        byte[] body = "Ollama is running".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    static void handleVersion(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        JSONObject o = new JSONObject();
        o.put("version", "0.1.0");
        writeJson(ex, 200, o);
    }

    static void handleTags(HttpExchange ex) throws IOException {
        setCors(ex);
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

    static void handlePs(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        JSONObject resp = new JSONObject();
        resp.put("models", new JSONArray());
        writeJson(ex, 200, resp);
    }

    static void handleChat(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject req;
        try { req = new JSONObject(readBody(ex)); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        UserInput ui = extractUserInput(req);
        if (!ui.hasContent()) {
            writeError(ex, 400, "no user content (text or images) in 'messages'");
            return;
        }

        boolean stream = req.optBoolean("stream", true);
        if (stream) handleChatStream(ex, ui);
        else        handleChatNonStream(ex, ui);
    }

    static void handleChatStream(HttpExchange ex, UserInput ui) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        BridgeService.sendChatStream(ui.text, ui.attachments, new BridgeService.StreamListener() {
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

    static void handleChatNonStream(HttpExchange ex, UserInput ui) {
        BridgeService.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
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

    static void handleGenerate(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject req;
        try { req = new JSONObject(readBody(ex)); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        UserInput ui = extractTopLevelInput(req);
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

            BridgeService.sendChatStream(ui.text, ui.attachments, new BridgeService.StreamListener() {
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
            BridgeService.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
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

    static void handleShow(HttpExchange ex) throws IOException {
        setCors(ex);
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

    static void handleEmbed(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject req;
        try { req = new JSONObject(readBody(ex)); }
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

    static void handlePull(HttpExchange ex) throws IOException {
        setCors(ex);
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

    static void handlePush(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }
        JSONObject resp = new JSONObject();
        resp.put("status", "success");
        writeJson(ex, 200, resp);
    }

    static void handleCreate(HttpExchange ex) throws IOException {
        setCors(ex);
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

    static void handleCopy(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        writeError(ex, 404, "model copy not supported");
    }

    static void handleDelete(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
        JSONObject resp = new JSONObject();
        resp.put("status", "success");
        writeJson(ex, 200, resp);
    }

    // =====================================================================
    //  OPENAI-COMPATIBLE /v1/*
    // =====================================================================

    static void handleV1(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }

        String path = ex.getRequestURI().getPath();

        if ("/v1/models".equals(path)) {
            handleV1ModelsList(ex);
        } else if (path.startsWith("/v1/models/")) {
            handleV1ModelById(ex, path.substring("/v1/models/".length()));
        } else if ("/v1/chat/completions".equals(path)) {
            handleV1ChatCompletions(ex);
        } else if ("/v1/completions".equals(path)) {
            handleV1Completions(ex);
        } else if ("/v1/embeddings".equals(path)) {
            handleV1Embeddings(ex);
        } else {
            writeError(ex, 404, "unknown v1 path: " + path);
        }
    }

    static void handleV1ModelsList(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject m = new JSONObject();
        m.put("id", MODEL_NAME);
        m.put("object", "model");
        m.put("created", CREATED_EPOCH);
        m.put("owned_by", "miacomsoft");

        JSONObject resp = new JSONObject();
        resp.put("object", "list");
        resp.put("data", new JSONArray().put(m));
        writeJson(ex, 200, resp);
    }

    static void handleV1ModelById(HttpExchange ex, String id) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }
        if (!MODEL_NAME.equals(id)) {
            writeError(ex, 404, "model not found: " + id);
            return;
        }
        JSONObject m = new JSONObject();
        m.put("id", MODEL_NAME);
        m.put("object", "model");
        m.put("created", CREATED_EPOCH);
        m.put("owned_by", "miacomsoft");
        writeJson(ex, 200, m);
    }

    static void handleV1ChatCompletions(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject req;
        try { req = new JSONObject(readBody(ex)); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        UserInput ui = extractUserInput(req);
        if (!ui.hasContent()) {
            writeError(ex, 400, "no user content (text or images) in 'messages'");
            return;
        }

        String model = req.optString("model", MODEL_NAME);
        if (model == null || model.isEmpty()) model = MODEL_NAME;

        boolean stream = req.optBoolean("stream", false);
        if (stream) v1ChatStream(ex, ui, model);
        else        v1ChatNonStream(ex, ui, model);
    }

    static void v1ChatStream(HttpExchange ex, UserInput ui, String model) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        final String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        final long created = Instant.now().getEpochSecond();
        final String modelName = model;

        try {
            JSONObject start = new JSONObject();
            start.put("id", id);
            start.put("object", "chat.completion.chunk");
            start.put("created", created);
            start.put("model", modelName);
            JSONObject delta = new JSONObject();
            delta.put("role", "assistant");
            delta.put("content", "");
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", JSONObject.NULL);
            start.put("choices", new JSONArray().put(choice));
            os.write(("data: " + start.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception ignored) {}

        BridgeService.sendChatStream(ui.text, ui.attachments, new BridgeService.StreamListener() {
            @Override
            public void onChunk(String text, String type) {
                if (!"RESPONSE".equals(type)) return;
                JSONObject chunk = new JSONObject();
                chunk.put("id", id);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", created);
                chunk.put("model", modelName);
                JSONObject delta = new JSONObject();
                delta.put("content", text);
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                choice.put("delta", delta);
                choice.put("finish_reason", JSONObject.NULL);
                chunk.put("choices", new JSONArray().put(choice));
                try {
                    os.write(("data: " + chunk.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
            }
            @Override
            public void onDone() {
                try {
                    JSONObject fin = new JSONObject();
                    fin.put("id", id);
                    fin.put("object", "chat.completion.chunk");
                    fin.put("created", created);
                    fin.put("model", modelName);
                    JSONObject choice = new JSONObject();
                    choice.put("index", 0);
                    choice.put("delta", new JSONObject());
                    choice.put("finish_reason", "stop");
                    fin.put("choices", new JSONArray().put(choice));
                    os.write(("data: " + fin.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
                try { os.close(); } catch (Exception ignored) {}
            }
            @Override
            public void onError(String message) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("error", new JSONObject().put("message", message));
                    os.write(("data: " + err.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
                try { os.close(); } catch (Exception ignored) {}
            }
        });
    }

    static void v1ChatNonStream(HttpExchange ex, UserInput ui, String model) {
        final String modelName = (model == null || model.isEmpty()) ? MODEL_NAME : model;
        BridgeService.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
            try {
                if (err != null) {
                    writeError(ex, 500, err.getMessage() == null ? "error" : err.getMessage());
                    return;
                }
                String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                long created = Instant.now().getEpochSecond();

                JSONObject resp = new JSONObject();
                resp.put("id", id);
                resp.put("object", "chat.completion");
                resp.put("created", created);
                resp.put("model", modelName);

                JSONObject msg = new JSONObject();
                msg.put("role", "assistant");
                msg.put("content", fullText == null ? "" : fullText);

                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                choice.put("message", msg);
                choice.put("finish_reason", "stop");
                resp.put("choices", new JSONArray().put(choice));

                JSONObject usage = new JSONObject();
                usage.put("prompt_tokens", 0);
                usage.put("completion_tokens", 0);
                usage.put("total_tokens", 0);
                resp.put("usage", usage);

                byte[] b = resp.toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            } catch (Exception ignored) {}
        });
    }

    static void handleV1Completions(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject req;
        try { req = new JSONObject(readBody(ex)); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        UserInput ui = extractTopLevelInput(req);
        if (!ui.hasContent()) { writeError(ex, 400, "empty 'prompt' and no 'images'"); return; }

        String model = req.optString("model", MODEL_NAME);
        if (model == null || model.isEmpty()) model = MODEL_NAME;

        boolean stream = req.optBoolean("stream", false);
        final String modelName = model;

        if (stream) {
            v1CompletionStream(ex, ui, modelName);
        } else {
            BridgeService.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
                try {
                    if (err != null) {
                        writeError(ex, 500, err.getMessage() == null ? "error" : err.getMessage());
                        return;
                    }
                    String id = "cmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    long created = Instant.now().getEpochSecond();

                    JSONObject resp = new JSONObject();
                    resp.put("id", id);
                    resp.put("object", "text_completion");
                    resp.put("created", created);
                    resp.put("model", modelName);

                    JSONObject choice = new JSONObject();
                    choice.put("index", 0);
                    choice.put("text", fullText == null ? "" : fullText);
                    choice.put("finish_reason", "stop");
                    resp.put("choices", new JSONArray().put(choice));

                    JSONObject usage = new JSONObject();
                    usage.put("prompt_tokens", 0);
                    usage.put("completion_tokens", 0);
                    usage.put("total_tokens", 0);
                    resp.put("usage", usage);

                    byte[] b = resp.toString().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                } catch (Exception ignored) {}
            });
        }
    }

    static void v1CompletionStream(HttpExchange ex, UserInput ui, String model) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        final String id = "cmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        final long created = Instant.now().getEpochSecond();
        final String modelName = model;

        BridgeService.sendChatStream(ui.text, ui.attachments, new BridgeService.StreamListener() {
            @Override
            public void onChunk(String text, String type) {
                if (!"RESPONSE".equals(type)) return;
                JSONObject chunk = new JSONObject();
                chunk.put("id", id);
                chunk.put("object", "text_completion");
                chunk.put("created", created);
                chunk.put("model", modelName);
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                choice.put("text", text);
                choice.put("finish_reason", JSONObject.NULL);
                chunk.put("choices", new JSONArray().put(choice));
                try {
                    os.write(("data: " + chunk.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
            }
            @Override
            public void onDone() {
                try {
                    JSONObject fin = new JSONObject();
                    fin.put("id", id);
                    fin.put("object", "text_completion");
                    fin.put("created", created);
                    fin.put("model", modelName);
                    JSONObject choice = new JSONObject();
                    choice.put("index", 0);
                    choice.put("text", "");
                    choice.put("finish_reason", "stop");
                    fin.put("choices", new JSONArray().put(choice));
                    os.write(("data: " + fin.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
                try { os.close(); } catch (Exception ignored) {}
            }
            @Override
            public void onError(String message) {
                try { os.close(); } catch (Exception ignored) {}
            }
        });
    }

    static void handleV1Embeddings(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        JSONObject req;
        try { req = new JSONObject(readBody(ex)); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        JSONArray inputs = new JSONArray();
        if (req.has("input")) {
            Object in = req.get("input");
            if (in instanceof JSONArray) inputs = (JSONArray) in;
            else inputs.put(in);
        }

        JSONArray data = new JSONArray();
        for (int i = 0; i < inputs.length(); i++) {
            JSONObject item = new JSONObject();
            item.put("object", "embedding");
            item.put("index", i);
            item.put("embedding", new JSONArray().put(0.0));
            data.put(item);
        }

        String model = req.optString("model", MODEL_NAME);
        if (model == null || model.isEmpty()) model = MODEL_NAME;

        JSONObject resp = new JSONObject();
        resp.put("object", "list");
        resp.put("data", data);
        resp.put("model", model);
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 0);
        usage.put("total_tokens", 0);
        resp.put("usage", usage);

        writeJson(ex, 200, resp);
    }

    // =====================================================================
    //  HELPERS
    // =====================================================================

    static JSONObject doneObject(String content) {
        JSONObject o = new JSONObject();
        o.put("model", MODEL_NAME);
        o.put("created_at", nowIso());
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", content);
        o.put("message", msg);
        o.put("done", true);
        o.put("done_reason", "stop");
        o.put("total_duration", 0);
        o.put("prompt_eval_count", 0);
        o.put("eval_count", 0);
        return o;
    }

    static String nowIso() {
        return Instant.now().toString();
    }

    static void setCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    static void noContent(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    static void methodNotAllowed(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(405, -1);
        ex.close();
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void writeJson(HttpExchange ex, int status, JSONObject obj) throws IOException {
        byte[] b = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static void writeError(HttpExchange ex, int status, String message) throws IOException {
        JSONObject o = new JSONObject();
        o.put("error", message);
        writeJson(ex, status, o);
    }
}
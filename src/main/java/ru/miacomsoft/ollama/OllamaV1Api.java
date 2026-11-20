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
import java.time.Instant;
import java.util.UUID;

import static ru.miacomsoft.ollama.OllamaConstants.MODEL_NAME;
import static ru.miacomsoft.ollama.OllamaConstants.CREATED_EPOCH;
import static ru.miacomsoft.ollama.OllamaHttpUtils.noContent;
import static ru.miacomsoft.ollama.OllamaHttpUtils.methodNotAllowed;
import static ru.miacomsoft.ollama.OllamaHttpUtils.writeJson;
import static ru.miacomsoft.ollama.OllamaHttpUtils.writeError;
import static ru.miacomsoft.ollama.OllamaHttpUtils.readBody;
import static ru.miacomsoft.ollama.OllamaHttpUtils.logIncomingRequest;
import static ru.miacomsoft.ollama.OllamaHttpUtils.setCors;

/**
 * OpenAI-совместимые эндпоинты: /v1/*.
 */
public final class OllamaV1Api {

    private OllamaV1Api() {}

    // ------------------------------------------------------------
    //  /v1 — диспетчер
    // ------------------------------------------------------------
    public static void handleV1(HttpExchange ex) throws IOException {
        setCors(ex);
        logIncomingRequest(ex, null);
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

    // ------------------------------------------------------------
    //  /v1/models  и  /v1/models/{id}
    // ------------------------------------------------------------
    static void handleV1ModelsList(HttpExchange ex) throws IOException {
        logIncomingRequest(ex, null);
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
        logIncomingRequest(ex, null);
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

    // ------------------------------------------------------------
    //  /v1/chat/completions
    // ------------------------------------------------------------
    static void handleV1ChatCompletions(HttpExchange ex) throws IOException {
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

        String model = req.optString("model", MODEL_NAME);
        if (model == null || model.isEmpty()) model = MODEL_NAME;

        boolean stream = req.optBoolean("stream", false);
        if (stream) v1ChatStream(ex, ui, model);
        else        v1ChatNonStream(ex, ui, model);
    }

    static void v1ChatStream(HttpExchange ex, OllamaInputExtractor.UserInput ui, String model) throws IOException {
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

        BridgeCommands.sendChatStream(ui.text, ui.attachments, new StreamListener() {
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

    static void v1ChatNonStream(HttpExchange ex, OllamaInputExtractor.UserInput ui, String model) {
        final String modelName = (model == null || model.isEmpty()) ? MODEL_NAME : model;
        BridgeCommands.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
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

    // ------------------------------------------------------------
    //  /v1/completions
    // ------------------------------------------------------------
    static void handleV1Completions(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { methodNotAllowed(ex); return; }

        String raw = readBody(ex);
        JSONObject req;
        try { req = new JSONObject(raw); }
        catch (Exception e) { writeError(ex, 400, "invalid json"); return; }

        OllamaInputExtractor.UserInput ui = OllamaInputExtractor.extractTopLevelInput(req);

        if (!ui.hasContent()) { writeError(ex, 400, "empty 'prompt' and no 'images'"); return; }

        String model = req.optString("model", MODEL_NAME);
        if (model == null || model.isEmpty()) model = MODEL_NAME;

        boolean stream = req.optBoolean("stream", false);
        final String modelName = model;

        if (stream) {
            v1CompletionStream(ex, ui, modelName);
        } else {
            BridgeCommands.sendChat(ui.text, ui.attachments).whenComplete((fullText, err) -> {
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

    static void v1CompletionStream(HttpExchange ex, OllamaInputExtractor.UserInput ui, String model) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        final String id = "cmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        final long created = Instant.now().getEpochSecond();
        final String modelName = model;

        BridgeCommands.sendChatStream(ui.text, ui.attachments, new StreamListener() {
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

    // ------------------------------------------------------------
    //  /v1/embeddings
    // ------------------------------------------------------------
    static void handleV1Embeddings(HttpExchange ex) throws IOException {
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
        String model = req.optString("model", MODEL_NAME);
        if (model == null || model.isEmpty()) model = MODEL_NAME;

        JSONArray data = new JSONArray();
        for (int i = 0; i < inputs.length(); i++) {
            JSONObject d = new JSONObject();
            d.put("object", "embedding");
            d.put("index", i);
            d.put("embedding", new JSONArray().put(0.0));
            data.put(d);
        }
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 0);
        usage.put("total_tokens", 0);

        JSONObject resp = new JSONObject();
        resp.put("object", "list");
        resp.put("data", data);
        resp.put("model", model);
        resp.put("usage", usage);
        writeJson(ex, 200, resp);
    }
}
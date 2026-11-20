package ru.miacomsoft.ollama;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.bridge.Attachment;

import java.util.ArrayList;
import java.util.List;

public final class OllamaInputExtractor {

    private OllamaInputExtractor() {}

    public static class UserInput {
        public String text = "";
        public final List<Attachment> attachments = new ArrayList<>();
        public boolean hasContent() {
            return (text != null && !text.trim().isEmpty()) || !attachments.isEmpty();
        }
    }

    public static UserInput extractUserInput(JSONObject req) {
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
                        Attachment a = parseDataUrl(url, "image");
                        if (a != null) out.attachments.add(a);
                    }
                } else if ("image".equals(type)) {
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
                                out.attachments.add(new Attachment(
                                        "image." + ext, media, data));
                            }
                        }
                    }
                } else if ("file".equals(type)) {
                    Object fObj = p.opt("file");
                    if (fObj instanceof JSONObject) {
                        JSONObject f = (JSONObject) fObj;
                        String data = f.optString("dataBase64", "");
                        if (!data.isEmpty()) {
                            String name = f.optString("name", "");
                            if (name.isEmpty()) name = f.optString("filename", "");
                            if (name.isEmpty()) name = "file.bin";

                            String mime = f.optString("mimeType", "");
                            if (mime.isEmpty()) mime = f.optString("mime_type", "");
                            if (mime.isEmpty()) mime = guessMimeByName(name);

                            int semi = mime.indexOf(';');
                            if (semi > 0) mime = mime.substring(0, semi).trim();

                            out.attachments.add(new Attachment(name, mime, data));
                        }
                    }
                }
            }
        }

        JSONArray imgs = userMsg.optJSONArray("images");
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                String b64 = imgs.optString(i, "");
                if (!b64.isEmpty()) {
                    String clean = b64.startsWith("data:") ? stripDataUrl(b64) : b64;
                    out.attachments.add(new Attachment(
                            "image-" + (i + 1) + ".png", "image/png", clean));
                }
            }
        }

        return out;
    }

    public static UserInput extractTopLevelInput(JSONObject req) {
        UserInput out = new UserInput();
        out.text = req.optString("prompt", "");
        JSONArray imgs = req.optJSONArray("images");
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                String b64 = imgs.optString(i, "");
                if (!b64.isEmpty()) {
                    String clean = b64.startsWith("data:") ? stripDataUrl(b64) : b64;
                    out.attachments.add(new Attachment(
                            "image-" + (i + 1) + ".png", "image/png", clean));
                }
            }
        }
        return out;
    }

    static Attachment parseDataUrl(String url, String fallbackBase) {
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
            return new Attachment(fallbackBase + "." + ext, mime, data);
        } catch (Exception e) {
            return null;
        }
    }

    static String stripDataUrl(String s) {
        int comma = s.indexOf(',');
        return comma < 0 ? s : s.substring(comma + 1);
    }

    static String guessMimeByName(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp"))  return "image/bmp";
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".txt"))  return "text/plain";
        if (n.endsWith(".md"))   return "text/markdown";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".csv"))  return "text/csv";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".xml"))  return "application/xml";
        if (n.endsWith(".yaml") || n.endsWith(".yml")) return "application/x-yaml";
        if (n.endsWith(".js"))   return "application/javascript";
        if (n.endsWith(".ts"))   return "application/typescript";
        if (n.endsWith(".java")) return "text/plain";
        if (n.endsWith(".py"))   return "text/x-python";
        if (n.endsWith(".doc"))  return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }
}
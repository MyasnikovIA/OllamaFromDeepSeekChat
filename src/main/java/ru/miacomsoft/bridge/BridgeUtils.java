package ru.miacomsoft.bridge;

import com.sun.net.httpserver.HttpExchange;

import java.net.URLDecoder;

public final class BridgeUtils {

    private BridgeUtils() {}

    public static void setCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    public static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (pair.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    public static String shortId(String tabId) {
        if (tabId == null) return "?";
        return tabId.length() > 12 ? tabId.substring(0, 12) + "…" : tabId;
    }

    public static String guessMimeByName(String name) {
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
        if (n.endsWith(".doc"))  return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }
}
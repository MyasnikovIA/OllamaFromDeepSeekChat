package ru.miacomsoft.ollama;

import com.sun.net.httpserver.HttpServer;

/**
 * Регистрация всех HTTP-контекстов Ollama / OpenAI-совместимого API.
 *
 * Единственная точка входа для Main.java:
 *   OllamaRouter.register(server);
 */
public final class OllamaRouter {

    private OllamaRouter() {}

    public static void register(HttpServer server) {
        // --- нативные Ollama ---
        server.createContext("/",              OllamaNativeApi::handleRoot);
        server.createContext("/api/version",   OllamaNativeApi::handleVersion);
        server.createContext("/api/tags",      OllamaNativeApi::handleTags);
        server.createContext("/api/ps",        OllamaNativeApi::handlePs);
        server.createContext("/api/chat",      OllamaNativeApi::handleChat);
        server.createContext("/api/generate",  OllamaNativeApi::handleGenerate);
        server.createContext("/api/show",      OllamaNativeApi::handleShow);
        server.createContext("/api/embed",     OllamaNativeApi::handleEmbed);
        server.createContext("/api/pull",      OllamaNativeApi::handlePull);
        server.createContext("/api/push",      OllamaNativeApi::handlePush);
        server.createContext("/api/create",    OllamaNativeApi::handleCreate);
        server.createContext("/api/copy",      OllamaNativeApi::handleCopy);
        server.createContext("/api/delete",    OllamaNativeApi::handleDelete);

        // --- OpenAI-совместимые ---
        server.createContext("/v1", OllamaV1Api::handleV1);
    }
}
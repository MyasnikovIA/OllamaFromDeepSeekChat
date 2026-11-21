package ru.miacomsoft.bridge;

import com.sun.net.httpserver.HttpServer;

public final class BridgeRouter {

    private BridgeRouter() {}

    public static void register(HttpServer server) {
        server.createContext("/events", BridgeEventsHandler::handle);
        server.createContext("/log",    BridgeLogHandler::handle);
        server.createContext("/tabs",   BridgeTabsHandler::handle);

        // --- публичный API команд и AJAX-моста ---
        server.createContext("/api/command",     ApiCommandHandler::handle);
        server.createContext("/api/ajax",        ApiAjaxHandler::handle);
        server.createContext("/api/ajax/events", ApiAjaxEventsHandler::handle);
    }
}
package ru.miacomsoft.bridge;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Состояние одной вкладки браузера (бывший Client). */
public class BrowserTab {
    public final String tabId;
    public final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    public final long connectedAt = System.currentTimeMillis();

    public volatile boolean active         = true;
    public volatile boolean thinking       = false;
    public volatile boolean smartSearch    = false;
    public volatile boolean tokenExhausted = false;
    public volatile boolean modesReported  = false;
    public volatile String  info           = "";
    public volatile String  closeReason    = "";

    public BrowserTab(String tabId) { this.tabId = tabId; }
}
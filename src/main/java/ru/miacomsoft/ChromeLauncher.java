package ru.miacomsoft;

import ru.miacomsoft.config.BrowserConfig;
import ru.miacomsoft.config.Config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ChromeLauncher {

    public enum OS { WINDOWS, MACOS, LINUX, UNKNOWN }

    private ChromeLauncher() {}

    public static OS detectOS() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return OS.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OS.MACOS;
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) return OS.LINUX;
        return OS.UNKNOWN;
    }

    /**
     * Запускает ВСЕ браузеры из конфига (используется при старте, если включено autoLaunchBrowsers).
     */
    public static void openDeepSeek() {
        OS os = detectOS();
        System.out.println("[CHROME] OS detected: " + os);

        Config cfg = Config.get();
        List<BrowserConfig> browsers = cfg.getBrowsers();

        if (browsers == null || browsers.isEmpty()) {
            System.out.println("[CHROME] browsers[] empty → single default profile");
            fallbackAutoLaunch(os, cfg.getDeepseekUrl());
        } else {
            for (BrowserConfig bc : browsers) {
                launchBrowser(bc, os, cfg.getDeepseekUrl());
            }
        }

        // popup-окна почты
        if (isNonEmpty(cfg.getMailClientUrl())) {
            launchPopupUrl(cfg.getMailClientUrl(), os);
        }
        if (isNonEmpty(cfg.getMailAdminUrl())) {
            launchPopupUrl(cfg.getMailAdminUrl(), os);
        }
    }

    /**
     * NEW: Запускает браузер по конкретному индексу из массива browsers в config.json.
     * Полезно для ручного запуска без открытия всех окон сразу.
     */
    public static void launchBrowserAtIndex(int index) {
        Config cfg = Config.get();
        List<BrowserConfig> browsers = cfg.getBrowsers();

        if (browsers == null || index < 0 || index >= browsers.size()) {
            System.err.println("[CHROME] Invalid browser index: " + index + " (total: " + (browsers == null ? 0 : browsers.size()) + ")");
            return;
        }

        BrowserConfig bc = browsers.get(index);
        OS os = detectOS();
        String url = cfg.getDeepseekUrl();

        System.out.println("[CHROME] Launching browser #" + index + ": " + bc.displayName());
        launchBrowser(bc, os, url);
    }

    /**
     * Запустить браузер с профилем по умолчанию (если передан только url).
     */
    public static void launchNewWindow(String url) {
        String target = (url == null || url.trim().isEmpty()) ? Config.get().getDeepseekUrl() : url.trim();
        OS os = detectOS();
        List<BrowserConfig> browsers = Config.get().getBrowsers();
        if (browsers != null && !browsers.isEmpty()) {
            for (BrowserConfig bc : browsers) {
                if (bc.isValid()) {
                    launchBrowser(bc, os, target);
                    return;
                }
            }
        }
        fallbackAutoLaunch(os, target);
    }

    /**
     * Запустить браузер с конкретным профилем.
     */
    public static void launchNewWindow(String url, BrowserConfig bc) {
        String target = (url == null || url.trim().isEmpty()) ? Config.get().getDeepseekUrl() : url.trim();
        OS os = detectOS();
        if (bc != null && bc.isValid()) {
            launchBrowser(bc, os, target);
        } else {
            launchNewWindow(target);
        }
    }

    // ============================================================
    //  DeepSeek-окно одного профиля (--user-data-dir).
    // ============================================================
    static void launchBrowser(BrowserConfig bc, OS os, String url) {
        if (!bc.isValid()) {
            System.err.println("[CHROME] skip invalid browser entry: " + bc);
            return;
        }
        String exe = pickExecutable(os);
        if (exe == null) {
            System.err.println("[CHROME] no chrome executable (config empty & auto-detect failed)"
                    + " — opening default browser for: " + url);
            openDefaultBrowser(os, url);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add("--no-first-run");
        args.add("--no-default-browser-check");

        if (Config.get().isHideAddressBar()) {
            args.add("--app=" + url);
        } else {
            args.add(url);
        }

        if (bc.userDataDir != null && !bc.userDataDir.isEmpty()) {
            args.add("--user-data-dir=" + bc.userDataDir);
        }

        appendExtensionArg(args);
        launch(exe, args, os, "[" + bc.displayName() + "] " + url);
    }

    // ============================================================
    //  Fallback: один экземпляр в профиле по умолчанию.
    // ============================================================
    static void fallbackAutoLaunch(OS os, String url) {
        String chrome = pickExecutable(os);
        if (chrome == null) {
            System.out.println("[CHROME] Chrome not found, opening default browser");
            openDefaultBrowser(os, url);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add("--no-first-run");
        args.add("--no-default-browser-check");

        if (Config.get().isHideAddressBar()) {
            args.add("--app=" + url);
        } else {
            args.add(url);
        }

        String profileDir = System.getProperty("ds.profile.dir");
        if (profileDir != null && !profileDir.isEmpty()) {
            args.add("--user-data-dir=" + new File(profileDir).getAbsolutePath());
        }

        appendExtensionArg(args);
        launch(chrome, args, os, "[auto] " + url);
    }

    // ============================================================
    //  Popup-окно почты — ОБЫЧНЫЙ режим (с адресной строкой).
    // ============================================================
    static void launchPopupUrl(String url, OS os) {
        String exe = pickExecutable(os);
        if (exe == null) {
            System.out.println("[CHROME] no browser for mail popup, using default: " + url);
            openDefaultBrowser(os, url);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add(url);

        launch(exe, args, os, "[mail] " + url);
    }

    // ============================================================
    //  Выбор исполняемого файла
    // ============================================================
    static String pickExecutable(OS os) {
        String configured = Config.get().getChromePath();
        if (configured != null && !configured.isEmpty()) {
            String resolved = resolveExecutable(configured);
            if (resolved != null) return resolved;
            System.err.println("[CHROME] configured chrome path not found: " + configured
                    + " — falling back to auto-detect");
        }
        return findChromeExecutable(os);
    }

    static void appendExtensionArg(List<String> args) {
        String extDir = System.getProperty("ds.extension.dir");
        if (extDir == null) extDir = Config.get().getExtensionDir();
        if (extDir != null && !extDir.isEmpty()) {
            File f = new File(extDir);
            if (f.isDirectory()) {
                args.add("--load-extension=" + f.getAbsolutePath());
            } else {
                System.err.println("[CHROME] extension dir not found: " + extDir);
            }
        }
    }

    static void launch(String exe, List<String> args, OS os, String label) {
        List<String> cmd = buildLaunchCommand(exe, args, os);
        System.out.println("[CHROME] launching " + label);
        System.out.println("[CHROME]   cmd = " + cmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (IOException e) {
            System.err.println("[CHROME] launch failed (" + label + "): " + e.getMessage());
            if (!args.isEmpty()) {
                String appArg = null;
                for (String a : args) {
                    if (a.startsWith("--app=")) { appArg = a.substring("--app=".length()); break; }
                }
                if (appArg != null) openDefaultBrowser(os, appArg);
            }
        }
    }

    static List<String> buildLaunchCommand(String exe, List<String> args, OS os) {
        List<String> cmd = new ArrayList<>();
        if (os == OS.MACOS && exe.toLowerCase(Locale.ROOT).endsWith(".app")) {
            cmd.add("open");
            cmd.add("-na");
            cmd.add(exe);
            cmd.add("--args");
            cmd.addAll(args);
        } else {
            cmd.add(exe);
            cmd.addAll(args);
        }
        return cmd;
    }

    // ============================================================
    //  Resolution helpers
    // ============================================================
    static String resolveExecutable(String path) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        String p = which(path);
        if (p != null) return p;
        return null;
    }

    static String findChromeExecutable(OS os) {
        switch (os) {
            case WINDOWS: return findChromeWindows();
            case MACOS:   return findChromeMac();
            case LINUX:   return findChromeLinux();
            default:      return null;
        }
    }

    static String findChromeWindows() {
        String[] candidates = {
                "C:/Program Files/Google/Chrome/Application/chrome.exe",
                "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
                System.getenv("LOCALAPPDATA") + "/Google/Chrome/Application/chrome.exe",
                "C:/ChromeForTesting/chrome-win64/chrome.exe"
        };
        for (String c : candidates) {
            if (c == null) continue;
            File f = new File(c);
            if (f.isFile()) return f.getAbsolutePath();
        }
        String p = which("chrome.exe");
        if (p != null) return p;
        return which("chrome");
    }

    static String findChromeMac() {
        String[] candidates = {
                "/Applications/Google Chrome.app",
                "/Applications/Chromium.app",
                System.getProperty("user.home") + "/Applications/Google Chrome.app"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isDirectory()) return c;
        }
        return null;
    }

    static String findChromeLinux() {
        String[] names = {
                "google-chrome", "google-chrome-stable",
                "chromium", "chromium-browser", "chrome"
        };
        for (String n : names) {
            String p = which(n);
            if (p != null) return p;
        }
        return null;
    }

    static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String sep = File.pathSeparator;
        for (String dir : path.split(java.util.regex.Pattern.quote(sep))) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    static void openDefaultBrowser(OS os, String url) {
        try {
            switch (os) {
                case WINDOWS:
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                    break;
                case MACOS:
                    new ProcessBuilder("open", url).start();
                    break;
                default:
                    new ProcessBuilder("xdg-open", url).start();
                    break;
            }
        } catch (IOException e) {
            System.err.println("[CHROME] cannot open default browser: " + e.getMessage());
        }
    }

    private static boolean isNonEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
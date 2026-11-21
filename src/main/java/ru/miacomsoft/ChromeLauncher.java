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
        if (os.contains("win"))                  return OS.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OS.MACOS;
        if (os.contains("nux") || os.contains("nix") || os.contains("aix"))
            return OS.LINUX;
        return OS.UNKNOWN;
    }

    // ============================================================
    //  Public API
    // ============================================================
    public static void openDeepSeek() {
        OS os = detectOS();
        System.out.println("[CHROME] OS detected: " + os);

        Config cfg = Config.get();
        List<BrowserConfig> browsers = cfg.getBrowsers();

        if (browsers == null || browsers.isEmpty()) {
            System.out.println("[CHROME] browsers[] empty → auto-detect");
            fallbackAutoLaunch(os, cfg.getDeepseekUrl());
        } else {
            for (BrowserConfig bc : browsers) {
                launchBrowser(bc, os, cfg.getDeepseekUrl());
            }
        }

        // ---- popup-окна почты (если заданы) ----
        if (isNonEmpty(cfg.getMailClientUrl())) {
            launchPopupUrl(cfg.getMailClientUrl(), os);
        }
        if (isNonEmpty(cfg.getMailAdminUrl())) {
            launchPopupUrl(cfg.getMailAdminUrl(), os);
        }
    }

    /** Открыть ещё одно окно DeepSeek (используется из /opentab при отсутствии активной вкладки). */
    public static void launchNewWindow(String url) {
        String target = (url == null || url.trim().isEmpty())
                ? Config.get().getDeepseekUrl()
                : url.trim();
        OS os = detectOS();

        List<BrowserConfig> browsers = Config.get().getBrowsers();
        if (browsers != null && !browsers.isEmpty()) {
            // Используем первый валидный профиль как «прототип»,
            // но открываем в нём новый URL.
            for (BrowserConfig bc : browsers) {
                if (bc.isValid()) {
                    launchBrowser(bc, os, target);
                    return;
                }
            }
        }
        fallbackAutoLaunch(os, target);
    }

    // ============================================================
    //  DeepSeek-окно одного профиля (режим --app + user-data-dir)
    // ============================================================
    static void launchBrowser(BrowserConfig bc, OS os, String url) {
        if (!bc.isValid()) {
            System.err.println("[CHROME] skip invalid browser entry: " + bc);
            return;
        }
        String exe = resolveExecutable(bc.path);
        if (exe == null) {
            System.err.println("[CHROME] executable not found [" + bc.name + "]: " + bc.path);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--app=" + url);
        if (bc.userDataDir != null && !bc.userDataDir.isEmpty()) {
            args.add("--user-data-dir=" + bc.userDataDir);
        }

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

        launch(exe, args, os, "[" + bc.name + "] " + url);
    }

    // ============================================================
    //  Fallback: авто-поиск Chrome, один профиль
    // ============================================================
    static void fallbackAutoLaunch(OS os, String url) {
        String chrome = findChromeExecutable(os);
        if (chrome == null) {
            System.out.println("[CHROME] Chrome not found, opening default browser");
            openDefaultBrowser(os, url);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--app=" + url);

        String profileDir = System.getProperty("ds.profile.dir");
        if (profileDir != null && !profileDir.isEmpty()) {
            args.add("--user-data-dir=" + new File(profileDir).getAbsolutePath());
        }

        String extDir = System.getProperty("ds.extension.dir");
        if (extDir == null) extDir = Config.get().getExtensionDir();
        if (extDir != null && !extDir.isEmpty()) {
            File f = new File(extDir);
            if (f.isDirectory()) args.add("--load-extension=" + f.getAbsolutePath());
        }

        launch(chrome, args, os, "[auto] " + url);
    }

    // ============================================================
    //  Popup-окно для почты (--app, БЕЗ user-data-dir)
    // ============================================================
    static void launchPopupUrl(String url, OS os) {
        String exe = pickChromeForPopup(os);
        if (exe == null) {
            System.out.println("[CHROME] no browser for popup, using default: " + url);
            openDefaultBrowser(os, url);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--app=" + url);
        // НЕТ --user-data-dir → используется профиль по умолчанию.

        launch(exe, args, os, "[popup] " + url);
    }

    static String pickChromeForPopup(OS os) {
        List<BrowserConfig> browsers = Config.get().getBrowsers();
        if (browsers != null && !browsers.isEmpty()) {
            for (BrowserConfig bc : browsers) {
                if (!bc.isValid()) continue;
                String exe = resolveExecutable(bc.path);
                if (exe != null) return exe;
            }
        }
        return findChromeExecutable(os);
    }

    // ============================================================
    //  OS-specific launch
    // ============================================================
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
            // Fallback на URL без аргументов
            if (!args.isEmpty()) {
                String appArg = null;
                for (String a : args) {
                    if (a.startsWith("--app=")) { appArg = a.substring("--app=".length()); break; }
                }
                if (appArg != null) openDefaultBrowser(os, appArg);
            }
        }
    }

    /**
     * Разный механизм запуска по ОС:
     *
     *   Windows : прямой exec (ProcessBuilder)
     *   Linux   : прямой exec
     *   macOS   : если путь — .app-бандл, через `open -na <app> --args …`;
     *             если это бинарь внутри бандла — прямой exec.
     */
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
        // Попробуем найти через PATH (полезно для Linux-случая "google-chrome")
        String p = which(path);
        if (p != null) return p;
        // Не нашли — вернём null, чтобы вызывающий код залогировал ошибку.
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
        String[] envRoots = {"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"};
        for (String env : envRoots) {
            String root = System.getenv(env);
            if (root == null) continue;
            File f = new File(root, "Google\\Chrome\\Application\\chrome.exe");
            if (f.isFile()) return f.getAbsolutePath();
        }
        return which("chrome.exe");
    }

    static String findChromeMac() {
        String[] candidates = {
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                System.getProperty("user.home") + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    static String findChromeLinux() {
        for (String n : new String[]{"google-chrome", "google-chrome-stable", "chromium", "chromium-browser"}) {
            String p = which(n);
            if (p != null) return p;
        }
        return null;
    }

    static String which(String name) {
        if (name == null) return null;
        // Если это абсолютный путь — уже проверено выше в resolveExecutable;
        // для простых имён идём по PATH.
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    // ============================================================
    //  Default browser (best effort, с адресной строкой)
    // ============================================================
    static void openDefaultBrowser(OS os, String url) {
        List<String> cmd = new ArrayList<>();
        switch (os) {
            case WINDOWS:
                cmd.add("cmd.exe");
                cmd.add("/c");
                cmd.add("start");
                cmd.add("");
                cmd.add(url);
                break;
            case MACOS:
                cmd.add("open");
                cmd.add(url);
                break;
            case LINUX:
            case UNKNOWN:
            default:
                cmd.add("xdg-open");
                cmd.add(url);
                break;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            System.out.println("[CHROME] default browser opened: " + url);
        } catch (IOException e) {
            System.err.println("[CHROME] default browser failed: " + e.getMessage());
        }
    }

    private static boolean isNonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
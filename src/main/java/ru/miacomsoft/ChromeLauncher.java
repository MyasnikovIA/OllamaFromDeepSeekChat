package ru.miacomsoft;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ChromeLauncher {

    public static final String DEEPSEEK_URL = "https://chat.deepseek.com/";

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

    public static void openDeepSeek() {
        OS os = detectOS();
        System.out.println("[CHROME] OS detected: " + os);

        String chrome = findChromeExecutable(os);
        if (chrome == null) {
            System.out.println("[CHROME] Chrome not found, opening default browser");
            openDefaultBrowser(os, DEEPSEEK_URL);
            return;
        }

        System.out.println("[CHROME] binary: " + chrome);
        try {
            launchChrome(chrome, DEEPSEEK_URL);
        } catch (IOException e) {
            System.err.println("[CHROME] launch failed: " + e.getMessage());
            openDefaultBrowser(os, DEEPSEEK_URL);
        }
    }

    // ============================================================
    //  НОВОЕ: открыть Chrome с произвольным URL (fallback для /opentab)
    // ============================================================
    public static void launchNewWindow(String url) {
        String target = (url == null || url.trim().isEmpty()) ? DEEPSEEK_URL : url.trim();
        OS os = detectOS();
        String chrome = findChromeExecutable(os);
        if (chrome == null) {
            System.out.println("[CHROME] Chrome not found, opening default browser");
            openDefaultBrowser(os, target);
            return;
        }
        try {
            launchChrome(chrome, target);
        } catch (IOException e) {
            System.err.println("[CHROME] launch failed: " + e.getMessage());
            openDefaultBrowser(os, target);
        }
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
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    static void launchChrome(String chrome, String url) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(chrome);
        cmd.add("--new-window");
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");

        String extDir    = System.getProperty("ds.extension.dir");
        String profileDir = System.getProperty("ds.profile.dir");

        if (extDir != null && !extDir.isEmpty()) {
            File f = new File(extDir);
            if (f.isDirectory()) {
                cmd.add("--load-extension=" + f.getAbsolutePath());
                if (profileDir == null || profileDir.isEmpty()) {
                    profileDir = new File(System.getProperty("user.home"), ".deepseek-ollama-profile").getAbsolutePath();
                }
                System.out.println("[CHROME] extension: " + f.getAbsolutePath());
            } else {
                System.err.println("[CHROME] extension dir not found: " + extDir);
            }
        }
        if (profileDir != null && !profileDir.isEmpty()) {
            cmd.add("--user-data-dir=" + new File(profileDir).getAbsolutePath());
        }

        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.start();
        System.out.println("[CHROME] launched, url=" + url);
    }

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
            System.out.println("[CHROME] default browser opened");
        } catch (IOException e) {
            System.err.println("[CHROME] default browser failed: " + e.getMessage());
        }
    }
}
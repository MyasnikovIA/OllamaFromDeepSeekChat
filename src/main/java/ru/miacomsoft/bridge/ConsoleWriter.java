package ru.miacomsoft.bridge;

/**
 * Потоковый вывод текста в System.out с двумя возможностями:
 *
 *   1. Word-wrap по ширине терминала (по границам целых слов).
 *      Ширина берётся из:
 *        -Dds.console.width=<N>   (System property)
 *        env COLUMNS=<N>           (переменная окружения, обычно ставит shell)
 *        fallback 120
 *
 *   2. Код между обратными кавычками (`x` или ```...```) выводится как есть:
 *      без word-wrap, с сохранением всех \n, символов и отступов.
 *
 * Все "прямые" сообщения ([TAB], [BYE], [TABOP], [EXT-ERR], [FILE] …)
 * должны предваряться вызовом ensureNewline() — иначе они "прилипнут"
 * к недописанному слову потока.
 */
public final class ConsoleWriter {

    private static final ConsoleWriter INSTANCE = new ConsoleWriter();

    public static ConsoleWriter get() { return INSTANCE; }

    private final int width;
    private int column = 0;
    private boolean inCode = false;
    private int fenceLen = 0;
    private final StringBuilder word = new StringBuilder();

    private ConsoleWriter() {
        this.width = detectWidth();
    }

    private static int detectWidth() {
        String w = System.getProperty("ds.console.width");
        if (w != null) {
            try { return Math.max(40, Integer.parseInt(w.trim())); } catch (Exception ignored) {}
        }
        String env = System.getenv("COLUMNS");
        if (env != null) {
            try { return Math.max(40, Integer.parseInt(env.trim())); } catch (Exception ignored) {}
        }
        return 120;
    }

    public int getWidth() { return width; }

    // ------------------------------------------------------------
    //  Потоковый вывод
    // ------------------------------------------------------------

    /** Печатает префикс [THINK] / [DEEPSEEK] в начало новой строки. */
    public synchronized void printTag(String tag) {
        flushWord();
        if (column > 0) {
            System.out.println();
            column = 0;
        }
        System.out.print(tag);
        column = tag.length();
        System.out.flush();
    }

    /** Печатает потоковый текст с word-wrap и сохранением кода. */
    public synchronized void appendStreamText(String text) {
        if (text == null || text.isEmpty()) return;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '`') {
                int run = 1;
                while (i + run < text.length() && text.charAt(i + run) == '`') run++;
                handleBacktickRun(run);
                i += (run - 1);
                continue;
            }

            if (inCode) {
                System.out.print(c);
                if (c == '\n') column = 0;
                else column++;
                continue;
            }

            if (c == ' ' || c == '\t' || c == '\n') {
                flushWord();
                if (c == '\n') {
                    System.out.println();
                    column = 0;
                } else if (c == '\t') {
                    int tabStop = ((column / 4) + 1) * 4;
                    int spaces = tabStop - column;
                    if (column > 0 && column + spaces > width) {
                        System.out.println();
                        column = 0;
                    } else {
                        for (int s = 0; s < spaces; s++) System.out.print(' ');
                        column += spaces;
                    }
                } else {
                    // обычный пробел
                    if (column > 0 && column + 1 > width) {
                        System.out.println();
                        column = 0;
                    } else {
                        System.out.print(' ');
                        column++;
                    }
                }
            } else {
                word.append(c);
            }
        }
        System.out.flush();
    }

    private void handleBacktickRun(int run) {
        String ticks = "`".repeat(run);
        if (!inCode) {
            flushWord();
            System.out.print(ticks);
            column += run;
            inCode = true;
            fenceLen = run;
        } else if (run == fenceLen) {
            System.out.print(ticks);
            column += run;
            inCode = false;
            fenceLen = 0;
        } else {
            System.out.print(ticks);
            column += run;
        }
    }

    private void flushWord() {
        if (word.length() == 0) return;
        int len = word.length();
        if (column > 0 && column + len > width) {
            System.out.println();
            column = 0;
        }
        System.out.print(word);
        column += len;
        word.setLength(0);
    }

    // ------------------------------------------------------------
    //  Завершение / пауза потока
    // ------------------------------------------------------------

    /** Flush + перевод строки. Сбрасывает inCode. Для конца стрима. */
    public synchronized void finishStream() {
        flushWord();
        System.out.println();
        column = 0;
        inCode = false;
        fenceLen = 0;
        System.out.flush();
    }

    /** Flush + перевод строки (только если что-то недописано). */
    public synchronized void ensureNewline() {
        flushWord();
        if (column > 0) {
            System.out.println();
            column = 0;
        }
        System.out.flush();
    }

    /** Полный сброс состояния (для тестов). */
    public synchronized void reset() {
        word.setLength(0);
        column = 0;
        inCode = false;
        fenceLen = 0;
    }
}
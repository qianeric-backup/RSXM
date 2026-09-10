package com.rsxm.app;

/**
 * PTY 实时流剥离器：从 reasonix TUI 流里抽出纯文本内容，过滤掉
 * 光标定位/清屏/OSC/边框字符与重复帧噪音，只留 AI 回复/工具输出可读文本。
 * 供 NativeChat 的「实时输出」区显示。
 */
public final class TerminalStreamParser {

    private TerminalStreamParser() {}

    /** 主剥离入口：in → 可读文本（保留换行/制表） */
    public static String stripTuiDecorations(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        boolean inCsi = false, inOsc = false;
        while (i < n) {
            char c = s.charAt(i);
            if (inCsi) {
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '\u0007') { inCsi = false; i++; continue; }
                i++; continue;
            }
            if (inOsc) {
                if (c == '\u0007') { inOsc = false; i++; continue; }
                if (c == '\u001b' && i + 1 < n && s.charAt(i + 1) == '\\') { inOsc = false; i += 2; continue; }
                i++; continue;
            }
            if (c == 0x1b) {
                char n1 = (i + 1 < n) ? s.charAt(i + 1) : 0;
                if (n1 == '[') { inCsi = true; i += 2; continue; }
                if (n1 == ']') { inOsc = true; i += 2; continue; }
                i += (n1 == 0 ? 1 : 2);
                continue;
            }
            if (c == '\n' || c == '\t') { out.append(c); i++; continue; }
            if (c < 0x20) { i++; continue; }
            // TUI 边框字符（覆盖式重绘噪音）
            if (isBoxChar(c)) { i++; continue; }
            out.append(c);
            i++;
        }
        // 丢 TUI 框架行（banner/状态栏/快捷键/输入提示符）
        String s2 = dropTuiNoiseLines(out.toString());
        return collapseBlankLines(s2);
    }

    private static boolean isBoxChar(char c) {
        return c == 0x2500 || c == 0x2502 || c == 0x250C || c == 0x2510
                || c == 0x2514 || c == 0x2518 || c == 0x251C || c == 0x2524
                || c == 0x252C || c == 0x2534 || c == 0x253C
                || c == 0x256D || c == 0x256E || c == 0x256F || c == 0x2570
                || c == 0x250F || c == 0x2513 || c == 0x2517 || c == 0x251B
                || c == 0x2550 || c == 0x2551 || c == 0x2554 || c == 0x2557
                || c == 0x255A || c == 0x255D;
    }


    /**
     * TUI 框架行黑名单：reasonix 启动 banner / 状态栏 / 快捷键提示 / 输入提示符。
     * 匹配行整行丢弃——用户开口的真回显不被误杀（这些都是 TUI 专用行首）。
     */
    private static final java.util.regex.Pattern[] TUI_NOISE_LINES = {
        java.util.regex.Pattern.compile("^\\s*\\u25C6\\s*reasonix\\b.*$"),
        java.util.regex.Pattern.compile("^\\s*Context is kept across turns\\b.*$"),
        java.util.regex.Pattern.compile("^\\s*screen cleared\\s*$"),
        java.util.regex.Pattern.compile("^\\s*YOLO\\s*\\u00b7\\s*tool approvals.*$"),
        java.util.regex.Pattern.compile("Shift\\+Tab.*Ctrl\\+Y\\s*YOLO.*$"),
        java.util.regex.Pattern.compile("^\\s*MODEL\\s+\\S+\\s+EFFORT\\s+\\S+\\s*$"),
        java.util.regex.Pattern.compile("^\\s*CTX\\s+[\\d.]+[KkMmTt]?\\s*\\(\\d+%\\)\\s+COMPACT.*$"),
        java.util.regex.Pattern.compile("^\\s*>\\s*$"),
        java.util.regex.Pattern.compile("^\\s*\\u203A\\s*$"),
        java.util.regex.Pattern.compile("^\\s*Balance:\\s*.*$"),
        java.util.regex.Pattern.compile("^\\s*(Ask|Plan|Auto|YOLO)\\s*$"),
        java.util.regex.Pattern.compile("^\\s*\\u00b7\\s*(anthropic-key|web_search|claude|openai|deepseek)\\s*$")
    };

    /** 按 TUI 框架行黑名单丢弃独立匹配行 */
    private static String dropTuiNoiseLines(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            boolean drop = false;
            for (java.util.regex.Pattern pa : TUI_NOISE_LINES) {
                if (pa.matcher(line).matches()) { drop = true; break; }
            }
            if (!drop) { out.append(line).append('\n'); }
        }
        if (out.length() > 0 && out.charAt(out.length()-1)=='\n') out.setLength(out.length()-1);
        return out.toString();
    }

    private static String collapseBlankLines(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int consec = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') { consec++; if (consec <= 2) out.append(c); }
            else { consec = 0; out.append(c); }
        }
        return out.toString();
    }
}

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
        // 把 TUI 空白压缩为单空行（重复 \n\n\n → \n\n）
        return collapseBlankLines(out.toString());
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

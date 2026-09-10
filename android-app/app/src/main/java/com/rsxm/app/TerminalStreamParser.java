package com.rsxm.app;

import java.util.ArrayList;
import java.util.List;

/**
 * PTY 实时流剥离器 v2：screen-aware。
 *
 * reasonix TUI 基于 bubbletea v2（alt-screen 全屏重绘模型）：
 * 每帧为「\e[H 光标回家 → 逐行整屏重写 → \e[K 清行尾」，帧间可能穿插 \e[2J/\e[3J 清屏。
 * 旧版按帧无脑把剥离后的文本累加，框架行/重复行随每帧堆叠 → GUI 回显混乱。
 *
 * v2 做法：
 * 1) 逐字符剥离 CSI/OSC/边框/控制字符，保留可读文本与 \n \t；
 * 2) ScreenDiff 跨帧跟踪：每帧剥离后的行与上一帧做公共前缀对齐 diff，
 *    上一屏已有的框架行不重复输出，只输出新增内容行；
 * 3) 框架行黑名单（稳定 token，容忍字段顺序变化）兜底首帧与 diff 对不齐的情况。
 * 无状态入口仍为 {@link #stripTuiDecorations(String)}；
 * 跨帧去重用 {@link ScreenDiff#feed(String)}。
 */
public final class TerminalStreamParser {

    private TerminalStreamParser() {}

    // ------------------------------------------------------------------
    // 单帧剥离
    // ------------------------------------------------------------------

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
        return collapseBlankLines(dropTuiNoiseLines(out.toString()));
    }

    // ------------------------------------------------------------------
    // 跨帧屏幕 diff：bubbletea 整屏重绘时上一屏内容通常以相同顺序保留在本帧开头，
    // 找最长公共前缀，只让前缀之后的行通过；对不齐时退化为黑名单逐行过滤。
    // ------------------------------------------------------------------

    public static final class ScreenDiff {
        private List<String> prev = new ArrayList<>();

        /** feed 一帧剥离后的文本，返回相对上一帧新增的可读文本（可为空串） */
        public synchronized String feed(String frame) {
            if (frame == null || frame.isEmpty()) return "";
            List<String> lines = new ArrayList<>();
            for (String ln : frame.split("\n", -1)) lines.add(ln);

            int commonPrefix = 0;
            int cap = Math.min(prev.size(), lines.size());
            while (commonPrefix < cap && prev.get(commonPrefix).equals(lines.get(commonPrefix))) {
                commonPrefix++;
            }
            // 只有当上一屏几乎全部被本帧开头复现（≥80%）时才认定为整屏重绘，跳过前缀；
            // 否则（如屏幕滚动导致错位）保持逐行黑名单过滤，避免误漏真实内容。
            boolean repaint = !prev.isEmpty() && commonPrefix * 5 >= prev.size() * 4;
            int start = repaint ? commonPrefix : 0;

            StringBuilder outB = new StringBuilder(frame.length());
            for (int i = start; i < lines.size(); i++) {
                String ln = lines.get(i);
                if (isNoiseLine(ln)) continue;
                outB.append(ln).append('\n');
            }
            prev = lines;
            return collapseBlankLines(outB.toString());
        }

        /** 会话切换/清屏时重置 */
        public synchronized void reset() {
            prev = new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    // 框架行黑名单：稳定 token 匹配，容忍字段顺序/文案微调
    // ------------------------------------------------------------------

    private static boolean isNoiseLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.isEmpty()) return false;
        if (t.contains("Context is kept across turns")) return true;
        if (t.contains("screen cleared")) return true;
        if (t.startsWith("◆") && t.contains("reasonix")) return true;
        if (t.contains("tool approvals skipped")) return true;
        if (t.contains("Shift+Tab") && t.contains("Ctrl+Y")) return true;
        if (t.startsWith("MODEL") && t.contains("EFFORT")) return true;
        if (t.startsWith("CTX") && (t.contains("COMPACT") || t.contains("%"))) return true;
        if (t.startsWith("BAL") && (t.contains("¥") || t.contains("$") || t.contains("Balance"))) return true;
        if (t.startsWith("Balance:")) return true;
        if (t.equals(">") || t.equals("›")) return true;
        if (t.equals("Ask") || t.equals("Plan") || t.equals("Auto") || t.equals("YOLO")) return true;
        if (t.startsWith("esc to interrupt") || t.startsWith("Esc to interrupt")) return true;
        // spinner 行（braille 转轮，逐帧变化）
        if (t.matches("[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏◐◓◑◒].*")) return true;
        return false;
    }

    /** 黑名单整行过滤（供无状态入口使用） */
    private static String dropTuiNoiseLines(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            if (!isNoiseLine(line)) out.append(line).append('\n');
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
        return out.toString();
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

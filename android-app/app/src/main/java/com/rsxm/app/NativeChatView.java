package com.rsxm.app;

import android.util.Log;

/**
 * 原生会话视图：把 reasonix 的 TUI 字节流整理成稳定的多行文本，交给 MainActivity 的
 * native_output TextView 渲染。
 *
 * 背景：WebView/xterm.js 依赖 JS 渲染，部分设备（userfaultfd 卡死、WebView 合成层
 * 不刷新）出现黑屏/排版错乱；xterm 对 alt-screen 的整屏重绘（大量 \e[H \e[J 光标
 * 定位）在触摸滚动下还会与 WebView 自带滚动冲突。
 *
 * 本类做一个轻量终端缓冲：
 *  - 按行缓冲 char 网格（rows x cols），处理 \r、\n、\r\n、退格、\e[K（清行尾）、
 *    \e[H/\e[1;1H（归位）、\e[J（清屏）、alt-screen 切换（\e[?1049h/l）等常用序列；
 *  - 其余 CSI 序列（颜色/光标移动/翻页等）剥离，不进入文本；
 *  - 每次 flush() 输出当前屏幕文本的稳定表示（行去尾随空格）。
 *
 * 输入由 MainActivity 侧直接写入 sProcIn（与 xterm 共用同一条通道），本类不持有 I/O。
 */
public class NativeChatView {
    private static final String TAG = "NativeChatView";
    private static final int ROWS = 40;
    private static final int COLS = 120;

    private final char[][] grid = new char[ROWS][COLS];
    private int row = 0;      // 当前光标行
    private int col = 0;      // 当前光标列
    private boolean altScreen = false;

    /** 归一化输出（可在线程任意调用，线程安全由调用方保证） */
    public NativeChatView() {
        clearScreen();
    }

    private void clearScreen() {
        for (int r = 0; r < ROWS; r++) {
            java.util.Arrays.fill(grid[r], ' ');
        }
        row = 0; col = 0;
    }

    /** 把一段字节（已按 UTF-8 解码为 String）灌入终端缓冲 */
    public synchronized void feed(String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\u001b') {
                // 转义序列
                if (i + 1 < n && s.charAt(i + 1) == '[') {
                    // CSI ... 直到字母结尾
                    int j = i + 2;
                    StringBuilder param = new StringBuilder();
                    char fin = 0;
                    while (j < n) {
                        char pc = s.charAt(j);
                        if ((pc >= '0' && pc <= '9') || pc == ';' || pc == '?' || pc == ' ' || pc == '>') {
                            param.append(pc);
                            j++;
                        } else {
                            fin = pc;
                            j++;
                            break;
                        }
                    }
                    handleCSI(param.toString(), fin);
                    i = j;
                } else if (i + 1 < n && s.charAt(i + 1) == ']') {
                    // OSC ... 到 BEL 或 ESC \（终端标题等），跳过
                    int j = i + 2;
                    while (j < n && s.charAt(j) != '\u0007') {
                        if (s.charAt(j) == '\u001b' && j + 1 < n && s.charAt(j + 1) == '\\') {
                            j += 2; break;
                        }
                        j++;
                    }
                    i = j;
                } else if (i + 1 < n && (s.charAt(i + 1) == '7' || s.charAt(i + 1) == '8' ||
                        s.charAt(i + 1) == '=' || s.charAt(i + 1) == '>')) {
                    i += 2;   // DECSC/DECRC 等，忽略
                } else if (i + 1 < n && s.charAt(i + 1) == '(') {
                    i += 3;   // 字符集选择，忽略
                } else {
                    i += 1;   // 孤立 ESC
                }
            } else if (c == '\r') {
                col = 0;
                i++;
            } else if (c == '\n') {
                newline();
                i++;
            } else if (c == '\b') {
                if (col > 0) col--;
                i++;
            } else if (c == '\t') {
                do { put(' '); } while ((col % 8) != 0 && col < COLS);
                i++;
            } else if (c == '\u0007') {
                i++;   // BEL 忽略
            } else if (c >= 0x20) {
                put(c);
                i++;
            } else {
                i++;
            }
        }
    }

    private void put(char c) {
        if (col >= COLS) {
            col = 0;
            newline();
        }
        if (row >= ROWS) {  // 屏幕已满：整体上移
            for (int r = 1; r < ROWS; r++) {
                System.arraycopy(grid[r], 0, grid[r - 1], 0, COLS);
            }
            java.util.Arrays.fill(grid[ROWS - 1], ' ');
            row = ROWS - 1;
        }
        grid[row][col] = c;
        col++;
    }

    private void newline() {
        if (row < ROWS - 1) {
            row++;
        } else {
            for (int r = 1; r < ROWS; r++) {
                System.arraycopy(grid[r], 0, grid[r - 1], 0, COLS);
            }
            java.util.Arrays.fill(grid[ROWS - 1], ' ');
        }
        // reasonix 输出常用 \r\n；保持光标在行首
        col = 0;
    }

    private void handleCSI(String param, char fin) {
        switch (fin) {
            case 'H': {   // 光标定位 \e[H \e[r;cH
                int[] p = parseParams(param);
                row = clamp(p.length > 0 ? p[0] : 1, 1, ROWS) - 1;
                col = clamp(p.length > 1 ? p[1] : 1, 1, COLS) - 1;
                break;
            }
            case 'f': {   // 同 H
                int[] p = parseParams(param);
                row = clamp(p.length > 0 ? p[0] : 1, 1, ROWS) - 1;
                col = clamp(p.length > 1 ? p[1] : 1, 1, COLS) - 1;
                break;
            }
            case 'J': {   // 清屏 \e[J \e[2J
                if (param.isEmpty() || param.equals("2") || param.startsWith("2;")) {
                    clearScreen();
                } else if (param.equals("0")) {
                    // 从光标清到末尾
                    for (int r = row; r < ROWS; r++) {
                        int start = (r == row) ? col : 0;
                        java.util.Arrays.fill(grid[r], start, COLS, ' ');
                    }
                }
                break;
            }
            case 'K': {   // 清行 \e[K
                java.util.Arrays.fill(grid[row], col, COLS, ' ');
                break;
            }
            case 'A': {   // 光标上移
                int k = param.isEmpty() ? 1 : Math.max(1, parseInt0(param));
                row = clamp(row - k, 0, ROWS - 1);
                break;
            }
            case 'B': {   // 光标下移
                int k = param.isEmpty() ? 1 : Math.max(1, parseInt0(param));
                row = clamp(row + k, 0, ROWS - 1);
                break;
            }
            case 'C': {   // 右移
                int k = param.isEmpty() ? 1 : Math.max(1, parseInt0(param));
                col = clamp(col + k, 0, COLS - 1);
                break;
            }
            case 'D': {   // 左移
                int k = param.isEmpty() ? 1 : Math.max(1, parseInt0(param));
                col = clamp(col - k, 0, COLS - 1);
                break;
            }
            case 'l': case 'h': {   // 模式设置；alt-screen/bracketed paste 等忽略，文本无影响
                break;
            }
            default:
                break;   // 颜色等一律剥离
        }
    }

    private static int[] parseParams(String param) {
        String[] parts = param.split(";");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parseInt0(parts[i]);
        }
        return out;
    }

    private static int parseInt0(String s) {
        try {
            return Integer.parseInt(s.replace("?", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 提取当前屏幕的稳定文本（行去尾随空格，去除尾部全空行），用于 TextView 渲染 */
    public synchronized String render() {
        StringBuilder sb = new StringBuilder(ROWS * COLS + ROWS);
        int firstNonEmpty = 0;
        while (firstNonEmpty < ROWS && isRowEmpty(firstNonEmpty)) firstNonEmpty++;
        int lastNonEmpty = ROWS - 1;
        while (lastNonEmpty > firstNonEmpty && isRowEmpty(lastNonEmpty)) lastNonEmpty--;
        for (int r = firstNonEmpty; r <= lastNonEmpty; r++) {
            int end = COLS;
            while (end > 0 && grid[r][end - 1] == ' ') end--;
            sb.append(new String(grid[r], 0, end));
            if (r < lastNonEmpty) sb.append('\n');
        }
        return sb.toString();
    }

    private boolean isRowEmpty(int r) {
        for (int c = 0; c < COLS; c++) {
            if (grid[r][c] != ' ') return false;
        }
        return true;
    }
}
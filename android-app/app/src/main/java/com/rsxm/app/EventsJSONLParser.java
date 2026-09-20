package com.rsxm.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话事件流解析器（events.jsonl 局部增量，v2 修复回显混乱的根本解）。
 *
 * 根因：旧 NativeChat 实时区把 PTY 原始字节流（bubbletea TUI 全屏重绘帧）剥离
 * 后拼接显示，无法可靠区分「重绘框架行 / 真实新增内容」，ScreenDiff 前缀 diff
 * 在流式单行追加（AI 逐 token 输出）场景下根本不成立 —— 每帧都只追加少量
 * 字符，前缀全变，整屏被当成全量新内容。
 *
 * 正确做法：reasonix 会话目录每个会话都有 &lt;id&gt;.events.jsonl（desktop 版
 * 消费的同一文件），格式为增量补丁流：
 *   {"type":"append","revision":151,"messages":[{role,content,...}]}
 *   {"type":"replace","revision":146,"base_revision":145,"messages":[...]}
 * 每次 provider 流式输出落盘都会追加一条 patch。本类按字节增量读取并解析，
 * 输出「相对上一 revision 的新增文本」——天然增量，零 TUI 框架行。
 *
 * 事件形态兼容（按样例 events.jsonl 与上游 internal/turnevent/ledger.go）：
 *   - {"type":"append",  messages:[...]}   增量：只渲染新 message
 *   - {"type":"replace", messages:[...]}   快照重建（revision 变更）
 *   - {"type":"truncate"}                  回退/compact，清空重放
 */
public class EventsJSONLParser {

    /** 渲染事件（assistant 增量文本 / tool 事件 / 状态） */
    public static class Event {
        public String kind = "";      // "text" | "reasoning" | "tool"
        public String role = "";      // assistant | user | tool
        public String content = "";   // 渲染正文（text 为增量片段，tool/状态 为整段）
        public String toolName = "";  // tool 事件才有
        public long revision = -1;

        public boolean isText() { return "text".equals(kind); }
        public boolean isReasoning() { return "reasoning".equals(kind); }
        public boolean isTool() { return "tool".equals(kind); }
    }

    private final File eventsFile;
    private long lastOffset = 0;
    private long lastRevision = -1;   // 已消费到的 revision（type=replace 时跳过此前内容）
    /** 跨 patch 追加缓冲：append 的 assistant content 可能拆在多个 revision 中 */
    private final StringBuilder pendingText = new StringBuilder();
    private final List<Event> out = new ArrayList<>();

    public EventsJSONLParser(File eventsFile) {
        this.eventsFile = eventsFile;
    }

    public File file() { return eventsFile; }

    /** 消费自上次 poll 以来新产生的事件（返回后内部清空） */
    public synchronized List<Event> drain() {
        List<Event> r = new ArrayList<>(out);
        out.clear();
        return r;
    }

    public synchronized boolean isEmpty() { return out.isEmpty(); }
    public synchronized int pendingCount() { return out.size(); }
    /** 供 UI 显示「AI 正在输入」时的累积缓冲 */
    public synchronized String pendingText() { return pendingText.toString(); }
    public synchronized void clearPendingText() { pendingText.setLength(0); }

    /** 增量读取 + 解析 events.jsonl 新追加部分；有新事件返回 true */
    public synchronized boolean poll() {
        if (eventsFile == null || !eventsFile.exists()) return false;
        long size = eventsFile.length();
        if (size < lastOffset) {
            // 文件被截断/重建（rotation、compaction 或测试中 Files.write 覆盖）：从头重读
            lastOffset = 0;
        }
        if (size == lastOffset) return false;
        try (RandomAccessFile raf = new RandomAccessFile(eventsFile, "r")) {
            raf.seek(lastOffset);
            long avail = size - lastOffset;
            byte[] buf = new byte[(int) Math.min(avail, 4 * 1024 * 1024)];
            int n = raf.read(buf);
            if (n <= 0) return false;
            // 行对齐消费（v2.0.25 修复）：只消费到最后一个完整 '\n'，尾部不完整行
            // （半条 JSON 或半个多字节 UTF-8 字符）留在文件里下轮再读。
            // 旧实现 lastOffset += n 先行推进再解析，写入方尚未写完的尾行字节被永久
            // 跳过（事件丢失/乱码）；'\n' 不会出现在多字节 UTF-8 序列内部，按它截断
            // 天然是字符安全边界，无需 CharsetDecoder 容错。
            int limit = -1;
            for (int b = n - 1; b >= 0; b--) {
                if (buf[b] == '\n') { limit = b; break; }
            }
            if (limit < 0) return false;   // 本次没有完整行：不推进 offset，等待下次
            lastOffset += limit + 1;
            String text = new String(buf, 0, limit + 1, StandardCharsets.UTF_8);
            boolean changed = false;
            boolean inJson = false, str = false, esc = false;
            int lineStart = 0;
            int depth = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!inJson) {
                    if (c == '{') { inJson = true; depth = 1; }
                    continue;
                }
                if (esc) { esc = false; continue; }
                if (str) {
                    if (c == '\\') esc = true;
                    else if (c == '"') str = false;
                    continue;
                }
                if (c == '"') { str = true; continue; }
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {   // JSON 对象闭合：截取本行并解析
                        int end = text.indexOf('\n', i);
                        String line = text.substring(lineStart, end < 0 ? text.length() : end).trim();
                        lineStart = (end < 0 ? text.length() : end + 1);
                        if (end < 0) i = text.length(); else i = end;
                        inJson = false;
                        if (line.startsWith("{") && line.endsWith("}")) {
                            if (parseLine(line)) changed = true;
                        }
                    }
                }
            }
            // 尾部不完整 JSON 行：不会出现——消费范围已行对齐（截止最后一个 '\n'）
            return changed;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析一条事件补丁（type=append/replace/truncate）。解析异常返回 false 不中断整体。 */
    private boolean parseLine(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String type = o.optString("type", "");
            long rev = o.optLong("revision", -1);
            if (rev >= 0) lastRevision = rev;
            if ("truncate".equals(type)) {
                pendingText.setLength(0);
                return true;
            }
            if (!"append".equals(type) && !"replace".equals(type)) return false;
            JSONArray msgs = o.optJSONArray("messages");
            if (msgs == null) return false;
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                emitMessage(m, rev);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 一条 message → 0 或 1 个渲染事件（user 不在实时流中重复渲染，工具调用/结果按需） */
    private void emitMessage(JSONObject m, long rev) {
        String role = m.optString("role", "");
        // 流式过程中 assistant 消息可能出现在多个 revision 中（逐 token 落盘）；
        // content 字段取整段：text 事件直接累入 pendingText 缓冲。
        String content = m.optString("content", "");
        String reasoning = m.optString("reasoning_content", "");
        String name = m.optString("name", "");
        // tool_run_state 表明是工具调用状态更新（completed/running 等）
        String toolState = m.optString("tool_run_state", "");

        if ("tool".equals(role)) {
            Event ev = new Event();
            ev.kind = "tool";
            ev.role = "tool";
            ev.toolName = name;
            ev.content = content;
            ev.revision = rev;
            out.add(ev);
            return;
        }
        if ("assistant".equals(role)) {
            if (!reasoning.isEmpty()) {
                Event ev = new Event();
                ev.kind = "reasoning";
                ev.role = "assistant";
                ev.content = reasoning;
                ev.revision = rev;
                out.add(ev);
            }
            if (content.isEmpty()) return;
            // 关键去重：reasonix 生成的 content 是「当前流式 so far 全量」，
            // append 每 revision 重发同一 message 的最新快照。
            String pend = pendingText.toString();
            if (content.startsWith(pend)) {
                // 正常增长：只发增量
                String delta = content.substring(pend.length());
                pendingText.append(delta);
                if (!delta.isEmpty()) {
                    Event ev = new Event();
                    ev.kind = "text";
                    ev.role = "assistant";
                    ev.content = delta;
                    ev.revision = rev;
                    out.add(ev);
                }
            } else if (pend.startsWith(content)) {
                // 快照缩短（replace/compact 重写比之前短）：静默重置缓冲，
                // 不把整段重发当新文本渲染（旧实现会整段重复显示）
                pendingText.setLength(0);
                pendingText.append(content);
            } else {
                // 新一轮 turn（用户提问后）：content 与 pending 前缀不重合 → 整段为新文本
                pendingText.setLength(0);
                pendingText.append(content);
                Event ev = new Event();
                ev.kind = "text";
                ev.role = "assistant";
                ev.content = content;
                ev.revision = rev;
                out.add(ev);
            }
        }
        // role=user：由 UI 在发送时本地回显，不重复渲染
    }
}

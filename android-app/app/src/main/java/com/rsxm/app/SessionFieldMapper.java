package com.rsxm.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * reasonix 会话 jsonl → GUI 字段映射。
 *
 * reasonix 的会话保存为 ~/.reasonix/projects/<项目>/sessions/<会话>.jsonl，
 * 每行一个 JSON 事件，关键字段：
 *   - role:      user | assistant | tool
 *   - content:   字符串（文本）或 parts 数组 {type:text|tool_use|tool_result, text, ...}
 *   - ts / timestamp / created_at: 时间戳（可能为秒或毫秒）
 *   - model:     assistant 消息的模型名（如 deepseek-v4-flash）
 *   - usage / cost: assistant 消息的 token / 费用统计
 *   - type / id / session: 事件类型与 id（每行可能为此类包装，message 对象在 "message" 字段下）
 *
 * 本类把 jsonl 增量解析为稳定的字段化消息列表（MappedMessage: id/role/tsText/model/
 * content/usageText），供 GUI 气泡渲染；重复解析幂等（记录已处理字节偏移）。
 */
public class SessionFieldMapper {

    /** GUI 可渲染的一条消息（CLI 字段 → 原生控件映射结果） */
    public static class MappedMessage {
        public String id = "";
        public String role = "user";          // user | assistant | tool
        public String tsText = "";            // 本地时间 HH:mm:ss
        public String model = "";             // assistant 模型名
        public String content = "";           // 纯文本正文
        public String usageText = "";         // token/费用统计
        public String toolName = "";          // tool 显示名

        public boolean isUser() { return "user".equals(role); }
        public boolean isAssistant() { return "assistant".equals(role); }
        public boolean isTool() { return "tool".equals(role); }
    }

    private final File sessionFile;
    private long lastOffset = 0;              // 已解析到文件的字节偏移（增量续读）
    private final List<MappedMessage> messages = new ArrayList<>();

    public SessionFieldMapper(File sessionFile) {
        this.sessionFile = sessionFile;
    }

    /** 当前映射的 jsonl 路径 */
    public File file() { return sessionFile; }

    /** 是否已有内容 */
    public boolean hasMessages() { return !messages.isEmpty(); }

    /** 已解析消息列表（只读使用） */
    public List<MappedMessage> all() { return messages; }

    /** 增量解析：读取自 lastOffset 起的新 jsonl 行并追加到 messages；无文件/无新增返回 false */
    public synchronized boolean poll() {
        if (sessionFile == null || !sessionFile.exists()) return false;
        long size = sessionFile.length();
        if (size <= lastOffset) return false;
        try (RandomAccessFile raf = new RandomAccessFile(sessionFile, "r")) {
            raf.seek(lastOffset);
            long avail = size - lastOffset;
            byte[] buf = new byte[(int) Math.min(avail, 4 * 1024 * 1024)];
            int n = raf.read(buf);
            if (n <= 0) return false;
            String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
            lastOffset += n;
            // 可能切到多字节字符中间：丢弃最后一个不完整 UTF-8 字节（≤3）回退
            int cut = 0;
            for (int i = chunk.length() - 1; i >= 0 && i > chunk.length() - 4; i--) {
                char c = chunk.charAt(i);
                if (c == '\n') break;
                if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)
                        || (c >= 0x80 && (c & 0xC0) == 0x80)) {
                    cut = i;
                } else {
                    break;
                }
            }
            String text = cut > 0 ? chunk.substring(0, cut) : chunk;
            lastOffset -= (chunk.length() - text.length());
            boolean changed = false;
            for (String line : text.split("\n")) {
                String s = line.trim();
                if (s.isEmpty() || !s.startsWith("{")) continue;
                MappedMessage m = parseLine(s);
                if (m != null) {
                    messages.add(m);
                    changed = true;
                }
            }
            return changed;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 解析一行 jsonl 为字段化消息；结构不符返回 null */
    private MappedMessage parseLine(String line) {
        try {
            JSONObject o = new JSONObject(line);
            // 字段容器：message 包装（"message": {role, content, ...}）或平铺
            JSONObject root = o;
            if (o.has("message") && o.opt("message") instanceof JSONObject) {
                root = o.getJSONObject("message");
            }
            String role = firstOf(root, "role", "kind", "");
            role = normalizeRole(role);
            if (role.isEmpty()) return null;

            MappedMessage m = new MappedMessage();
            m.id = firstOf(root, "id", firstOf(o, "id", ""));
            m.role = role;
            m.tsText = fmtTs(firstTs(root, o));
            m.model = firstOf(root, "model", "");
            m.content = extractContent(root);
            m.usageText = extractUsage(root);
            if ("tool".equals(role)) {
                m.toolName = firstOf(root, "tool_name", firstOf(root, "name", ""));
            }
            if (m.content.isEmpty() && m.toolName.isEmpty()
                    && !m.isUser() && !m.isAssistant()) {
                // 空 tool 事件（如 tool_result 无文本）仍保留，仅内容置空
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeRole(String role) {
        if (role == null) return "";
        String r = role.trim().toLowerCase(Locale.ROOT);
        if (r.contains("assistant")) return "assistant";
        if (r.contains("user")) return "user";
        if (r.contains("tool") || r.contains("function")) return "tool";
        return "";
    }

    private static String firstOf(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            if (o.has(k)) {
                Object v = o.opt(k);
                if (v != null) return String.valueOf(v);
            }
        }
        return "";
    }

    /** 提取 content：字符串，或 parts 数组（text 拼接；tool_use 记工具名；tool_result 记文本） */
    private static String extractContent(JSONObject root) {
        Object c = root.opt("content");
        if (c == null || JSONObject.NULL.equals(c)) return "";
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) c;
            for (int i = 0; i < arr.length(); i++) {
                Object it = arr.opt(i);
                if (!(it instanceof JSONObject)) continue;
                JSONObject part = (JSONObject) it;
                String type = firstOf(part, "type", "");
                if ("text".equals(type)) {
                    sb.append(firstOf(part, "text", ""));
                } else if ("tool_use".equals(type)) {
                    String name = firstOf(part, "name", "tool");
                    sb.append("【工具调用: ").append(name).append("】");
                } else if ("tool_result".equals(type)) {
                    Object tc = part.opt("content");
                    if (tc instanceof String) {
                        sb.append((String) tc);
                    } else if (tc instanceof JSONArray) {
                        for (int j = 0; j < ((JSONArray) tc).length(); j++) {
                            Object it2 = ((JSONArray) tc).opt(j);
                            if (it2 instanceof JSONObject) {
                                sb.append(firstOf((JSONObject) it2, "text", ""));
                            } else if (it2 != null) {
                                sb.append(it2);
                            }
                        }
                    }
                } else {
                    Object t2 = part.opt("text");
                    if (t2 != null) sb.append(t2);
                }
            }
            return sb.toString();
        }
        return String.valueOf(c);
    }

    /** 提取 usage/cost 统计：usage.{in_tokens,out_tokens}、cost 金额 */
    private static String extractUsage(JSONObject root) {
        try {
            JSONObject u = root.optJSONObject("usage");
            if (u != null) {
                long in = u.optLong("in_tokens", u.optLong("input_tokens", -1));
                long out = u.optLong("out_tokens", u.optLong("output_tokens", -1));
                StringBuilder sb = new StringBuilder();
                if (in >= 0) sb.append("↑").append(in);
                if (out >= 0) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append("↓").append(out);
                }
                if (sb.length() > 0) return sb.toString();
            }
            double cost = root.optDouble("cost", -1);
            if (cost >= 0) return String.format(Locale.ROOT, "$%.4f", cost);
        } catch (Exception ignored) {}
        return "";
    }

    /** 时间戳兜底取 ts/dedup、timestamp、created_at；可能秒/毫秒 */
    private static long firstTs(JSONObject root, JSONObject wrapper) {
        Object[] candidates = {
                root.opt("ts"), root.opt("timestamp"),
                wrapper.opt("ts"), wrapper.opt("timestamp"),
                root.opt("created_at"), wrapper.opt("created_at"),
                root.opt("created"), wrapper.opt("created"),
        };
        for (Object o : candidates) {
            if (o instanceof Number) {
                long v = ((Number) o).longValue();
                // 秒级（10 位）或毫秒级（13 位）都转毫秒
                if (v < 100000000000L) v *= 1000L;
                return v;
            }
            if (o instanceof String) {
                try {
                    long v = Long.parseLong(((String) o).trim());
                    if (v < 100000000000L) v *= 1000L;
                    return v;
                } catch (Exception ignored) {}
            }
        }
        return 0L;
    }

    private static final SimpleDateFormat TS_FMT = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    static {
        TS_FMT.setTimeZone(TimeZone.getDefault());
    }

    private static String fmtTs(long ms) {
        if (ms <= 0) return "";
        return TS_FMT.format(new Date(ms));
    }
}
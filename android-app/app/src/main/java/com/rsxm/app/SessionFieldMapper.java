package com.rsxm.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
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

    /** 增量解析：按字节读取并用 CharsetDecoder 增量解码（避免多字节 UTF-8 在块边界截断，
     *  也避免字符/字节偏移混用导致的错位重复读）。无文件/无新增返回 false。 */
    public synchronized boolean poll() {
        if (sessionFile == null || !sessionFile.exists()) return false;
        long size = sessionFile.length();
        if (size <= lastOffset) return false;
        try (RandomAccessFile raf = new RandomAccessFile(sessionFile, "r")) {
            raf.seek(lastOffset);
            long avail = size - lastOffset;
            byte[] buf = new byte[(int) Math.min(avail, 2 * 1024 * 1024)];
            int n = raf.read(buf);
            if (n <= 0) return false;
            lastOffset += n;
            java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String text;
            try {
                CharBuffer cb = dec.decode(java.nio.ByteBuffer.wrap(buf, 0, n));
                text = cb.toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                // 块尾可能是不完整多字节字符：仅解码完整前缀，剩余字节回退给下次轮询
                int valid = 0;
                while (valid < n) {
                    byte b = buf[valid];
                    int rem = (b & 0x80) == 0 ? 1
                            : (b & 0xE0) == 0xC0 ? 2
                            : (b & 0xF0) == 0xE0 ? 3
                            : (b & 0xF8) == 0xF0 ? 4 : 1;
                    if (valid + rem > n) break;
                    valid += rem;
                }
                lastOffset -= (n - valid);
                text = new String(buf, 0, valid, StandardCharsets.UTF_8);
            }
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

    /** 解析一行 jsonl 为字段化消息；结构不符返回 null。
     *  兼容 reasonix 会话文件多种事件形态：
     *   - 平铺 {role, content(, ts, model, usage)}
     *   - message 包装 {type:"user_message"/..., message:{...}} 或 {message:{role,...}}
     *   - events 分支 {event_index, type, message:{...}}
     *   - /memory、steer 等事件（无 message 对象/无 role）→ null 忽略 */
    private MappedMessage parseLine(String line) {
        try {
            JSONObject o = new JSONObject(line);
            JSONObject root = findMessageObject(o);
            if (root == null) {
                // 无 message 容器：顶层可能有 role（平铺形态）
                if (!hasAnyRole(o)) return null;
                root = o;
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
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** 递归找含 role 的 message 对象（优先 message/event 字段下的对象） */
    private static JSONObject findMessageObject(JSONObject o) {
        // 直接含 role 的对象
        if (hasAnyRole(o)) return o;
        // message 字段
        Object msg = o.opt("message");
        if (msg instanceof JSONObject) {
            JSONObject m = (JSONObject) msg;
            if (hasAnyRole(m)) return m;
        }
        // event / payload / data 字段
        for (String k : new String[]{"event", "payload", "data", "body", "input"}) {
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                JSONObject m = (JSONObject) v;
                if (hasAnyRole(m)) return m;
                Object msg2 = m.opt("message");
                if (msg2 instanceof JSONObject && hasAnyRole((JSONObject) msg2)) return (JSONObject) msg2;
                JSONObject deep = findMessageObject(m);
                if (deep != null) return deep;
            }
        }
        return null;
    }

    private static boolean hasAnyRole(JSONObject o) {
        return o != null && (o.has("role") || o.has("kind"));
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
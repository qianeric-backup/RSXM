package com.rsxm.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * reasonix serve「transcript 投影」中的一条记录（transcript record）。
 *
 * <p>这是 GUI 回显**唯一**的内容来源：上游把服务端会话投影成一条条 record
 * （`GET /transcript/snapshot` / `GET /transcript/follow`，Transcript v2 协议，
 * 与官方桌面版 ChatSource、以及 TUI 渲染的是同一份数据），TUI 只是它的一个渲染器。
 * 因此这里直接消费 record，而不是解析 TUI 屏幕字节流，也不会出现边框/光标定位/状态行。
 *
 * <p>实测字段（reasonix v1.39.3，`/transcript/snapshot`）：
 * <pre>
 * record := { id, order, message: {...}, refs: [] }
 * message.role = user      → { content, createdAt, historyTurn }
 *              | assistant → { content, workDurationMs, toolCalls:[{id,name,arguments,resolvedName,resolvedReadOnly}] }
 *              | tool      → { content, toolCallId, toolName, execution:{kind,shell,state,exitCode,durationMs,...},
 *                              toolResultError? }
 *              | notice    → { content, code, level }
 * </pre>
 *
 * 流式期间同一个 record（同 {@link #id}）会被反复更新（ChatSource 的语义是
 * "Streaming updates notify the target assistant node"，即原地更新节点），所以 UI 侧
 * 必须按 id 做 upsert，而不是追加。
 */
public class TranscriptRecord {

    /** 顶层 record 的稳定 id（`m:<messageId>` / `tool:<toolCallId>` / `<...>:notice:<n>`） */
    public String id = "";
    /** 全局递增顺序（分页/排序依据） */
    public long order = -1;

    public String messageId = "";
    /** user | assistant | tool | notice | ... */
    public String role = "";
    public String content = "";

    /** tool 记录：工具名与调用 id（用于与 assistant 记录里的 toolCalls 关联参数） */
    public String toolName = "";
    public String toolCallId = "";

    /** user 记录：创建时间（毫秒） */
    public long createdAt = 0;
    /** assistant 记录：该轮耗时（毫秒） */
    public long workDurationMs = 0;

    /** tool 记录：执行状态（completed / failed / running …） */
    public String execState = "";
    public int execExitCode = -1;
    public String execKind = "";

    /** notice 记录：分级信息（code / level） */
    public String code = "";
    public String level = "";

    /** tool 记录的错误说明（toolResultError，字符串或对象） */
    public String error = "";

    /** assistant 记录里的工具调用（name + 原始 arguments JSON） */
    public final List<Call> calls = new ArrayList<>();

    /** assistant 记录里的单次工具调用 */
    public static class Call {
        public String id = "";
        public String name = "";
        public String resolvedName = "";
        public String arguments = "";
    }

    public boolean isUser() { return "user".equals(role); }
    public boolean isAssistant() { return "assistant".equals(role); }
    public boolean isTool() { return "tool".equals(role); }
    public boolean isNotice() { return "notice".equals(role); }

    /** UI 索引键：优先用 record 自身 id，缺失时退化为 role+order */
    public String key() {
        if (!id.isEmpty()) return id;
        return role + ":" + order + ":" + messageId;
    }

    /** 是否工具执行失败（非 0 退出码 / failed 状态 / 有错误说明） */
    public boolean toolFailed() {
        if (!error.isEmpty()) return true;
        if (!execState.isEmpty() && !"completed".equals(execState)) return true;
        return execExitCode > 0;
    }

    /** 解析一条 record（兼容直接给出 message 对象的情况） */
    public static TranscriptRecord parse(JSONObject wrapper) {
        if (wrapper == null) return null;
        JSONObject m = wrapper.optJSONObject("message");
        if (m == null) m = wrapper;

        TranscriptRecord r = new TranscriptRecord();
        r.id = wrapper.optString("id", m.optString("recordId", ""));
        r.order = wrapper.optLong("order", -1);
        r.messageId = m.optString("messageId", "");
        r.role = m.optString("role", "");
        r.content = m.optString("content", "");
        r.toolName = m.optString("toolName", "");
        r.toolCallId = m.optString("toolCallId", "");
        r.createdAt = m.optLong("createdAt", 0);
        r.workDurationMs = m.optLong("workDurationMs", 0);
        r.code = m.optString("code", "");
        r.level = m.optString("level", "");

        JSONObject ex = m.optJSONObject("execution");
        if (ex != null) {
            r.execState = ex.optString("state", "");
            r.execExitCode = ex.optInt("exitCode", -1);
            r.execKind = ex.optString("kind", "");
        }
        if (m.has("toolResultError") && !m.isNull("toolResultError")) {
            Object e = m.opt("toolResultError");
            r.error = (e instanceof String) ? (String) e : String.valueOf(e);
        }
        JSONArray calls = m.optJSONArray("toolCalls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.optJSONObject(i);
                if (c == null) continue;
                Call call = new Call();
                call.id = c.optString("id", "");
                call.name = c.optString("name", "");
                call.resolvedName = c.optString("resolvedName", "");
                call.arguments = c.optString("arguments", "");
                r.calls.add(call);
            }
        }
        return r;
    }

    /** 解析 record 数组（跳过无法解析的项） */
    public static List<TranscriptRecord> parseArray(JSONArray arr) {
        List<TranscriptRecord> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            TranscriptRecord r = parse(o);
            if (r != null && !r.role.isEmpty()) out.add(r);
        }
        return out;
    }
}

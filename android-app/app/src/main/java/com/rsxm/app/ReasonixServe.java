package com.rsxm.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Reasonix Serve 无头引擎客户端（v2.1.0 新功能）。
 *
 * 依据 reasonix 1.31 `reasonix serve --help` 与实测契约（HTTP+JSON，127.0.0.1 可直连
 * —— proot/chroot 与宿主共享网络命名空间，guest 端口在 app 侧直接可见）：
 *
 *   GET  /status               → {running, runtimeState:{phase,running,turnStatus,activity,...},
 *                                 toolApprovalMode, label, used, window, sessionPath, sessionName,
 *                                 goal, goalStatus, effort:{levels,current}, ...}
 *   GET  /sessions             → JSON 数组（已保存会话）
 *   GET  /history              → [{role, content}...]（含 system；渲染时过滤）
 *   POST /submit {"input":...} → 202（异步受理；用 /status 轮询 running）
 *   POST /cancel               → 中断当前回合
 *   POST /new                  → 开新会话
 *   GET  /todos                → JSON 数组
 *   GET  /checkpoints          → [{turn, prompt, time, canCode, canConversation, coverage}]
 *   POST /rewind               → 回溯到 checkpoint（body {"turn":N}，端点按实测/文档自适应）
 *   GET  /tool-approval-mode   → 200（文本或 JSON）
 *   POST /tool-approval-mode {"mode":"manual|ask|auto|acceptEdits|dontAsk|plan|bypassPermissions"} → 204
 *   GET  /models               → {current, default, label, models[]}
 *
 * 认证：--auth token 时所有请求带 Authorization: Bearer <token>。
 * token 由 app 生成落 guest /root/.rsxm-serve-token（宿主侧同路径可读），
 * 端口落 /root/.rsxm-serve-port（--port-file）。
 */
public class ReasonixServe {

    /** guest 内约定路径（= 宿主 filesDir/rootfs/root/ 下同名文件） */
    public static final String TOKEN_FILE_GUEST = "/root/.rsxm-serve-token";
    public static final String PORT_FILE_GUEST = "/root/.rsxm-serve-port";
    public static final String LOG_FILE_GUEST = "/root/.rsxm-serve.log";
    /** proot 模式 guest /root → 宿主 rootfs/root */
    public static final String PORT_FILE_HOST_SUFFIX = "rootfs/root/.rsxm-serve-port";
    public static final String TOKEN_FILE_HOST_SUFFIX = "rootfs/root/.rsxm-serve-token";
    public static final String PID_FILE_GUEST = "/root/.rsxm-serve-pid";

    private final String baseUrl;    // http://127.0.0.1:<port>
    private final String token;      // 空 = auth none

    public ReasonixServe(int port, String token) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.token = (token == null) ? "" : token.trim();
    }

    /** 从宿主侧文件读取端口（serve 启动后由 --port-file 写入）；读不到返回 -1 */
    public static int readBoundPort(File filesDir) {
        try {
            File f = new File(filesDir, PORT_FILE_HOST_SUFFIX);
            if (!f.exists()) return -1;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            // port-file 内容为 host:port
            int c = s.lastIndexOf(':');
            if (c >= 0) s = s.substring(c + 1);
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 读取 token（宿主侧可见的 token 文件） */
    public static String readToken(File filesDir) {
        try {
            File f = new File(filesDir, TOKEN_FILE_HOST_SUFFIX);
            if (!f.exists()) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 生成 32 位随机 hex token */
    public static String generateToken() {
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[16];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------- HTTP 基础 ----------------

    private static final class Resp {
        int code;
        String body = "";
    }

    private Resp get(String path, int timeoutMs) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                r.body = sb.toString().trim();
            }
        } catch (Exception e) {
            r.body = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private Resp postJson(String path, String json, int timeoutMs) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            if (json != null && !json.isEmpty()) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                r.body = sb.toString().trim();
            }
        } catch (Exception e) {
            r.body = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    // ---------------- 业务封装 ----------------

    /** 引擎是否在线（GET /status 200 即在线） */
    public boolean isUp() {
        return get("/status", 3000).code == 200;
    }

    /** GET /status 原始 JSON（失败返回 null） */
    public String status() {
        Resp r = get("/status", 3000);
        return r.code == 200 ? r.body : null;
    }

    /** 当前回合是否在运行（从 /status runtimeState 推断） */
    public boolean isRunning() {
        String s = status();
        if (s == null) return false;
        try {
            JSONObject o = new JSONObject(s);
            JSONObject rt = o.optJSONObject("runtimeState");
            if (rt != null) return rt.optBoolean("running", false);
            return o.optBoolean("running", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** GET /sessions 原始 JSON */
    public String sessions() {
        Resp r = get("/sessions", 5000);
        return r.code == 200 ? r.body : null;
    }

    /** GET /history 原始 JSON（[{role, content}...]） */
    public String history() {
        Resp r = get("/history", 5000);
        return r.code == 200 ? r.body : null;
    }

    /** POST /submit（异步受理 202；轮询 /status 判断 running 结束） */
    public boolean submit(String text) {
        JSONObject o = new JSONObject();
        try { o.put("input", text); } catch (Exception ignored) {}
        int code = postJson("/submit", o.toString(), 8000).code;
        return code == 202 || code == 200;
    }

    /** POST /cancel 中断当前回合 */
    public void cancel() {
        postJson("/cancel", "", 3000);
    }

    /** POST /new 开新会话 */
    public void newSession() {
        postJson("/new", "", 4000);
    }

    /** GET /todos 原始 JSON */
    public String todos() {
        Resp r = get("/todos", 4000);
        return r.code == 200 ? r.body : null;
    }

    /** GET /checkpoints 原始 JSON */
    public String checkpoints() {
        Resp r = get("/checkpoints", 5000);
        return r.code == 200 ? r.body : null;
    }

    /** POST /rewind（body {"turn": N}）；端点语义以实测为准，2xx 即成功 */
    public boolean rewind(int turn) {
        JSONObject o = new JSONObject();
        try { o.put("turn", turn); } catch (Exception ignored) {}
        int code = postJson("/rewind", o.toString(), 6000).code;
        return code >= 200 && code < 300;
    }

    /** GET /tool-approval-mode → 当前模式文本（ask / auto / bypassPermissions…；失败返回 null） */
    public String toolApprovalMode() {
        Resp r = get("/tool-approval-mode", 4000);
        if (r.code != 200) return null;
        try {
            JSONObject o = new JSONObject(r.body);
            return o.optString("mode", o.optString("toolApprovalMode", r.body));
        } catch (Exception ignored) {}
        return r.body.isEmpty() ? null : r.body;
    }

    /** POST /tool-approval-mode {"mode": M}（204 = 成功） */
    public boolean setToolApprovalMode(String mode) {
        JSONObject o = new JSONObject();
        try { o.put("mode", mode); } catch (Exception ignored) {}
        int code = postJson("/tool-approval-mode", o.toString(), 4000).code;
        return code >= 200 && code < 300;
    }

    /**
     * 渲染 /history 为气泡消息列表（过滤 system/tool 原生噪音，映射 role→方向）。
     * 返回 MappedMessage 列表，直接复用主界面 renderMessageBubble。
     */
    public static List<SessionFieldMapper.MappedMessage> historyToMessages(String historyJson) {
        List<SessionFieldMapper.MappedMessage> out = new ArrayList<>();
        if (historyJson == null || historyJson.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(historyJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "");
                if ("system".equalsIgnoreCase(role)) continue;   // 系统 prompt 不渲染
                String content = m.optString("content", "");
                if (content == null) content = "";
                SessionFieldMapper.MappedMessage mm = new SessionFieldMapper.MappedMessage();
                mm.role = role.toLowerCase();
                mm.content = content;
                mm.model = m.optString("model", "");
                out.add(mm);
            }
        } catch (Exception ignored) {}
        return out;
    }
}

package com.rsxm.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * reasonix serve 的「transcript 投影」客户端（Transcript v2，NDJSON over SSE）。
 *
 * <p>上游把服务端会话投影成一条条 transcript record（这是 TUI 与官方 Web UI 共用的
 * 上层内容模型，见 reasonix 内嵌文档 TRANSCRIPT_PROJECTION.md）；本类只负责把这份投影
 * 拉下来，GUI 侧不解析任何终端字节流：
 *
 * <pre>
 * GET /transcript/follow   SSE 长连接，一行一个 JSON 对象（不接受 data: 前缀亦可）
 *   首帧 → {"protocolVersion":2,"subscription":"…","snapshot":{… records …},"changes":[]}
 *   增量 → {"protocolVersion":2,"changes":[{records:[…], event:{…}, runtime:{…}, revision, commitSeq, …}]}
 * GET /transcript/snapshot 一次性快照（窗口更大，进入视图时先拉一次）
 * GET /transcript/page     同样的窗口（hasOlder 指示还有更早记录）
 * </pre>
 *
 * 鉴权：{@code Authorization: Bearer <token>}（serve --auth token）。
 * 断线指数退避重连（上限 15s）；服务端未就绪时持续重试。
 */
public class TranscriptClient {

    /** 回调（均在后台线程触发，调用方自行切主线程） */
    public interface Listener {
        /** 基线帧（含 snapshot 全量窗口） */
        void onBaseline(JSONObject frame);

        /** 增量帧（含 changes / records / event / runtime） */
        void onDelta(JSONObject frame);

        /** 连接状态变化（detail 可为空） */
        void onConnection(boolean connected, String detail);
    }

    private final String baseUrl;   // 例如 http://127.0.0.1:8787
    private final String token;
    private final Listener listener;

    /**
     * 服务端在「无新变化」时可能读完基线就结束响应（实测：不带订阅参数时首帧后立即 EOF），
     * 因此把重连当作低频轮询对待，给一个最小间隔，避免空转把 CPU/电量打满。
     */
    private static final long MIN_RECONNECT_MS = 1200;

    private volatile boolean running = false;
    private volatile Thread thread;
    private volatile HttpURLConnection conn;
    /** 已切换 /events fallback 通道（/transcript 契约缺失时；reasonix 1.38/1.39 实测无此契约） */
    private volatile boolean fallbackMode = false;

    public TranscriptClient(String baseUrl, String token, Listener listener) {
        this.baseUrl = baseUrl;
        this.token = (token == null) ? "" : token.trim();
        this.listener = listener;
    }

    public boolean isRunning() { return running; }

    /** 是否处于 /events 回退通道（true 时 GUI 用 /history 拉取渲染，事件帧仅作刷新触发） */
    public boolean isFallbackMode() { return fallbackMode; }

    /** 启动后台跟随线程（幂等：重复调用先停旧的；重连后会自动重新取基线） */
    public void start() {
        stop();
        running = true;
        Thread t = new Thread(this::loop, "transcript-follow");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public void stop() {
        running = false;
        fallbackMode = false;
        Thread t = thread;
        thread = null;
        HttpURLConnection c = conn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
        if (t != null) t.interrupt();
    }

    // ---------------- 一次性读取 ----------------

    /** GET /transcript/snapshot（失败返回 null） */
    public JSONObject fetchSnapshot(int timeoutMs) {
        return fetchJson("/transcript/snapshot", timeoutMs);
    }

    /** GET /transcript/page?before=<order>（失败返回 null） */
    public JSONObject fetchPage(long before, int timeoutMs) {
        String q = before > 0 ? ("?before=" + before) : "";
        return fetchJson("/transcript/page" + q, timeoutMs);
    }

    private JSONObject fetchJson(String path, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = open(path, timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            if (in == null) return null;
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------- 长连接跟随 ----------------

    private HttpURLConnection open(String path, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        c.setRequestProperty("Cache-Control", "no-cache");
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeoutMs);
        return c;
    }

    private void loop() {
        int backoff = 500;
        while (running) {
            HttpURLConnection c = null;
            boolean connected = false;
            boolean broken = false;      // 真断开（读取异常 / 端点不可用），区别于"服务端正常收尾"
            try {
                c = open("/transcript/follow", 0);   // 0 = 无限等待（SSE 长连接）
                conn = c;
                int code = c.getResponseCode();
                if (code != 200) {
                    if (code == 404) {
                        // 无 /transcript 契约（reasonix 1.38/1.39 实测缺失）→ 切 /events 通道
                        listener.onConnection(true, "无 /transcript 契约，切换 /events 通道");
                        enterFallback();
                        return;
                    }
                    broken = true;
                    sleepQuiet(backoff);
                    backoff = Math.min(backoff * 2, 15000);
                    continue;
                }
                backoff = 500;
                connected = true;
                listener.onConnection(true, "");
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    boolean firstLine = true;
                    while (running && (line = r.readLine()) != null) {
                        String s = line.trim();
                        if (s.startsWith("data:")) s = s.substring(5).trim();
                        // 首行内容探测：契约存在时首行是 JSON 对象；缺失时 serve 回退返回
                        // Web UI HTML（<!doctype html>）→ 切 /events fallback
                        if (firstLine) {
                            firstLine = false;
                            if (!s.startsWith("{")) {
                                listener.onConnection(true, "无 /transcript 契约，切换 /events 通道");
                                enterFallback();
                                return;
                            }
                        }
                        if (s.isEmpty() || s.charAt(0) != '{') continue;   // 注释/心跳/其它字段
                        JSONObject o;
                        try {
                            o = new JSONObject(s);
                        } catch (Exception ignored) {
                            continue;   // 半条/异常行：跳过，不中断流
                        }
                        if (o.has("snapshot")) {
                            listener.onBaseline(o);
                        } else {
                            listener.onDelta(o);
                        }
                    }
                }
            } catch (Exception e) {
                // 读取中断：只有非主动 stop 才算"断开"
                if (running) broken = true;
            } finally {
                conn = null;
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) {}
                }
                // 服务端在"无新变化"时会读完首帧就收尾（长轮询节奏），这不等于断线：
                // 只有真正读取异常/端点不可用才通知断开，否则状态行每 1.2s 闪一次"已断开，正在重连"。
                if (broken) listener.onConnection(false, "读取中断");
            }
            // 连接成功但被服务端立即收尾时按最小间隔重连（相当于轮询）；失败走退避
            if (running) sleepQuiet(connected ? MIN_RECONNECT_MS : backoff);
        }
    }

    /** 切换 /events 回退通道（SSE；事件帧驱动 GUI 拉 /history 渲染，断线退避重连） */
    private void enterFallback() {
        fallbackMode = true;
        fallbackLoop();
    }

    private void fallbackLoop() {
        int backoff = 500;
        while (running) {
            HttpURLConnection c = null;
            try {
                c = open("/events", 0);
                conn = c;
                int code = c.getResponseCode();
                if (code != 200) {
                    sleepQuiet(backoff);
                    backoff = Math.min(backoff * 2, 15000);
                    continue;
                }
                backoff = 500;
                listener.onConnection(true, "events 流");
                // 基线：通知 GUI 拉 /history 全量渲染（事件流本身无消息内容）
                JSONObject base = new JSONObject();
                try { base.put("__fallbackBaseline", true); } catch (Exception ignored) {}
                listener.onBaseline(base);
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        String s = line.trim();
                        if (s.startsWith("data:")) s = s.substring(5).trim();
                        if (s.isEmpty() || s.charAt(0) != '{') continue;
                        JSONObject o;
                        try {
                            o = new JSONObject(s);
                        } catch (Exception ignored) {
                            continue;
                        }
                        try { o.put("__fallbackEvent", true); } catch (Exception ignored) {}
                        listener.onDelta(o);
                    }
                }
            } catch (Exception e) {
                if (running) listener.onConnection(false, "events 断开，重连中…");
            } finally {
                conn = null;
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) {}
                }
            }
            if (running) sleepQuiet(backoff);
            backoff = Math.min(backoff * 2, 15000);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

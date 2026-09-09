package com.rsxm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub 集成：access token 管理、登录验证、触发 Actions 自动打包、下载构建 APK。
 *
 * 仓库固定为 rsxm 项目（RSXM），workflow 为 .github/workflows/build-release.yml。
 * 所有网络操作同步执行，调用方需自行放到后台线程。
 */
public class GitHubManager {
    private static final String TAG = "GitHubManager";

    /** 目标仓库：owner/repo */
    public static final String REPO = "qianeric-backup/RSXM";
    /** Actions workflow 文件名（不含扩展名，dispatch 用文件名即可） */
    public static final String WORKFLOW = "build-release.yml";
    /** 操作类型：普通权限 token 即可触发 workflow_dispatch；actions 读权限用于查 run/artifact */
    private static final String API = "https://api.github.com";

    private final Context ctx;
    private final SharedPreferences sp;

    public GitHubManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = ctx.getSharedPreferences("gh_prefs", Context.MODE_PRIVATE);
    }

    // ---------------- Token 管理 ----------------

    public String getToken() {
        return sp.getString("gh_token", "");
    }

    public void saveToken(String token) {
        sp.edit().putString("gh_token", token.trim()).apply();
    }

    public void clearToken() {
        sp.edit().remove("gh_token").apply();
    }

    public boolean hasToken() {
        return !getToken().isEmpty();
    }

    // ---------------- HTTP 基础 ----------------

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL(API + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        String tok = getToken();
        if (!tok.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + tok);
        }
        c.setRequestProperty("User-Agent", "RSXM/1.0");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    /** 执行请求，返回 {code, body} */
    private int[] exec(HttpURLConnection c, String body, StringBuilder out) throws Exception {
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in != null) out.append(readAll(in));
        } catch (Exception e) {
            Log.w(TAG, "read body failed", e);
        }
        c.disconnect();
        return new int[]{code};
    }

    // ---------------- 登录 / 用户信息 ----------------

    public static class LoginResult {
        public boolean ok;
        public String login;
        public String name;
        public String avatar;
        public String error;
    }

    /** 用当前 token 验证登录态：GET /user */
    public LoginResult fetchUser() {
        LoginResult r = new LoginResult();
        try {
            HttpURLConnection c = open("/user", "GET");
            StringBuilder body = new StringBuilder();
            int[] res = exec(c, null, body);
            if (res[0] == 200) {
                JSONObject o = new JSONObject(body.toString());
                r.ok = true;
                r.login = o.optString("login", "");
                r.name = o.optString("name", "");
                r.avatar = o.optString("avatar_url", "");
            } else {
                r.error = "HTTP " + res[0] + ": " + (body.length() > 0 ? body : "(无响应)");
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchUser failed", e);
            r.error = e.getMessage();
        }
        return r;
    }

    /** 验证并保存 token（登录）：成功返回 true 并记忆用户 */
    public LoginResult login(String token) {
        saveToken(token);
        LoginResult r = fetchUser();
        if (r.ok) {
            sp.edit()
                    .putString("gh_login", r.login)
                    .putString("gh_name", r.name)
                    .putString("gh_avatar", r.avatar)
                    .apply();
        } else {
            clearToken();
        }
        return r;
    }

    public String savedLogin() {
        return sp.getString("gh_login", "");
    }

    // ---------------- 触发自动打包 ----------------

    /** 触发 workflow_dispatch（ref=main），返回 run id 或错误 */
    public String triggerBuild() throws Exception {
        JSONObject body = new JSONObject().put("ref", "main");
        // version input：读取应用当前版本号传入，打包产物与本地一致
        try {
            String ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            body.put("inputs", new JSONObject().put("version", ver));
        } catch (Exception ignored) {}
        HttpURLConnection c = open("/repos/" + REPO + "/actions/workflows/" + WORKFLOW + "/dispatches", "POST");
        StringBuilder out = new StringBuilder();
        int[] res = exec(c, body.toString(), out);
        if (res[0] == 204 || res[0] == 201 || res[0] == 200) {
            return "";
        }
        String err = out.length() > 0 ? out.toString() : ("HTTP " + res[0]);
        throw new Exception("触发失败: " + err);
    }

    /** 查询 workflow 最近一次运行：返回 run id/slug/status/conclusion */
    public JSONObject latestRun(String runId) throws Exception {
        String path = runId != null && !runId.isEmpty()
                ? "/repos/" + REPO + "/actions/runs/" + runId
                : "/repos/" + REPO + "/actions/runs?workflow=" + WORKFLOW + "&per_page=1";
        HttpURLConnection c = open(path, "GET");
        StringBuilder out = new StringBuilder();
        int[] res = exec(c, null, out);
        if (res[0] != 200) throw new Exception("查询运行失败 HTTP " + res[0]);
        JSONObject o = new JSONObject(out.toString());
        if (runId != null && !runId.isEmpty()) return o;
        JSONArray arr = o.optJSONArray("workflow_runs");
        if (arr != null && arr.length() > 0) return arr.getJSONObject(0);
        return null;
    }

    /** 轮询直到运行结束；maxSec 超时返回 null */
    public JSONObject waitRunFinish(String runId, int maxSec, java.util.function.Consumer<String> progress) throws Exception {
        long deadline = System.currentTimeMillis() + maxSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JSONObject run = latestRun(runId);
            if (run == null) throw new Exception("找不到构建运行");
            String status = run.optString("status", "");
            String conclusion = run.optString("conclusion", "");
            if (progress != null) progress.accept("状态: " + status + " / " + conclusion);
            if ("completed".equals(status)) return run;
            Thread.sleep(5000);
        }
        return null;
    }

    /** 列出运行产物（artifacts），找到 APK zip */
    public JSONObject latestArtifact(String runId) throws Exception {
        HttpURLConnection c = open("/repos/" + REPO + "/actions/runs/" + runId + "/artifacts", "GET");
        StringBuilder out = new StringBuilder();
        int[] res = exec(c, null, out);
        if (res[0] != 200) throw new Exception("查询产物失败 HTTP " + res[0]);
        JSONArray arr = new JSONObject(out.toString()).optJSONArray("artifacts");
        if (arr == null || arr.length() == 0) return null;
        return arr.getJSONObject(0);
    }

    // ---------------- 下载 APK ----------------

    /**
     * 下载 artifact zip 并解出 app-release.apk 到 dest。
     * artifact url 需要跟随 302（GitHub 签名下载地址）。
     */
    public File downloadApk(String artifactId, File destDir) throws Exception {
        destDir.mkdirs();
        URL url = new URL("https://api.github.com/repos/" + REPO + "/actions/artifacts/" + artifactId + "/zip");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(300000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        String tok = getToken();
        if (!tok.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestProperty("User-Agent", "RSXM/1.0");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new Exception("下载失败 HTTP " + code);
        }
        File apk = null;
        try (ZipInputStream zis = new ZipInputStream(c.getInputStream())) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().endsWith(".apk")) {
                    File out = new File(destDir, "reasonix-proot-github.apk");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    apk = out;
                    break;
                }
            }
        }
        c.disconnect();
        if (apk == null) throw new Exception("产物中没有 APK 文件");
        return apk;
    }
}
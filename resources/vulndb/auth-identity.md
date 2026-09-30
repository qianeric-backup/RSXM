# 认证与身份面速查（JWT / SSO / 验证码 / 口令）

## JWT

- 算法混淆：`alg:none`（删除签名段）、`RS256→HS256`（用公钥作 HMAC 密钥）。
- 弱密钥爆破：`hashcat -m 16500 jwt.txt rockyou.txt`；`jwt_tool` 探测。
- 头注入：`kid` 指向可控文件（`../../etc/passwd`）、`jwk` 注入自签公钥。
- 过期/篡改：修改 `exp`/`role` 重放，观察服务端是否校验。

## SSO / OAuth

- 状态参数固定：`state` 不校验 → 登录 CSRF 劫持。
- redirect_uri 开放重定向：`https://TARGET/cb?redirect_uri=http://EVIL` 窃取 code。
- token 混淆：authorization code 换 access token 的 client 不匹配。
- 会话固定：登录前后 SESSION ID 不变 → 配合开放重定向投毒。

## 验证码绕过 / 撞库

- 复用：同一验证码 CAPTCHA_ID 重放多次；仅前端校验时直接跳过。
- OCR：识别低对抗验证码（模板 OCR_TEMPLATE）；图形干扰强则走接口复用。
- 撞库：字典 WORDLIST + 代理池 PROXY_POOL + 限速 RATE（防封与审计），
  按响应差异（「用户不存在 / 密码错误」）做账号枚举。

## 口令攻击

- 在线爆破：`hydra -L users.txt -P pass.txt TARGET http-post-form "/login:user=^USER^&pass=^PASS^:F=错误"`
- 离线：`hashcat -m 0 dump.txt rockyou.txt`（MD5）、`-m 1000`（NTLM）、`-m 22000`（WPA）。
- 喷洒：固定弱口令横测多账号（`Spray` 模式），单账号低次数避免锁定。

## 修复要点

JWT 服务端校验算法白名单 + 密钥强随机；OAuth 校验 state 与 redirect_uri 精确匹配；
验证码服务端绑定会话 + 一次性；登录接口统一错误文案 + 限速与锁定策略。

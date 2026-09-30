# 漏洞库（RSXM dsh purge · 内置速查）

本库为离线速查手册，按利用面分类。每份文件包含：探测要点、常用 PAYLOAD
模板、验证与修复建议。reasonix 可直接按 `/vulndb <类别>` 或阅读本目录使用。

| 文件 | 覆盖面 |
| --- | --- |
| `web-injection.md` | SQLi / XSS / SSTI / 命令注入 |
| `web-logic.md` | 文件上传 / SSRF / 反序列化 / WAF 绕过 / 越权逻辑 |
| `auth-identity.md` | JWT / SSO / 验证码绕过 / 撞库 / 口令攻击 |
| `intranet-post.md` | 内网扫描 / AD 域 / 提权 / 横向移动 / 凭据 |
| `cloud-mobile.md` | 云元数据 / 容器 K8s / 移动端 / 小程序 |
| `cve-quick.md` | 高价值 CVE 速查（log4j / Spring / 网关类 / 邮件类等） |

## 用法

- 面板「资源 → 漏洞库」查看与安装到 guest（`~/.reasonix/purge/vulndb/`）。
- reasonix 会话中：`cat ~/.reasonix/purge/vulndb/<文件>` 或按全局 skill
  `rsxm-redteam-vulndb` 检索（skill 已内置并自动加载）。
- 每个条目坚持：TARGET 占位化、先探测后利用、记录结果并清理痕迹。

## 更新

- 内置版本随 APK 升级自动刷新；面板「GitHub 同步」可从
  `https://raw.githubusercontent.com/qianeric-backup/RSXM/main/resources/vulndb/`
  拉取最新单文件（覆盖同名文件，新增文件需完整同步）。

# RSXM dsh purge · 资源中心（GitHub 同步源）

本目录是 RSXM 应用内「dsh purge → 资源」页签的 GitHub 同步源。
应用内同步地址：`https://raw.githubusercontent.com/qianeric-backup/RSXM/main/resources/`

| 目录 | 内容 | 部署目标（guest） |
| --- | --- | --- |
| `vulndb/` | 漏洞速查库（web-injection / web-logic / auth-identity / intranet-post / cloud-mobile / cve-quick） | `~/.reasonix/purge/vulndb/` |
| `rules/` | 规则集（rsxm-default / redteam-operations，目标 AGENTS.md） | `~/.reasonix/rules/` |
| `skills/redteam/` | 红队 skill 包（23 个，来源 dsh-purge 上游，MIT） | `~/.reasonix/skills/redteam/`（reasonix 自动加载） |

## 使用

- APK 内置同一份资源（`assets/purge/`），离线可用；GitHub 同步用于增量更新。
- 更新流程：改本目录文件 → 推送 main → 应用内「资源 → GitHub 同步」拉取。
- 保持 `assets/purge/` 与 `resources/` 内容一致（新资源需同时放两处并随 APK 发布）。

## 许可

- `skills/redteam/` 来自 [YuJunZhiXue/dsh-purge](https://github.com/YuJunZhiXue/dsh-purge)（MIT），
  随上游更新；`vulndb/` 与 `rules/` 为本项目自建（MIT）。

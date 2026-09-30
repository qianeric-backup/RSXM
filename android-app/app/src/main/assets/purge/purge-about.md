# ⚔️ dsh purge（DeepSeek Harness 网络安全红队评测插件）· RSXM 融合版 v1.1.35

本面板由上游 [YuJunZhiXue/dsh-purge](https://github.com/YuJunZhiXue/dsh-purge) v1.1.35（MIT）的宿主侧栏 UI（清洗 / 演练台 双页）融合进 RSXM 原生界面而来。

## 在本应用中的映射

| 上游 dsh-purge（DSH 宿主） | RSXM 融合版（Reasonix + Alpine） |
| --- | --- |
| 清洗 → 补丁 | RSXM 宿主策略补丁：破甲 system_prompt、[permissions] 白名单、Guardian 禁用、YOLO 审批、全局指令与破甲 skill |
| 清洗 → 提示词 | 编辑 `~/.reasonix/break-armor-prompt.md`（会话覆盖段），可一键重新注入 [agent] system_prompt |
| 清洗 → 规则集 | `~/.reasonix/rules/` 多套 AGENTS.md / CLAUDE.md，启用写入全局指令 |
| 清洗 → Skill | 查看全局 skill（rsxm-break-armor / rsxm-android-bridge 等），与「高级设置 → SKILL」面板互通 |
| 演练台 | 授权后查看资产（/sdcard、/host-data、/host/system）、技能与运行环境（proot/chroot、adb/root 桥、Alpine 版本） |

## 使用须知

- 补丁操作（应用 / 还原）直接修改本机 `~/.reasonix/config.toml` 与 rootfs 文件，**不对外网目标联网**。
- 演练台只用于你有权管理的本机、离线靶标或已书面授权的演练环境。
- 补丁应用后需**重启环境**才完全生效（入口在补丁区与高级设置中），与上游「应用后必须重启」一致。
- 白 / 墨主题可切换（清洗台内容区）。

## 还原 / 卸载

每个补丁均可单独还原（移除对应标记与配置块）；全局 skill 与规则集在对应分区管理。卸载本面板即还原全部补丁后移除 APK 中的 purge 资产（随 APK 升级自动覆盖刷新）。

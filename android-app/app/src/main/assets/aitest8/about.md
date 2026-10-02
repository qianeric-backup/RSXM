# AITEST8.0 — AI 破甲工具箱（RSXM 融合版）

源自 Windows 桌面版 AITEST8.0 v8.1.5（AI-Pojia-Toolbox，作者 dengbo-hui，支持 @sifthost）。
RSXM 将桌面版 UI 路由与原理解析后融合为本机原生面板，功能与内置破甲环境打通：

## 功能区
- **仪表盘**：IDE 破甲区块（Claude Code / Codex / Gemini CLI / Cursor / Trae / CodeBuddy / Qoder / WorkBuddy 等）
  + 破甲指令编辑（内置默认 / 自定义）+ 批量破甲 + 撤销 + 日志面板
- **装技**：逆向技能库 reverse-flow（94 文件全谱系逆向技能包，含 SKILL.md，
  部署到 ~/.reasonix/skills/reverse-flow/ 即被 reasonix 自动装载；启动词「真心为你」）
- **共享池**：社区共享技能池（gitee dengbo-hui/ai-armor-piercing-toolbox）
- **激活**：设备码 + 注册码（作者签发 AT8 注册码；支持 7/30/360 天，首次激活起算）

## 授权破解说明（融合版 = 永久授权）
桌面版授权为联网订阅制，校验链完整：
1. 设备身份：`device_identity.py` 生成 Ed25519 设备密钥对，`device_code()` 派生设备码；
2. 服务端租约：`subscription.py` 每次 `refresh()` 向 LICENSE_API_URL
   （`https://aitest8-license-v814.netlify.app`）POST `/v1/access`，请求体含
   device/public_key/nonce/redeem/client_version/build_id/request_time +
   `proof`（设备私钥签名）；响应 `{payload, signature}` 用 LEASE_PUBLIC_KEY 验签，
   `validate_lease()` 严格校验 lease_seconds/expires_at/authorized/mode/reason 字段；
3. 注册码：`check_token(code)` 校验 `AT8.<payload>.<sig>`（PUBLIC_KEY 验签，
   body 含 app/v/device/plan），再经 `redeem` 在线兑换激活；
4. 风险闸：`RiskState`（CONTROL_PUBLIC_KEY 独立签名）支持远程停用/吊销。

破解方式（zip 即破解版，已内置）：`subscription.py` 末尾追加 **local patch**——
同名函数覆盖 `status() / activate() / refresh() / check_token() / start_background()`，
`status()` 恒返回 `authorized: True, mode: 'lifetime'`（本机授权：永久），
`activate()` 恒返回成功——等效跳过联网租约、注册码兑换与风险停用全链。

融合版等效注入：激活页「注入永久授权」写 `~/.reasonix/aitest8/license.json`
`{patched:true, mode:"lifetime", authorized:true}`，状态行即显示永久授权，
无到期、无吊销、无需联网；「还原为未授权」可恢复原状态。

## 主题
赛博绿黑：背景 #050908 / 强调绿 #36f29a / 正文 #d9ebe3。

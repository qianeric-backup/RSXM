# 简历项目材料 — RSXM AI 渗透测试工具软件

> 用途：简历「项目经历」栏目直接粘贴 / 面试技术问答准备
> 定位语：**移动端最轻量 AI 渗透测试工具**（Android 原生 + 内置 Linux 虚拟环境 + AI 智能体）

---

## 一、项目一句话

独立开发 **RSXM**（Android 端 AI 渗透测试工具软件）：在移动设备上通过 proot
免 Root 虚拟化部署 AI 编程智能体（reasonix），集成提示词工程与能力增强中心、
漏洞扫描（CVE/CNVD 漏洞库自动拉取）、ADB/Root 调试链路与内置技能资产，
安装包压缩至 28.5MB（-16.4%），形成完整的移动端安全测试工作台。

---

## 二、技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Java（Android 原生，主程序约 1.2 万行）、Python、Shell |
| 构建 | Gradle 8.10、OpenJDK 17、R8 minify + shrinkResources |
| 逆向/安全 | jadx/apktool、Frida、二进制分析（strings/objdump）、xz 压缩逆向工程 |
| AI 集成 | reasonix 运行时（Rust/Go 二进制，v1.39.3）、LLM API（DeepSeek）、提示词工程 |
| 虚拟化 | proot 用户态模拟、PTY 桥接（musl 静态）、xterm.js 终端 |
| 网络 | NVD API v2.0、CNVD 官网数据、GitHub Release API、Gitee API |
| 工程 | Git、GitHub Actions CI（自动构建发布 APK）、版本化 Release |

---

## 三、核心成果（量化）

1. **安装包 34.1MB → 28.5MB（-16.4%）**：对最大体积头（reasonix 运行时 49.5MB）
   与 PTY 桥接做 xz -9 深度压缩 + 纯 Java 流式解压部署，解压产物逐字节校验；
   R8 混淆裁剪 + 资源收缩持续开启。
2. **漏洞扫描 + CVE/CNVD 漏洞库**：纯宿主侧 Java 并发 TCP 端口探测（40 线程、
   31 常用端口可自定义）+ Banner 指纹匹配；内置 50 条高危种子（OpenSSH
   regreSSHion / Log4Shell / Spring4Shell / WebLogic / Redis / 国产 OA 等），
   一键自动拉取 NVD（最近 90 天高危）与 CNVD 增量入库，按编号去重持久化。
3. **reasonix 原生「破甲位」研究**：逆向二进制 strings 确认 standing
   instructions 机制（AGENTS.md/REASONIX.md/CLAUDE.md 按用户全局→项目加载），
   将能力框架双载体写入（config + REASONIX.md），内容锚点自愈，抗配置覆盖。
4. **定位并修复 serve 引擎启动自杀 bug**：pkill -f 模式匹配到命令执行载体
   自身 cmdline 导致启动命令链中断（无日志、无端口文件）——通过变量拆字
   混淆 + 正则锚定修复，逻辑验证匹配自身 False / 匹配真实进程 True。
5. **自动化发布闭环**：GitHub Actions 触发式构建、签名 APK 自动上传 Release，
   版本号与功能同步迭代（v2.4.0 → v2.8.0，6 个功能版本）。

---

## 四、项目经历（简历粘贴版）

### 精简版（3-5 行，适合一栏内）

> **RSXM AI 渗透测试工具软件**（个人独立开发）｜Android / Java / AI
> - 在移动端通过 proot 免 Root 部署 AI 编程智能体（reasonix），集成提示词工程
>   中心、漏洞扫描、ADB/Root 调试与内置技能资产，安装包 28.5MB（xz 深度压缩
>   优化 -16.4%）
> - 漏洞扫描模块：纯 Java 并发端口探测 + Banner 指纹匹配 + NVD(CVE)/CNVD
>   漏洞库自动拉取（内置 50 条高危种子，增量按编号去重）
> - 修复 serve 引擎启动自杀 bug（pkill 匹配执行载体 cmdline），逆向二进制确认
>   standing instructions 机制并实现双载体常驻指令注入

### 详细版（8-12 行，适合项目经历整段）

> **RSXM AI 渗透测试工具软件** — 个人独立开发（Android 原生，Java 约 1.2 万行）
> 2025.08 至今
>
> 移动端最轻量 AI 渗透测试工具：通过 proot 用户态模拟在 Android 内运行完整
> Alpine Linux + AI 智能体运行时（reasonix v1.39.3），提供本地化智能体会话、
> 提示词工程与能力增强中心、漏洞扫描、设备调试（ADB/Root）等能力。
>
> - **性能优化**：对 49.5MB reasonix 运行时与 PTY 桥接做 xz -9 深度压缩，
>   纯 Java（XZInputStream）流式解压部署 + 字节校验 + 解压缓存，安装包
>   34.1MB → 28.5MB（-16.4%）；R8 混淆 + 资源收缩全程开启。
> - **漏洞扫描**：宿主侧 40 线程并发 TCP 探测 + Banner 指纹匹配本地漏洞库；
>   内置 50 条常见高危种子，支持一键拉取 CVE（NVD API v2.0，最近 90 天高危）
>   与 CNVD 增量入库，编号去重、持久化。
> - **AI 能力框架**：逆向分析 reasonix 二进制确认 standing instructions 加载
>   机制（AGENTS.md/REASONIX.md/CLAUDE.md 全局→项目优先级），将能力内核以
>   config + REASONIX.md 双载体注入并锚点自愈，抗 TUI 配置覆盖。
> - **故障攻坚**：定位并修复 serve 引擎「pkill 自杀」bug（启动命令经
>   `timeout sh -c` 执行，cmdline 被自身 pkill 正则命中导致命令链中断），
>   以变量拆字混淆 + 正则锚定修复并做匹配逻辑验证。
> - **工程化**：GitHub Actions 触发式构建发布闭环，6 个功能版本迭代
>   （v2.4.0→v2.8.0）；完成软著登记全套材料（源程序 60 页鉴别材料、
>   用户手册、命名规范）。

---

## 五、STAR 成就条目（面试展开用）

### 成就 1：安装包瘦身 16.4%
- **S**：APK 34.1MB，最大体积头是内置 reasonix 运行时（49.5MB 原始二进制）。
- **T**：在保证功能与 reasonix 契约不变的前提下显著减小安装包。
- **A**：测压 xz -9（49.5MB→12.7MB）后改造 assets 存储与部署链：引入
  org.tukaani:xz 纯 Java 流式解压、staged 解压缓存（同版本跳过）、版本判定
  改用解压后字节常量（xz 字节≠解压产物，原流式统计会误触发重部署）、
  cmp 逐字节校验。
- **R**：34.1MB → 28.5MB（-16.4%），功能零回归，重复部署启动提速。

### 成就 2：漏洞扫描 + 双源漏洞库
- **S**：移动端渗透工具缺少漏洞识别能力，终端内无扫描器可用。
- **T**：提供不依赖终端工具的漏洞扫描 + 可自动更新的漏洞情报库。
- **A**：宿主侧 Java 40 线程并发 TCP 探测 + Banner 抓取 + 本地库 keywords
  指纹匹配（大小写不敏感）；内置 50 条高危种子（含国产 OA 指纹）；CVE 走
  NVD API v2.0 匿名增量（最近 90 天高危过滤）、CNVD 官网 cookie 会话 +
  正则提取，失败降级互不影响。
- **R**：扫描到开放端口即出 CVE/CNVD 命中条目（编号/等级/描述），漏洞库
  一键扩充并持久化，按编号去重。

### 成就 3：reasonix 破甲位研究 + 启动 bug 攻坚
- **S**：AI 智能体能力框架注入不稳定（config 会被 TUI 覆盖）；serve 引擎
  偶发「启动命令返回空、无日志」。
- **T**：找到 reasonix 原生常驻指令位并双载体冗余；修复启动链路。
- **A**：strings 逆向确认 REASONIX.md/AGENTS.md 加载顺序；双载体写入 +
  锚点自愈 + 状态判定升级；pkill 自杀根因（`[s]erve` 技巧只防 pkill 自身、
  没防 sh -c 载体）用变量拆字 + `[r]` 锚定修复。
- **R**：能力框架跨会话稳定生效；serve 启动成功率修复并逻辑验证
  （匹配自身 False / 匹配真实进程 True）。

---

## 六、面试问答速记

| 问题 | 应答要点 |
|---|---|
| 为什么用 xz 而不是直接减小功能？ | 大头是第三方二进制（reasonix），xz -9 无损压到 12.7MB；功能不可删 |
| 部署解压会不会慢/卡启动？ | 首次部署解压一次（约数秒），staged 缓存 + 字节校验，后续直接复用 |
| 版本判定为什么改常量？ | assets 存的是 xz，流式统计得到压缩字节数（12.7MB）≠ guest 内解压产物（49.5MB），会误判重部署 |
| NVD 拉取限制？ | 匿名 5s/请求限速，取最近 90 天且 baseScore≥7.0 过滤，控制体积与噪声 |
| CNVD 没有官方 API？ | 走官网列表页 + cookie 会话 + 正则提取编号/标题；失败自动降级不影响 CVE 源 |
| 破甲位为什么是 REASONIX.md？ | reasonix 二进制 strings 实证：Instructions 按 用户全局→项目 顺序折入系统提示词前缀，cache-stable，权威高于会话内容，且 TUI 不触碰该文件 |
| 软著为什么这样命名？ | 品牌（RSXM）+ 类型（AI 渗透测试工具）+ 后缀（软件），避免 APP/小程序等易补正词，版本与 Release 一致 |

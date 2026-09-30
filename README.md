# RSXM V2.0.0（原 Reasonix Proot App）

在 **Android 上通过 proot 运行 Alpine Linux 环境**，打开应用默认进入 **RSXM 原生 GUI 会话界面**（Reasonix AI 编码助手，可切换回 CLI 终端）的 APK 工程。

无需 root，无需安装 Termux。仅支持 **arm64 (arm64-v8a)** 设备。

## 功能特性

- **一键进入 RSXM（GUI 默认）**：打开 APK → 自动解压 Alpine Linux → 启动 reasonix 交互会话，**默认进入原生 GUI 会话视图**（serve 的 transcript 投影驱动 + 原生输入框）；快捷栏「视图」或返回键可切回 CLI 终端；退出 reasonix 后落到 Alpine shell。
- **完整 proot 环境**：`proot -0` 免 root 运行 Alpine 3.20（arm64），可 `apk add` 安装任意工具。
- **真实 TTY**：内置自编译 `pty-bridge`（静态 musl）创建 PTY，reasonix 的 TUI 完整可用（含鼠标滚轮滚动）。
- **屏幕自适应**：终端按手机视口自动计算行列并实时同步 PTY（旋转/软键盘自动重排）。
- **触摸滚动**：reasonix TUI 内滑动 → 模拟 SGR 滚轮事件滚动历史输出；shell 主屏滑动 → 滚动终端 scrollback；侧滑菜单内置滑动调速（档位 1~10，默认 5），档位越低滑动越慢越精细，可随时调整。
- **纯黑主题（顶边栏仅菜单）**：全局纯黑界面——顶部仅保留「☰ 菜单」入口（其余功能收进侧滑栏/高级设置）；GUI 输入框、新会话、回终端等控件均为黑底白字。
- **顶边栏（仅菜单）**：顶部快捷栏仅保留「☰ 菜单」一个入口，所有功能经侧滑栏进入（ADB / API Key / GitHub / DS2API / 更新 / 项目 / 会话 / 视图切换 / 高级设置）。
- **侧边栏精简 + 高级设置二级菜单**：侧滑栏仅保留高频项（ADB / API Key / GitHub / DS2API / 更新 / 项目 / 会话 / 高级设置）；ROOT、开发环境、SKILL、MCP、后台运行（默认开启，见下）、YOLO 免审批、滑动速度、快捷键等收进「高级设置」二级面板（点按对应行进入功能页，返回箭头关闭）。
- **双视图切换（终端 / GUI 对话）**：侧滑栏「视图切换」在 xterm.js 终端与原生会话视图间切换。原生视图的内容**全部来自 reasonix serve 的 transcript 投影**（Transcript v2 协议，与官方桌面版 ChatSource、以及 TUI 渲染的是同一份上游内容——TUI 只是这份投影的一个渲染器，见 reasonix 内嵌文档 TRANSCRIPT_PROJECTION.md）：`GET /transcript/follow`（SSE 长连接：首帧 `snapshot.records` 为全量窗口，后续帧 `changes[].records / event / runtime` 为增量；断线自动重连并重取基线）、`GET /transcript/snapshot`（进入视图/新会话时拉一次基线）；发送/中断/新会话分别走 `POST /submit`、`/cancel`、`/new`。**不解析任何终端字节流或会话文件**：旧实现把 PTY 字节流剥离 ANSI 后拼接（无法区分整屏重绘与真实新增，边框/光标定位/状态行会混入回显——即看到的“混乱”），`/events` 逐 token 事件流与 `/history` 纯文本拼接同样已删除。气泡按 record 的 `role` / `content` / `toolCalls` / `execution` 结构化渲染：user 靠右蓝底、assistant 靠左（工具调用以 `▸ 工具名 参数` 跟在正文后）、tool 灰色等宽卡片（工具名 + 参数摘要 + 输出，非 0 退出码/失败标红）、notice 系统提示黄字；同一 record 流式更新时按 id 原地覆盖（不重复插入），服务端 user record 到达后自动替换本地即时回显；顶部状态行显示 serve / transcript 连接与生成状态；serve 为**常驻引擎**，切到终端视图或其它 app 都保持在线。
- **后台保活默认开启 + serve 看门狗自愈（v2.2.9）**：「后台运行」（前台服务保活：环境与 Activity 生命周期解耦，进程不退化成 cached 进程被系统回收）**默认开启**——切到微信/浏览器等其它 app 时 proot 环境与 serve 引擎继续运行，重新打开 app 直接复用（终端 I/O 无缝续接），可在 **高级设置 → 后台运行** 关闭；serve 由宿主侧**看门狗**每 20 秒 `GET /status` 确认在线，掉线（进程被冻结/回收、环境重启）自动重新拉起并接回 transcript 跟随流，连续失败按 15s → 30s → 60s → 120s 退避（serve 自身起不来时不密集重启、不刷诊断卡片），回到前台时额外立即探测一次；Android 13+ 首次自动开启时顺带请求一次通知权限（未授权前台服务照常运行，只是常驻通知不可见）。
- **破甲环境（Guardian 已禁用）**：内置 reasonix Guardian 高风险审查会 deny 破甲任务，v2.0.5 起在 `~/.reasonix/config.toml` 写入 `guardian_model = ""`（Guardian 因模型缺失自动禁用）。
- **手机操作逻辑优化**：视图模式记忆（重启恢复上次终端/对话视图）；返回键三级逻辑（先关面板 → 原生视图先收键盘再回终端 → 才退出）；原生视图头部「新会话 / 回终端」快捷按钮；输入发送后保持焦点（多轮连续输入）；软键盘弹出时原生消息列表自动滚到底部。
- **GitHub 登录与自动打包**：主屏快捷栏与侧滑栏「GitHub」面板支持输入/保存 GitHub Personal Access Token（仅存本机 `SharedPreferences`，不写入 Linux 环境），`GET /user` 验证登录态并显示账户；一键触发仓库 [RSXM](https://github.com/qianeric-backup/RSXM) 的 `build-release.yml` workflow（`workflow_dispatch`，云端 `assembleRelease`），轮询构建状态，完成后下载构建产物 APK 并经系统安装器安装（token 需 `repo` + `actions` 权限）。
- **左侧侧滑配置菜单**（DrawerLayout）：
  - **ADB 无线调试**：guest 内自动安装 adb（国内镜像 + 国内 DNS），填写配对码/端口后一键发送配对连接命令到终端，或复制命令、直接跳转无线调试设置。
  - **API Key 配置**：随时查看/修改 DeepSeek API Key（写入 `~/.reasonix/.env`，保存后自动重启环境）。支持 **openai / anthropic / responses 三种协议**（下拉切换），可直接粘贴中转站给的**完整端点**（如 `https://host/v1/responses`）：面板自动识别协议、拆出 `base_url` 并写入精确 `request_url`；「测试连通性」按协议依次探测 `/models`、`/v1/messages`、`/responses` 三类端点。
  - **DS2API 网关（内置）**：应用启动时自动在 Linux 环境后台运行内置的 DS2API 服务（上游 [CJackHwang/ds2api](https://github.com/CJackHwang/ds2api) AGPL-3.0，v4.6.1），管理台 `http://127.0.0.1:5001/admin`（初始管理密钥 `rsxm-ds2api-admin`，首次保存配置后持久化到 `/root/ds2api/config.json`）；若旧版 DS2API App 已占用 5001 端口则内置服务不重复启动。
  - **快捷键**：侧滑栏「快捷键」弹出 reasonix 常用按键面板（Enter / Ctrl+A / Ctrl+E / Ctrl+W / Ctrl+U、↑↓、PgUp / PgDn、End、Tab / Shift+Tab、Esc、Ctrl+C、编辑门 y / n / u 等），点击条目即把对应按键序列发送到 reasonix 终端（不追加换行，面板保持打开可连续点按翻页）。
  - **MCP 服务器**：管理当前项目 `.mcp.json`（reasonix 按项目根 `.mcp.json` 的 `mcpServers` 发现 MCP 服务器）：列出已配置服务器（名称/类型/摘要），表单添加/编辑/删除——本地命令型选 stdio（command + args + env），远程选 http/sse（url + headers），支持 `auto_start` 开关（关闭则需在终端手动 `reasonix mcp start`）；`${VAR}` 环境变量按 CLI 原生规则展开；保存后重启环境生效。
  - **更新 resonix**：从官方 npm 包（`@reasonix/cli-linux-arm64`，npmmirror 国内镜像）下载 tgz 解压更新，或从手机选择新版文件、恢复内置版本。
  - **dsh purge 清洗台**：融合上游 dsh-purge v1.1.35（DeepSeek Harness 网络安全红队评测插件，MIT）侧栏 UI 的原生面板（侧滑栏「dsh purge」→ 全屏面板）。双页签「清洗 / 演练台」+ 白/墨主题：
    - **清洗 → 补丁**：RSXM 宿主策略补丁分组（破甲 system_prompt / [permissions] 白名单 / Guardian 禁用 / YOLO 审批 / 全局指令 AGENTS.md），每项实时状态 + 应用/还原（直接改 `~/.reasonix/config.toml`，应用后需重启环境生效，与上游一致）。
    - **清洗 → 提示词**：编辑 `~/.reasonix/break-armor-prompt.md`（会话覆盖段），一键「应用为 system_prompt」重注入 `[agent]`，可恢复内置模板。
    - **清洗 → 规则集**：`~/.reasonix/rules/` 多套规则，启用即写全局指令、删除即移除（内置 rsxm-default 模板由 APK 资产提供副本）。
    - **清洗 → Skill**：全局 skill 启用状态列表（与「高级设置 → SKILL」面板互通）。
    - **演练台**：读声明 + 勾选三项授权后进入，巡检资产（/sdcard、/host-data、/host/system 挂载）、技能与运行环境（proot/chroot 模式、Alpine 版本、adb/root 桥、ds2api、磁盘），只用于有权管理的本机/离线靶标/已书面授权环境。
- **离线打包**：Alpine rootfs、proot、reasonix、xterm.js 与 DS2API 全部内置，首次启动解压后无需网络（reasonix 调用 API / DS2API 连接 DeepSeek 时才需联网）。
- **手机存储访问**：guest 内 `/sdcard` 直接映射手机共享存储；首次启动引导"所有文件访问"授权（授权后自动重启环境生效），并可读写宿主 app 私有数据（`/host-data`）与只读系统分区（`/host/system` 等）。
- **bash 兼容**：Alpine 无 bash，内置 `bash → busybox ash(sh)` 包装，reasonix 的 shell 命令可直接执行；同时关闭 reasonix 的 OS 沙箱（Android 无 bubblewrap）。
- **破甲环境（内置）**：应用启动时自动把 infinite-gen-4 v0.4.0-hardened 破甲系统提示词注入 reasonix 的 `~/.reasonix/config.toml`（`[agent] system_prompt` 键，幂等标记 `rsxm-break-armor-v0.4.0-hardened`），并同步为全局 skill `rsxm-break-armor`（`~/.reasonix/skills/`）与全局指令（`AGENTS.md` 追加说明）——任何项目/任何会话下 AI 都携带该行为框架（来源 `assets/break-armor-prompt.md`，每次启动自动刷新；**内容锚点自愈**：`escape-clause free kernel` 锚点缺失即强制重注入，防 reasonix 更新重写 config 或旧版浅内容残留导致破甲失效）。
- **破甲内核（内置插件）**：应用启动时把 `dsh-infinite-gen-4` v0.4.1（`assets/dsh-infinite-gen-4.tar`，贡献 4 个 prompt）部署到 guest 的 `~/.reasonix/plugins/dsh-infinite-gen-4`，并在 `~/.reasonix/plugin-packages.json` 登记为 `enabled` —— 等价于桌面端的「本地目录安装」（目录 + `reasonix-plugin.json` 清单 + 登记项），随 APK 升级自动覆盖刷新（幂等标记 `~/.reasonix/.rsxm-plugin-dsh-infinite-gen-4.ver` = 插件版本@APK 版本）。**chroot 模式 / rootfs 内 `.reasonix` 属主为 root 时 app 写不进去**：解压、登记、标记三步都回退到 `su`（root 不可用时，serve 诊断卡片显示失败原因）。
- **手机 Root 权限**：检测 KernelSU/Magisk，侧滑菜单可查看/测试授权状态；reasonix（AI）内直接执行 `root <命令>` 即通过 app 以 su 获取手机 root 权限（如 `root id`、`root 'pm list packages'`）。

## 架构

```
┌──────────────────────────────────────────────────────┐
│ Android App (com.rxproot.app)                        │
│   DrawerLayout（侧滑配置菜单）                        │
│   WebView ── xterm.js 终端模拟器（自适应 + 触摸滚动） │
│        │  JS <-> Java 桥（键盘/尺寸/滚轮）            │
│   proot.so（termux fork 5.1.107.89，静态 musl PIE）   │
│     -0 -r <rootfs> -b /dev -b /proc -b /sys          │
│        -b /sdcard -b /host-data -b /host/*           │
│        │  PROOT_LOADER=<nativeLibDir>/loader.so      │
│   Alpine Linux 3.20 (arm64 minirootfs)               │
│     /usr/bin/pty-bridge ── 创建 PTY                  │
│       └── entry.sh ── 自动启动 reasonix              │
└──────────────────────────────────────────────────────┘
```

### 关键组件（全部打包在 APK 内）

| 组件 | 说明 |
| --- | --- |
| `lib/arm64-v8a/proot.so` | termux 维护的 proot 5.1.107.89（zig 交叉编译，musl 静态 PIE），修复 Android app 环境 accept/accept4 被 seccomp 拦截的问题 |
| `lib/arm64-v8a/loader.so` | termux proot 配套 loader（静态，链接脚本固定 `0x2000000000`），由 `PROOT_LOADER` 指定，绕过 SELinux execve 限制 |
| `assets/rootfs.tar` | Alpine 3.20 arm64 minirootfs（gzip，首次启动用系统 toybox tar 解压） |
| `assets/ds2api/ds2api-bundle.tgz` | 内置 DS2API 网关（上游 [CJackHwang/ds2api](https://github.com/CJackHwang/ds2api) v4.6.1，AGPL-3.0：静态 arm64 二进制 + WebUI 管理台 + LICENSE/README），随环境启动自动后台运行（127.0.0.1:5001） |
| `assets/usr/bin/pty-bridge` | 自编译静态 musl PIE，guest 内创建 PTY（`posix_openpt`+`fork`） |
| `assets/usr/bin/reasonix` | 静态链接 Go 二进制（来自官方 npm 平台包 `@reasonix/cli-linux-arm64`，可在应用内一键更新） |
| `assets/dsh-infinite-gen-4.tar` | 内置 reasonix 插件「无限四代」v0.4.1（破甲内核）：随环境启动部署到 guest 的 `~/.reasonix/plugins/` 并登记 `plugin-packages.json`（`enabled`），apk 写不进时由 `su` 回退 |
| `assets/break-armor-prompt.md` | infinite-gen-4 v0.4.0-hardened 破甲系统提示词（canonical 权威源，escape-clause free kernel，entry.sh 注入 `config.toml` / 全局 skill / `AGENTS.md`） |
| `assets/web/*` | xterm.js 5.3.0 + fit addon（离线终端渲染） |

## 构建

前置：JDK 17 + Android SDK（`platforms;android-34`、`build-tools;34.0.0`）。

```bat
cd android-app
set JAVA_HOME=C:\path\to\jdk-17
set ANDROID_HOME=C:\path\to\android-sdk
call gradlew.bat assembleRelease
```

产物：`android-app/app/build/outputs/apk/release/app-release.apk`（R8 混淆 + 资源压缩，约 16MB）。

> `android-app/local.properties` 需指向本机 SDK（未入库）。重编译 proot/pty-bridge 需要 [zig](https://ziglang.org/download)（详见 `android-app/README.md`）。

## 安装与使用

```sh
adb install reasonix-proot.apk
```

1. 首次打开：自动解压 Linux 环境（约 30 秒）→ **弹出 API Key 配置对话框**，填入 DeepSeek
   API Key（platform.deepseek.com 获取）点击"保存并启动"，即可直接进入 reasonix 会话。
2. 已配置过则直接进入；随时可在 **侧滑菜单 → API Key 配置** 修改。
3. 退出 reasonix 后自动回到 Alpine shell；输入 `exit` 关闭。

### ADB 无线调试（用 guest 内 adb 调试本手机）

1. 手机：设置 → 开发者选项 → 无线调试 → 打开，记下配对码与配对/连接端口。
2. 侧滑菜单 → **ADB 无线调试** → 填入配对码/端口 → 点「配对并连接」或「自动连接」
   （由应用直接驱动容器内 adb 执行并回显结果，**不依赖 reasonix 会话**）。
3. 连接成功（状态变「已连接」）后，在 reasonix 终端里即可 `adb shell` / `adb install`。

### 更新 resonix

侧滑菜单 → **更新 resonix** → 网络更新（默认官方 npm 源，可改 URL 指定版本）或从手机选择新版文件。

## 已知限制

- 仅 arm64 设备；x86 模拟器无法运行。
- DNS 使用国内公共 DNS（223.5.5.5 / 119.29.29.29）。
- 应用私有目录（`files/rootfs`）在卸载时清除。
- TUI 内滑动滚动依赖 reasonix 的 SGR 鼠标追踪（已启用）。
- Android 11+ 无法访问其他应用的 `Android/data` 目录（系统硬限制）。

## 许可证

MIT。第三方组件版权归其各自作者所有：
[Reasonix](https://github.com/esengine/DeepSeek-Reasonix)（MIT）、[proot](https://github.com/termux/proot)（GPL-2.0）、Alpine Linux（GPL）、[xterm.js](https://github.com/xtermjs/xterm.js)（MIT）。

# Reverse Flow Skill — 能力清单

> 基于 `reverse_flow_skill` v1.0.0 完整审计，涵盖 15 个脚本、6 个公共模块、18 篇参考文档、46 项 smoketest。

---

## 一、自动化分析（开箱即用）

### 1. 一键全自动分析 (`auto_analyze.py`)

投入任意二进制文件，一次性输出完整分析报告：

| 分析项 | 内容 |
|--------|------|
| 文件识别 | PE / ELF / Mach-O / ZIP / PDF / 图片，含架构 (x86/x64/ARM) |
| 哈希 | MD5 + SHA-1 + SHA-256 |
| 熵值 | 全局 + 8 段分段熵值 |
| 字符串 | ASCII 字符串 Top-20 提取 |
| 密码常量 | AES S-box、MD5/SHA-1/SHA-256 IV、TEA delta、Base64、CRC-32 共 20 种扫描 |
| 壳检测 | UPX/ASPack/VMProtect/Themida 等 10+ 种壳特征 |
| 反分析检测 | 40+ 调试器/虚拟机/沙箱指示字符串 |
| 可疑 API | 注入/持久化/执行/网络/凭据/规避/加密 7 大类 API 分类 |
| 深度结构解析 | PE/ELF/Mach-O/DEX/.NET/APK 完整结构解析（复用各专项解析器：节区/段/装载命令/DEX 结构/CLR 元数据） |
| 离线分诊富集 | URL/IP/邮箱/注册表/路径/可疑关键词提取 + 推荐工具链与下一步（triage_artifact） |
| 风险评级 | report_from_triage.risk_hint 适配器复用，归一化为 unknown/low → high 评级 + 理由 |
| 深度密码扫描 | find_crypto 完整常量库 + 加密 API 导入 + 高熵区滑动窗口检测 |
| Ghidra 可用性 | detect_ghidra 探测本地 Ghidra/Java 是否就绪（仅探测，不调用子进程） |
| ROP gadgets | 仅可执行文件(PE/ELF/Mach-O)，枚举可执行段 gadget 并按类别计数（capstone 可选，输入限 50MB） |
| 混淆检测 | shellcode_tools.find_obfuscated_strings 检测栈字符串/XOR 解码循环/加密数据表 |
| 调试器脚本 | 仅 PE 且存在加壳/反调试信号时离线预生成 x64dbg 脚本（unpack_esp/memory_dump/anti_debug_bypass/breakpoint_trace），不执行 |
| YARA 规则 | yara_gen 富生成器（启发式选串 + 字节模式），失败时回退内联生成器 |
| 风险摘要 | 严重性分级汇总 |

### 2. 安全离线分诊 (`triage_artifact.py`)

不执行样本，只读分析，适合不可信文件的第一手检查：

| 分析项 | 内容 |
|--------|------|
| 哈希 | MD5/SHA-1/SHA-256（流式计算） |
| 熵值 | 前缀熵值 |
| 字符串 | 最多 500 条 ASCII + UTF-16LE |
| Magic 检测 | 13 种文件头签名 |
| 指标提取 | URL、IPv4、Windows/Unix 路径、注册表、邮箱、可疑关键词 7 类 |
| 工具推荐 | 基于文件类型自动推荐分析工具链（android/native/firmware/network/malware） |
| 下一步建议 | 按文件类型生成后续分析建议 |

---

## 二、格式专项深度分析

### 3. PE 深度扫描 (`pe_deep_scan.py`)

| 能力 | 详情 |
|------|------|
| 头部解析 | DOS/PE header → machine、时间戳、子系统、入口点、32/64 位 |
| 节区枚举 | 每节熵值、权限 (R/W/X)、特征 |
| 导入表 | IAT/ILT thunk 解析，PE32/PE32+ 双支持 |
| 可疑导入 | 进程注入/操作/提权/凭据/反调试 6 类 |
| TLS 回调 | 提取 TLS callback 地址 |
| Rich Header | XOR 解密 → 编译器工具链元数据还原 |
| 安全缓解 | ASLR / HE ASLR / DEP/NX / SafeSEH / CFG / GS Cookie / RWX / AppContainer |
| 壳检测 | 节名 + 高熵 + 少节区 三重判定 |

### 4. ELF 深度扫描 (`elf_deep_scan.py`)

| 能力 | 详情 |
|------|------|
| ELF Header | class (32/64)、端序、OS ABI、类型、机器码、入口点 |
| Program Header | LOAD/DYNAMIC/INTERP/NOTE/TLS/GNU_STACK/GNU_RELRO/GNU_PROPERTY，含 R/W/X 标记 |
| Section Header | 名/类型/地址/大小（从节名字符串表解析） |
| Dynamic 段 | NEEDED/SONAME/RPATH/RUNPATH/FLAGS/INIT_ARRAY/FINI_ARRAY/BIND_NOW |
| PT_NOTE | GNU build ID 提取 |
| Init/Fini Array | 初始化/终止函数数组 |
| 安全缓解 | PIE / NX / RELRO (None/Partial/Full) / Stack Canary / FORTIFY / RUNPATH / RWX |
| 库识别 | 所有 .so 引用提取 |

### 5. Mach-O 深度扫描 (`macho_scan.py`)

| 能力 | 详情 |
|------|------|
| Fat Binary | Universal binary 检测 + slice 枚举（最多 16 个架构） |
| Thin Header | CPU 类型、文件类型 (EXECUTE/DYLIB/BUNDLE/OBJECT)、flag 解码 |
| Load Commands | 最多 128 条：SEGMENT/SEGMENT_64（含 section 详情）、DYLIB 系列（含版本）、UUID、MAIN、CODE_SIGNATURE、ENCRYPTION_INFO（FairPlay）、VERSION_MIN、BUILD_VERSION、RPATH、DYLD_INFO |
| Section 属性 | CSTRING_LITERALS / SYMBOL_STUBS / LAZY_POINTERS / MOD_INIT_FUNC_POINTERS 等 |
| 安全分析 | PIE / NX / ARC (__RESTRICT/__restrict 反调试) / 代码签名 / FairPlay 加密 / RPATH |

### 6. APK 深度扫描 (`apk_deep_scan.py`)

| 能力 | 详情 |
|------|------|
| Manifest 解析 | 二进制 XML → 权限/组件提取 |
| 权限风险 | 19 高危 + 13 中危 + 4 低危权限分级 |
| 导出组件 | Activity/Service/Receiver/Provider 全枚举 |
| Native 库 | .so 架构识别 (x86/ARM/AArch64/x86_64) |
| DEX 列表 | classes.dex + multi-dex 定位 |
| 签名信息 | META-INF/.RSA/.DSA/.EC 证书 |
| 壳检测 | 18 种加固方案（360/腾讯乐固/梆梆/爱加密/APKProtect 等） |
| 可疑文件 | 嵌入 APK/JAR、res/raw 中的 .exe/.dll/elf、shell 脚本 |
| 信息提取 | URL/IP/邮箱（从可读资源文件） |
| 风险评分 | 权限 + 组件 + 加固 + 原生代码 + 可疑文件 → 0-50+ 量化评分 |

### 7. DEX 结构分析 (`dex_analyze.py`)

| 能力 | 详情 |
|------|------|
| 版本识别 | Android 2.3 到 Android 14+ 全版本 magic |
| ID 表 | string_ids / type_ids / proto_ids / field_ids / method_ids / class_defs |
| 字符串表 | 全量提取（最多 65,536 条），ULEB128 解码 |
| 类型名 | type_id → 字符串解析 |
| 类定义 | 访问标志/父类/接口/注解/源文件 |
| 成员计数 | class_data_item ULEB128 → fields + methods |
| 完整性 | adler32 校验和 + SHA-1 签名验证 |
| 安全指标 | debuggable / native (System.loadLibrary) / 反射 / 动态加载 (DexClassLoader) / 加密字符串 |
| 壳检测 | 16 种加固/混淆器（DexGuard/Bangcle/360/腾讯乐固/爱加密/LIAPP/网易易盾/VMPsoft 等） |

### 8. .NET 程序集分析 (`dotnet_analyze.py`)

| 能力 | 详情 |
|------|------|
| PE→CLR | IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR → CLR Header |
| CLR Header | 运行时版本/Flags (ILONLY/32BITREQUIRED/STRONGNAMESIGNED/NATIVE_ENTRYPOINT)/入口 Token/强名称/资源/VTableFixups |
| 元数据 | BSJB magic → 版本字符串 → Stream Headers |
| #~ 流 | 64-bit valid/sorted 掩码 → 表行计数 (TypeDef/MethodDef/Field/AssemblyRef/Module 等) |
| 堆枚举 | #Strings / #US (UTF-16LE + 7-bit 压缩长度) / #GUID / #Blob |
| 混淆器检测 | 28 种检测 (ConfuserEx/Obfuscar/SmartAssembly/.NET Reactor/Babel/Eazfuscator/KoiVM/VMProtect/Themida/Dotfuscator 等) + de4dot 可清理性 |
| 节名指纹 | .agile/.phx/.neolit/.mpress 等 section 级特征 |
| 安全 | Strong Name / ILOnly / SuppressIldasm / Authenticode |

---

## 三、逆向工程专项工具

### 9. Shellcode 工具箱 (`shellcode_tools.py`)

| 子命令 | 功能 |
|--------|------|
| `extract` | 从二进制指定偏移提取 shellcode → capstone 反汇编 |
| `disasm` | 多架构反汇编 (x86/x64/ARM/ARM64/Thumb)，支持 hex 输入和文件输入，无 capstone 时自动退化为 hex dump |
| `generate` | 模板生成 shellcode：Linux x86/x64 (exec_sh/bind_shell/reverse_shell/read_file)、Windows x86/x64 (calc/reverse_shell)，输出格式 Python bytes / C 数组 / hex / 反汇编 |
| `strings` | 检测混淆字符串模式（栈字符串 0xC6 模式、XOR 解码循环、加密数据表） |
| `emulate` | Unicorn CPU 仿真：x86/x64/ARM，设置内存映射、追踪前 20 条指令、统计总执行指令数 |

### 10. ROP 利用链构建 (`rop_finder.py`)

| 功能 | 详情 |
|------|------|
| 架构检测 | PE/ELF header → x86/x64 自动识别 |
| 可执行段提取 | PE IMAGE_SCN_MEM_EXECUTE / ELF PF_X |
| Gadget 搜索 | 从 ret 操作码回溯，深度可配 (--depth)，capstone 反汇编 |
| Gadget 分类 | pop/load_arg/mov_store/mov_load/xchg/stack_pivot/zero_reg/inc_dec/add_sub/syscall |
| 链构建 | execve('/bin/sh')、calc/WinExec、mprotect 三种预设模板 |
| Bad Char 过滤 | 过滤 null/换行等指定坏字符 |
| 输出 | stdout + JSON 导出 + 文件保存 |

---

## 四、辅助工具链

### 11. 加密常量扫描 (`find_crypto.py`)

- **20 种常量扫描**：AES 正/逆 S-box、Rcon、DES IP/PC1、MD5/SHA-1/SHA-256/SHA-512 IV、ChaCha20/Salsa20 expand 常量、TEA/XTEA delta、CRC-32 多项式（需 3+ 次出现）、Base64 标准/URL-safe 字母表、SM4 S-box
- **高熵区检测**：256 字节滑动窗口 + 50% 重叠 + 合并相邻
- **导入表扫描**：加密 API 名称匹配
- **目标扫描**：--target 指定算法单独扫描

### 12. YARA 规则自动生成 (`yara_gen.py`)

- **字符串提取**：ASCII + UTF-16LE
- **启发式评分**：长度/大小写混合/特殊字符/标识符/关键词 加权
- **去重**：Jaccard 相似度 > 0.8 过滤
- **字节模式**：可疑 API 附近 + 函数序言 (x86 0x558BEC / x64 0x48895C24)
- **条件逻辑**：Magic at 0 + (N of $s* OR 1 of $hex*)

### 13. 分诊报告生成 (`report_from_triage.py`)

- 输入 triage JSON → Markdown 初步报告
- 支持多文件聚合
- 自动生成：工件清单 / 已验证事实 / 指标摘要 / 工具推荐 / 下一步

### 14. 本地工具审计 (`tool_audit.py`)

- **23 种工具检测**：Ghidra/jadx/x64dbg/Apktool/radare2/Frida/Binwalk/DIE/pwndbg/YARA/Unicorn/angr/Capstone/RetDec/GEF/AFL++/syzkaller/capa/Qiling/LIEF/Volatility3/FLOSS/Rizin
- **检测方式**：shutil.which() + Python import
- **输出**：已安装/未安装 + 星数排名 + Top-10 推荐安装

### 15. 案例工作区创建 (`create_case.py`)

一键创建结构化案例目录：

```
case/
├── artifacts/          # 原始样本（只读）
├── triage/             # 分诊结果
├── reports/            # 分析报告
├── logs/               # 运行日志
├── notes/              # 假设 + 决策记录
├── exports/            # 导出数据
├── tools/              # 工具审计
└── prompts/            # Agent prompt 模板
```

---

## 五、公共函数库 (`common/`)

| 模块 | 导出 | 功能 |
|------|------|------|
| `entropy.py` | `shannon_entropy(data) -> float` | 香农熵计算，被 5+ 脚本复用 |
| `filetype.py` | `identify_file_type(data, path) -> dict` | 11 种文件格式识别（PE/.NET/ELF 32+64 LE+BE/Mach-O thin+fat/ZIP+存档/PNG+JPEG+RIFF/PDF/APK/Java class） |
| `hashes.py` | `compute_hashes(data) -> dict` | MD5 + SHA-1 + SHA-256 一次性计算 |
| `io_utils.py` | `read_file(path, max_size) -> bytes` | 安全读文件（默认 100MB，硬上限 500MB） |
| `strings.py` | `extract_ascii_strings` / `extract_unicode_strings` / `extract_strings` | 字符串提取（去重排序） |
| `crypto_constants.py` | `CONSTANTS` dict | 集中式密码常量库（AES/DES/MD5/SHA-1/SHA-256/SHA-512/ChaCha20/TEA/CRC-32/Base64/SM4） |

---

## 六、知识参考体系（18 篇）

### 工作流与方法论
| 文档 | 核心内容 |
|------|----------|
| `workflow.md` | 7 阶段门控、交付物标准、下一步菜单模板、升级标准 |
| `capabilities.md` | 全技能检查表（通用/原生/托管/固件/移动/文档/漏洞） |
| `prompting.md` | 双语 Agent prompt 模板、模糊意图恢复、CTF 措辞规范化 |
| `evidence-reporting.md` | 初报/深逆/漏洞公告三级报告模板、置信度词汇、证据表规范 |
| `tooling-matrix.md` | 按平台/文件类型的工具选择矩阵 + 证据收集指引 |
| `tool-catalog.md` | 39 个高星工具（含仓库地址和星数） |

### 逆向技术
| 文档 | 核心内容 |
|------|----------|
| `reverse-techniques.md` | 静态优先/反编译/动态/加壳处理/补丁对比/协议恢复 |
| `go-reverse.md` | pclntab 魔法数、运行时函数定位、类型恢复、Go 专用工具 |
| `rust-reverse.md` | 调用约定、符号反混淆、异步状态机、智能指针识别 |
| `wasm-analysis.md` | 12 段模块结构、WAT 指令集速查、语言→Wasm 特征识别 |
| `symbolic-execution.md` | angr/Triton/Manticore 用法、符号化策略、路径爆炸控制、SMT 约束模式 |
| `anti-reverse.md` | 17 种反调试 + 12 种反虚拟机 + 11 种反反汇编 + 壳识别 + 绕过方法 |
| `linux-anti-debug.md` | ptrace 自跟踪、/proc 检查、RDTSC 计时、信号处理、硬件断点检测、父子进程联动 |

### 专项分析
| 文档 | 核心内容 |
|------|----------|
| `crypto-analysis.md` | 完整常量数据库、算法恢复方法、密钥提取模式 |
| `network-re.md` | PCAP 入口、二进制协议分析（消息边界/字段/状态机/编码识别）、TLS/JA3 |
| `malware-analysis.md` | 隔离需求、静态分诊流程、持久化 9 法、进程注入 7 法、C2 模式 |
| `vulnerability-review.md` | 10 类漏洞、根因分析流程、CVSS 式严重性、修复模式 |
| `exploit-development.md` | 缓解审计清单、利用原语层级、栈溢出标准流程、ROP/shellcode 章节 |

---

## 七、测试体系

### Smoketest (`tests/test_smoke.py`) — 46 项全部通过

| # | 测试 | 覆盖范围 |
|---|------|----------|
| 1 | `test_all_scripts_compile` | 所有 15 个脚本通过 `py_compile` |
| 2 | `test_all_scripts_help` | 所有脚本 `--help` 输出含 'usage:' |
| 3 | `test_all_scripts_bad_input` | 所有脚本对无效输入不崩溃 |
| 4 | `test_common_imports` | 6 个 common 模块全部可导入 |
| 5 | `test_entropy` | 边界值（全同/空/全范围） |
| 6 | `test_hashes` | MD5 / SHA-256 长度验证 |
| 7 | `test_filetype` | PE / ELF / 未知类型识别 |
| 8 | `test_io_utils` | 文件读取大小限制 |
| 9 | `test_strings` | UTF-8 字符串提取 |
| 10 | `test_crypto_constants` | 所有常量字节长度验证 |
| 11+ | 集成/隔离测试 | 各专项解析器(PE/ELF/Mach-O/DEX/.NET/APK)与 auto_analyze 流水线阶段(risk_hint、YARA、Ghidra、ROP、混淆检测、调试器脚本)的集成冒烟、纯文本不崩溃、以及 `import` 失败隔离验证 |

> 测试随每个集成模块同步扩充，从初始 10 项增长到 46 项，覆盖编译、帮助、坏输入、公共库、各模块集成与异常隔离。

---

## 八、端到端工作流

一个完整的逆向工程任务流：

```
用户提供样本
    │
    ▼
create_case.py          → 创建案例工作区
    │
    ▼
tool_audit.py           → 确认本地工具可用性
    │
    ▼
triage_artifact.py      → 安全离线分诊（哈希/熵/字符串/指标）
    │
    ▼
report_from_triage.py   → 生成初步报告
    │
    ▼
auto_analyze.py         → 一键全自动深度分析
    或
格式专项脚本             → PE / ELF / Mach-O / APK / DEX / .NET 专项
    │
    ▼
find_crypto.py          → 加密常量深度扫描
    │
    ▼
rop_finder.py           → ROP gadget 搜索 + 利用链构建
shellcode_tools.py      → Shellcode 提取/生成/仿真
yara_gen.py             → YARA 规则生成
    │
    ▼
最终报告 + 利用验证（CTF/实验室环境）
```

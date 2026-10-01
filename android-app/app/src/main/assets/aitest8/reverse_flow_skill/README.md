# Reverse Flow — 全谱系逆向工程技能

`reverse-flow` 是为 Claude Code / Codex CLI 设计的全谱系逆向工程技能包。

## 启动

启动词：**真心为你**

支持能力：
```
静态/动态分析 | 脱壳解混淆 | 密码学识别 | 反逆向对抗 | 协议逆向
漏洞挖掘 | 利用开发(CTF) | 恶意代码分析 | 一键自动分析流水线
```

工作流：
```
分析 → 报告 → 逆向 → 深度逆向 → 漏洞研判 → 利用开发 → 用户选择下一步
```

## 技能结构

### 核心
- `SKILL.md` — 技能主提示词（含全流程指令、工具命令模板、自动化流水线）
- `agents/openai.yaml` — Codex CLI 接口配置

### 参考知识库（18 篇）

| 文件 | 内容 |
|---|---|
| `references/workflow.md` | 阶段门控、交付物标准、下一步菜单 |
| `references/capabilities.md` | 逆向能力检查清单 |
| `references/tooling-matrix.md` | 工具矩阵（按平台/文件类型） |
| `references/tool-catalog.md` | 精选工具目录（含 GitHub star 数） |
| `references/prompting.md` | 提示词模板（英文内核+中文交互） |
| `references/reverse-techniques.md` | 静态/动态/反编译/移动端/固件技术 |
| `references/evidence-reporting.md` | 报告模板、证据表、置信度规范 |
| `references/vulnerability-review.md` | 漏洞分类、根因分析、严重性评估 |
| `references/exploit-development.md` | ROP/JOP、Shellcode、堆利用、格式化字符串、pwntools 速查 |
| `references/crypto-analysis.md` | 密码常量库(AES/DES/SHA/SM4)、算法识别、密钥检测、弱密码模式 |
| `references/anti-reverse.md` | 反调试/反VM/加壳/混淆检测与绕过、ScyllaHide/Frida 脚本 |
| `references/network-re.md` | 协议逆向、PCAP 分析、状态机推断、C2 信标检测、TLS 指纹 |
| `references/malware-analysis.md` | 恶意代码分析、持久化机制、进程注入、勒索软件、YARA 规则 |
| `references/go-reverse.md` | Go 二进制逆向：pclntab、运行时函数定位、类型恢复 |
| `references/rust-reverse.md` | Rust 逆向：调用约定、符号反混淆、异步状态机、智能指针 |
| `references/wasm-analysis.md` | Wasm 模块结构、WAT 指令集、语言→Wasm 特征识别 |
| `references/symbolic-execution.md` | angr/Triton/Manticore 符号执行、路径爆炸控制、SMT 约束 |
| `references/linux-anti-debug.md` | ptrace 自跟踪、/proc 检查、RDTSC 计时、硬件断点检测 |

### 实战脚本（11 个）

#### 分析脚本
| 脚本 | 功能 |
|---|---|
| `scripts/auto_analyze.py` | 一键自动化分析流水线：文件识别→哈希→熵→字符串→密码常量→加壳/反分析/可疑API→深度结构解析(PE/ELF/Mach-O/DEX/.NET/APK)→分诊富集→风险评级→深度密码扫描→Ghidra探测→ROP gadgets→混淆检测→x64dbg脚本预生成→YARA生成 |
| `scripts/triage_artifact.py` | 离线分诊：哈希、大小、熵、magic bytes、字符串、工具推荐 |
| `scripts/find_crypto.py` | **NEW** 密码常量扫描(AES/DES/MD5/SHA1/SHA256/SHA512/ChaCha20/SM4)、高熵区域检测、API导入检测 |
| `scripts/pe_deep_scan.py` | **NEW** 深度PE分析：DOS/PE/Rich头、区段、导入/导出、TLS回调、调试信息、证书、缓解措施、加壳检测 |
| `scripts/apk_deep_scan.py` | **NEW** 深度APK分析：权限审计、导出组件、native库检测、签名、加壳检测、风险评估 |

#### 逆向工具
| 脚本 | 功能 |
|---|---|
| `scripts/shellcode_tools.py` | **NEW** Shellcode提取/反汇编(x86/x64/ARM)/模拟执行(Unicorn)/生成(linux/windows模板) |
| `scripts/rop_finder.py` | **NEW** ROP gadget搜索(正则过滤)、分类(pop/syscall/stack pivot/zero reg)、ROP链自动构建 |

#### 工具脚本
| 脚本 | 功能 |
|---|---|
| `scripts/report_from_triage.py` | 从分诊JSON生成初始报告 |
| `scripts/tool_audit.py` | 检测本地逆向工具链，推荐缺失工具 |
| `scripts/create_case.py` | 创建结构化case工作区 |
| `scripts/yara_gen.py` | **NEW** YARA规则自动生成：独特字符串+十六进制模式+结构特征 |

## 快速使用

```bash
# 一键分析任意文件
python scripts/auto_analyze.py target.exe --out ./analysis/

# 扫描密码学常量
python scripts/find_crypto.py target.bin --out ./crypto/

# PE深度分析
python scripts/pe_deep_scan.py target.dll --out ./pe_scan/

# APK权限审计
python scripts/apk_deep_scan.py target.apk --out ./apk_scan/

# YARA规则生成
python scripts/yara_gen.py sample.bin --out ./yara_rules/

# ROP gadget搜索
python scripts/rop_finder.py target.exe --depth 5 --bad-chars "00|0a"

# Shellcode生成/反汇编
python scripts/shellcode_tools.py generate --arch x64 --os linux --type exec_sh
python scripts/shellcode_tools.py disasm --arch x64 --hex "4831d25248b82f62696e2f2f7368504889e7..."

# 创建case工作区
python scripts/create_case.py --case-name sample_analysis --goal "reverse engineering" --out ./cases/
```

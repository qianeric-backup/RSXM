# Go 逆向工程参考

## Go 二进制特征

### 编译属性
- **静态链接**（默认）：无外部动态库依赖，二进制体积大（通常 10-50 MB+）
- **自举运行时**：包含完整的goroutine调度器、GC、内存分配器
- **去符号**：Go 1.17+ 默认剥离DWARF调试信息，但保留符号表
- **无传统PLT/GOT**：静态链接没有延迟绑定

### 可执行段特征
| 段名 | 用途 | 典型大小 |
|------|------|----------|
| `.text` | 代码 + 运行时 | 最大段，含数千个运行时函数 |
| `.rodata` | 只读数据、字符串表、类型元数据 | 通常 1-10 MB |
| `.data` / `.noptrdata` | 已初始化的全局变量 | 较小 |
| `.bss` / `.noptrbss` | 未初始化数据 | 较小 |
| `.itablink` | interface 方法表元数据 | Go 独有 |
| `.gopclntab` | PC-line table（栈回溯信息） | Go 独有，关键定位点 |

## 关键定位点

### pclntab（Program Counter Line Table）
在二进制中搜索 magic number：
- Go 1.1 及之前：`0xFFFFFFFB`
- Go 1.2-1.17：`0xFFFFFFFA`
- Go 1.18+：`0xFFFFFFF0`（新格式）

`pclntab` 包含所有函数的 PC 范围、名称偏移和文件位置，是恢复函数名的最重要结构。

### 函数符号查找
```
# 提取 Go 符号
go tool nm <binary>           # 列出所有符号
readelf -Ws <binary> | grep -E 'FUNC|OBJECT'  # ELF格式
objdump -T <binary>           # 动态符号（极少）

# 恢复去符号二进制函数名
python3 GoReSym/GoReSym.py <binary>   # 开源工具，利用 pclntab 结构
```

### 关键运行时函数
| 函数名模式 | 用途 | 逆向关注点 |
|-----------|------|-----------|
| `runtime.main` | 程序入口点 | 用户代码从这里被调用 |
| `runtime.newproc` | goroutine 创建 | 并发流跟踪 |
| `runtime.morestack` | 栈扩容 | 函数序言常见调用 |
| `runtime.gcWriteBarrier` | GC 写入屏障 | 指针写入拦截 |
| `runtime.panicIndex` | 切片的 OOB | 崩溃点定位 |
| `runtime.memequal` | 内存比较 | 字符串/字节比较 |
| `runtime.memmove` | 内存拷贝 | 数据流跟踪 |
| `syscall.Syscall6` | 系统调用 | 底层操作跟踪 |
| `crypto/aes.NewCipher` | 加密初始化 | 加密算法定位 |
| `net.Dial` | 网络连接 | 网络行为跟踪 |

## 字符串定位

Go 二进制中的字符串:
- 存储在 `.rodata` 段，以大块连续形式出现
- 运行时不使用 C 风格的空终止符，而是用 `(ptr, len)` 结构体传递
- 可以使用 `strings` 命令提取，但大量运行时字符串会造成噪音

```bash
# 提取有意义的字符串
strings -n 6 <binary> | grep -v -E '^(runtime\.|sync\.|internal/|unicode/|math/|vendor/)' | sort -u > user_strings.txt
```

## 类型恢复

### interface 与具体类型
Go 的 interface 值由两个指针组成（`itab` + `data`），`itab` 指向类型元数据：
```python
# 通过 itab 偏移定位类型信息
# itab 条目: [*interfacetype] [*type]
# type 结构: [size] [ptrdata] [hash] [tflag] [align] [fieldalign] [kind] ...
```

### 常用类型 kind 值
| kind | 含义 | 说明 |
|------|------|------|
| 2 | `Int` | |
| 3 | `Int8` | |
| 5 | `Int32` | |
| 6 | `Int64` | |
| 11 | `Slice` | `[]T` |
| 14 | `Map` | `map[K]V` |
| 17 | `String` | |
| 20 | `Func` | |
| 24 | `Struct` | |
| 25 | `Pointer` | |
| 26 | `Interface` | `interface{}` / `any` |
| 54 | `Chan` | `chan T` |

## 反编译器与工具

### 专用 Go 工具
| 工具 | 用途 | 备注 |
|------|------|------|
| [GoReSym](https://github.com/mandiant/GoReSym) | 符号恢复 | 解析 pclntab，恢复函数名和类型名 |
| [go_parser](https://github.com/0xjiayu/go_parser) | IDA 插件 | IDA 内解析 Go 二进制结构 |
| [golang_loader_assist](https://github.com/strazzere/golang_loader_assist) | Ghidra 脚本 | Ghidra 内分析和恢复 Go 结构 |
| [alphaGolang](https://github.com/SentineLabs/AlphaGolang) | IDA Python | Go 二进制逆向脚本集合 |
| [GoFunctionRecovery](https://github.com/FlyObf/GoFunctionRecovery) | Ghidra 脚本 | 函数边界检测和命名 |

### 通用工具适配
- **IDA Pro**: 需要 Go 专用插件正确解析函数边界（Go 函数序言和 x86 标准不同）
- **Ghidra**: 使用 `golang_loader_assist` 扩展或手动配置
- **Binary Ninja**: 内置部分 Go 支持（v3.0+）
- **angr**: 需要 CFG 准确——先用 GoReSym 补充符号信息

## 常见逆向场景

### 1. 提取嵌入配置/密钥
Go 程序的配置通常编译进 `.rodata`，搜索模式：
```bash
strings <binary> | grep -iE '(api.?key|secret|token|password|endpoint|bucket)'
```

### 2. 网络通信还原
关注 `net.Dial`, `crypto/tls.Dial`, `net/http.(*Client).Do`
gRPC 程序需定位 `google.golang.org/grpc` 包中的 proto 消息类型

### 3. 去混淆/去虚拟化
Go 程序通常不需要外部加壳（已静态编译），常见保护手段：
- 字符串加密（运行时用 XOR / AES 解密）
- 控制流平坦化（少见，Go 编译器本身不易被插桩）
- 自定义 loader 动态解密代码（shellcode 注入）

### 4. 并发流跟踪
Go 的 goroutine 使控制流非线性，重点关注：
- channel 操作（`runtime.chansend`, `runtime.chanrecv`）
- `sync.Mutex.Lock/Unlock` 的调用
- goroutine 创建点（`go func()` → `runtime.newproc`）

## 实用技巧

### 快速寻找 main 函数
1. 搜索字符串 `"panic: "` 附近的引用
2. `main.main` 符号 → 用户主函数，通常在 `runtime.main` 中被调用
3. Go 1.18+：`pclntab` magic `0xFFFFFFF0` → 用 GoReSym 一键恢复所有符号

### 辅助分析命令
```bash
file <binary>                    # 确认静态链接
ldd <binary>                     # 验证静态（应输出 "not a dynamic executable"）
readelf -S <binary> | grep .gopclntab   # 确认 Go 二进制
rabin2 -S <binary> | head -20   # 快速查看段结构 (radare2)
```

### Ghidra 设置建议
- 禁用 "Assume all functions return"（Go 使用 `runtime.throw` / panic）
- 增加函数大小阈值（Go 运行时函数体可能很大）
- 设置调用约定为 `__golang`（如果有插件支持）
- 堆栈深度分析至少设为 64（Go goroutine 初始栈为 2KB-8KB）

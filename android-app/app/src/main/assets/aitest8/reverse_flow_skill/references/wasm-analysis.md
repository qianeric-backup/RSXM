# WebAssembly (Wasm) 逆向分析参考

## 概述

WebAssembly 是一种栈式虚拟机的二进制指令格式，用于浏览器和独立运行时（WASI, WasmEdge, Wasmtime 等）。

### 文件格式
- **`.wasm`** — 二进制格式（Binary Format）
- **`.wat`** — 文本格式（S-expression 可读表示）

两者通过 `wasm2wat` / `wat2wasm` 互相转换。

## 模块结构

### 顶层段 (Sections)
| ID | 名称 | 内容 | 分析价值 |
|----|------|------|----------|
| 0 | Custom | 自定义元数据（name section） | name section 含函数名、局部变量名 |
| 1 | Type | 函数签名（参数+返回值类型） | 恢复函数接口 |
| 2 | Import | 导入（host 函数、全局变量、内存、表） | 外部依赖、host API 调用 |
| 3 | Function | 内部函数引用的 type 索引 | 函数列表 |
| 4 | Table | 间接调用表 | vtable 分析 |
| 5 | Memory | 线性内存定义 | 内存大小、页数 |
| 6 | Global | 全局变量（可变/不可变、初始值） | 全局状态 |
| 7 | Export | 导出（函数、内存、表、全局变量） | 公开 API 边界 |
| 8 | Start | 启动函数 | 入口点 |
| 9 | Element | 表元素初始化 | 函数指针初始化 |
| 10 | Code | 函数体（局部变量 + 字节码指令） | 核心分析对象 |
| 11 | Data | 数据段（内存初始化） | 常量、字符串、嵌入数据 |

## 快速分诊

### 文件检测
```bash
file module.wasm          # WebAssembly (wasm) binary module
xxd module.wasm | head    # magic: 00 61 73 6D (asm\0)
```

### 模块信息提取
```bash
wasm-objdump -h module.wasm       # 段概览
wasm-objdump -x module.wasm       # 详细转储（类型、导入/导出、函数签名）
wasm-objdump -d module.wasm       # 反汇编（完整字节码）
wasm2wat module.wasm -o module.wat # 转换为可读 WAT
```

### 字符串提取
```bash
strings module.wasm        # 基本字符串
wasm-objdump -x module.wasm | grep -E '(export|import)' | cut -d'"' -f2
```

## WAT 指令集速览

### 栈操作
| WAT | 说明 | x86 类比 |
|-----|------|---------|
| `i32.const 42` | 将 42 推入栈 | `mov eax, 42` |
| `local.get 0` | 获取局部变量 0 | `mov eax, [ebp-4]` |
| `local.set 0` | 设置局部变量 0 | `mov [ebp-4], eax` |
| `global.get 0` | 获取全局变量 0 | `mov eax, [global_ptr+0]` |
| `drop` | 丢弃栈顶 | `add esp, 4` |

### 算术运算
| WAT | 说明 |
|-----|------|
| `i32.add` | 弹出 b, a; 推入 a + b |
| `i32.sub` | 弹出 b, a; 推入 a - b |
| `i32.mul` | 弹出 b, a; 推入 a * b |
| `i32.div_s` / `i32.div_u` | 有符号/无符号除法 |
| `i32.rem_s` / `i32.rem_u` | 有符号/无符号取余 |
| `i32.and` / `i32.or` / `i32.xor` | 位运算 |
| `i32.shl` / `i32.shr_s` / `i32.shr_u` | 移位 |

### 控制流
| WAT | 说明 |
|-----|------|
| `block` ... `end` | 基本块（可带标签） |
| `loop` ... `end` | 循环块 |
| `if` ... `else` ... `end` | 条件分支 |
| `br 0` | 跳转到标签 0（break） |
| `br_if 0` | 条件跳转（if top-of-stack ≠ 0 → jump） |
| `br_table [0,1,2] 3` | 间接跳转表（switch-case） |
| `call 0` | 调用函数索引 0 |
| `call_indirect (type 0)` | 间接调用（函数指针） |
| `return` | 返回 |
| `unreachable` | 陷阱（类比 `ud2` / `abort()`） |

### 内存访问
| WAT | 说明 |
|-----|------|
| `i32.load offset=4 align=4` | 从（栈顶地址 + 4）加载 i32 |
| `i32.store offset=8` | 存储 i32 到（栈顶地址 + 8） |
| `i32.load8_s` / `i32.load8_u` | 有符号/无符号字节加载 |
| `memory.size` / `memory.grow` | 获取/增长内存页 |

### 类型
| 类型 | 位宽 | C 对应 | Rust 对应 |
|------|-----|-------|----------|
| `i32` | 32-bit | `int32_t` / `uint32_t` | `i32` / `u32` |
| `i64` | 64-bit | `int64_t` / `uint64_t` | `i64` / `u64` |
| `f32` | 32-bit | `float` | `f32` |
| `f64` | 64-bit | `double` | `f64` |
| `externref` / `funcref` | 引用 | 宿主对象引用 | - |

## 常见分析场景

### 1. 提取嵌入数据
`data` 段包含初始化线性内存的所有常量：
```bash
# 用 wasm-objdump 提取 data 段内容
wasm-objdump -x module.wasm | grep -A100 'Data segment'
# 搜索字符串
wasm-objdump -x module.wasm | strings
```

### 2. 识别外部 API 调用
通过导入段追踪 host 函数调用：
```bash
wasm-objdump -x module.wasm | grep '^Import'
# 输出格式: Import[0] - func[0] sig=0 <env.puts> <- import
```

导入的 host 函数在 WAT 中以 `call` 指令出现（按导入索引调用）。

### 3. 加密算法识别
在 WAT 中识别密码学模式：
- **TEA/XTEA**: 大量 `i32.add` + `i32.shl` + `i32.xor` 循环
- **AES**: `i32.shl 4` + 大量表查找（`i32.load offset=N` 常量偏移）
- **RC4**: `i32.add` + `i32.load8_u` + `i32.xor`
- **ChaCha20**: `i32.add` + `i32.xor` + `i32.shl 16` / `i32.shl 12` 常量移位模式

### 4. 控制流还原
由于 Wasm 是结构化控制流（无 `jmp`/`goto`），反编译输出比 x86 更干净：
```bash
# 使用 wasm-decompile 生成伪代码
wasm-decompile module.wasm

# 或者用 Ghidra 11+ 的 Wasm 加载器
ghidra module.wasm
```

### 5. 间接调用表还原
`call_indirect` 通过 `table` 段索引跳转，需要追踪 `element` 段初始化和 `table.set` 动态写入来还原 vtable。

## 工具矩阵

| 工具 | 命令 | 用途 |
|------|------|------|
| wabt (WebAssembly Binary Toolkit) | `wasm2wat`, `wasm-objdump`, `wasm-decompile` | 转换 + 反汇编 + 反编译 |
| Binaryen | `wasm-opt`, `wasm-dis` | 优化 + 分析 |
| wasmtime | `wasmtime run --invoke <func>` | 独立运行时执行 |
| WABT js-api | `wasm-validate` | 模块验证 |
| Ghidra 11.x | 内置 Wasm 加载器 | GUI 反编译器 |
| Twiggy | `twiggy top` | 代码大小分析 |
| wasm-pack | `wasm-pack build` | Rust → Wasm 构建工具 |
| Emscripten | `emcc` | C/C++ → Wasm 编译套件 |

## 语言→Wasm 特征

### Rust → Wasm
- 使用 `wasm-bindgen` 导入/导出通过标记函数
- `.rodata` → data 段；字符串索引模式类似原生 Rust
- `wee_alloc` / `dlmalloc` 作为自定义分配器
- 存在 `__rust_start_panic` 和 `__rust_alloc_error_handler`

### C/C++ (Emscripten)
- 大的 `env` 导入模块（`emscripten_memcpy`, `emscripten_*`, `__syscall*`）
- 存在 `main`/`_main` 导出
- 传统 libc 函数（`malloc`, `free`, `printf`）作为导入出现

### Go (TinyGo)
- `memset` / `memcpy` / `runtime.*` 导入
- 使用 goroutine 的 TCP/HTTP 在 wasm 中用不同的调度器
- GC 相关导入（`gc_*`）

### AssemblyScript
- `~lib/` 前缀的导入函数
- 使用 `__alloc` / `__retain` / `__release` 内存管理
- 自定义 `__new` / `__newArray` 构造函数

## 线性内存分析

### 内存布局（典型）
```
+0x0000:  NULL 页（访问陷阱）
+0x1000:  栈区（从高到低增长）
+...:     未使用
+...:     堆区（分配器管理，从低到高增长）
+end:     全局数据区
```

### 运行时内存 dump
```bash
# 用 wasmtime + 自定义 hook 转储线性内存
# 或在浏览器 DevTools 中: console.save(memory.buffer)
```

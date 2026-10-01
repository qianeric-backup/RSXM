# Rust 逆向工程参考

## Rust 二进制特征

### 编译属性
- **静态链接偏好**：默认静态链接 Rust 标准库，musl target 全静态
- **去符号**：release 构建默认剥离调试符号（`strip = "symbols"` in Cargo.toml）
- **LLVM 后端**：使用 LLVM 优化，代码生成模式与 C/C++/Swift 相似
- **泛型单态化**：每个具体泛型组合生成独立函数副本，导致大量重复小函数

### 可执行段特征
| 段 | 用途 | Rust 特征 |
|-----|------|-----------|
| `.text` | 代码 | 大量小函数（Drop、Clone、泛型实例化） |
| `.rodata` | 常量 + 字符串 | 内联切片（&str）、格式化字符串 |
| `.data.rel.ro` | 重定位只读 | trait 对象 vtable |
| `.got` | 全局偏移表 | 外部 crate 函数 |

## 调用约定与ABI

### 默认ABI
- **`extern "Rust"`（默认）**：未稳定化，不保证跨版本兼容；实际是 LLVM 的 fastcall
- **`extern "C"`**：标准 C ABI — FFI 边界函数（`#[no_mangle] pub extern "C" fn`）用此 ABI
- **参数传递**：x86_64 上用 RDI, RSI, RDX, RCX, R8, R9 传参，RAX 返回

### 关键调用模式
```
; 返回 Result<T, E> (tagged union)
; 成功后 RAX 指向数据，失败后 RAX 带 err 标记
; 通常通过引用参数返回——第一个参数是隐藏的 out-pointer

; &str / &[T] 以 (ptr, len) 传递
; x86_64: ptr 在 RDI, len 在 RSI

; Box<T> / Arc<T> / Rc<T> 作为单个指针传递
; (内部已分配在堆上)

; Option<&T> 以指针传递，null 代表 None
; (Rust 的 null-pointer optimization)
```

## 关键定位点

### main 函数追踪
```bash
# 根据 Rust 运行时入口追踪
# 入口: _start → __libc_start_main → lang_start → lang_start_internal → main

# 方式1：追踪到 main (通常类型签名为 fn())
# 方式2：搜索包含项目名的字符串引用
# 方式3：在二进制中搜索错误信息字符串，定位 panic handler
```

### 符号名称 (mangled)
Rust 使用 `_ZN` / `_R` 前缀的 mangled 名称：
```
# 旧式 (legacy)
_ZN7example3foo17h1a2b3c4d5e6f7g8hE
# 新式 (v0, Rust 1.53+)
_RINvCs7d9e0f1g2h3i_7example3foo
```

```bash
# 反混淆 Rust 符号
rustfilt _ZN7example3foo17hdeadbeefE
# 或使用 nm + rustfilt
nm -C <binary> | rustfilt
```

### 标准库函数识别
| 函数/模式 | 用途 | 逆向定位价值 |
|-----------|------|-----------|
| `core::panicking::panic` | panic!() 触发点 | 错误路径、assertion 位置 |
| `core::result::unwrap_failed` | unwrap() 失败 | 错误处理分支 |
| `alloc::string::String::*` | String 操作 | 字符串处理逻辑 |
| `core::str::*::from_utf8` | UTF-8 验证后的 &str | 字符串处理入口 |
| `core::slice::*::copy_from_slice` | 切片拷贝 | 缓冲区操作 |
| `<Vec<T> as Drop>::drop` | 向量释放 | 资源边界 |
| `alloc::vec::Vec::push` | 向量追加 | 数据构建逻辑 |
| `core::fmt::write` | 格式化输出 | 字符串构建 |
| `std::io::Read::read` | 字节读取 | 文件/网络 I/O |
| `std::net::TcpStream::connect` | TCP 连接 | 网络通信 |
| `tokio::` 前缀函数 | 异步运行时 | 异步网络/IO 程序 |

### 异步函数识别
Tokio / async-std 程序特征：
- 大量状态机结构体（`Suspend0`, `Suspend1`, ...）
- 函数在 poll 方法中实现（嵌套 switch/match）
- `.text` 段中有多个 `tokio::runtime` 初始化函数

```bash
# 识别 Rust 异步二进制
strings <binary> | grep -E 'tokio|async-std|smol'
nm <binary> | grep -E '_ZN.*tokio.*7runtime'
```

## 字符串定位

Rust 的 `&str` 在二进制中:
- 字面字符串放在 `.rodata`（或 `.str` 在 macOS 上）有特定格式
- 可用标准方法提取：
```bash
strings -n 6 <binary> | sort -u
# 过滤 Rust 内部符号噪音（对较大二进制有效）
strings -n 8 <binary> | grep -v -E '^(core::|std::|alloc::)'
```

## 内存布局

### 关键数据结构
```rust
// &str 切片 = (ptr: *const u8, len: usize)  → 2 × 8 bytes 在 x86_64 上
// String    = { ptr: *const u8, len: usize, cap: usize }  → 3 × 8 bytes
// Vec<T>    = { ptr: *const T, len: usize, cap: usize }  → 3 × 8 bytes
// Box<T>    = *const T  → 1 × 8 bytes
// Option<Box<T>> / Option<&T>  → 1 × 8 bytes (null-pointer optimization)
// Result<T, E>  → tagged union, 大小 = max(sizeof(T), sizeof(E)) + discriminator
```

### 智能指针识别
| 指针类型 | 结构大小 | 识别线索 |
|---------|--------|---------|
| `Box<T>` | 8 字节 (x86_64) | 单调 malloc/free 调用 |
| `Rc<T>` | 8 字节 (ptr) + 外部 refcount | strong_count/weak_count 原子操作 |
| `Arc<T>` | 8 字节 (ptr) + 外部 refcount | Rc + 线程安全原子（不同 CPU 指令） |
| `Cow<T>` | 同 String/Vec | 与借用/所有权的区分，条件拷贝 |

## 常见逆向场景

### 1. 提取嵌入串 / 密钥 / 证书
```bash
# 查找常量嵌入模式
strings <binary> | grep -E '^[A-Za-z0-9+/=]{40,}$'  # Base64 keys/certs
strings <binary> | grep -E '(key|token|password|secret|api|cert)'
# 在 IDA/Ghidra 中搜索交叉引用的 &str
```

### 2. Cargo 依赖识别
```bash
# 从符号名称中提取 crate 列表
nm <binary> | rustfilt | awk -F'::' '{print $1}' | sort -u | head -50
# 在二进制中搜索 crate 名
strings <binary> | grep -E '^[a-z][a-z0-9_]{2,20} v?\d+\.'
```

### 3. 恢复枚举 / 代数数据类型
Rust 枚举被编译为 tagged union：
```c
// enum Option<T> { None, Some(T) }
// 编译为: { tag: u8/u16, payload: T }

// enum Result<T, E> { Ok(T), Err(E) }
// 编译为: { tag: u8, payload: union { ok: T, err: E } }
```
在 Ghidra/IDA 中，tag 值的 `cmp` + `je/jne` 分支揭示枚举变体结构。

### 4. FFI 调用定位
跨语言边界函数通常有 `#[no_mangle]` 或 `extern "C"`：
```bash
# 查找 C ABI 导出
objdump -T <binary> | grep -v 'GLIBC\|ld-linux'   # Linux
nm -gU <binary>                                    # macOS
dumpbin /EXPORTS <binary>                          # Windows
```

## 工具

### Rust 专用
| 工具 | 用途 |
|------|------|
| [rustfilt](https://github.com/luser/rustfilt) | 符号反混淆 |
| [cargo-binutils](https://github.com/rust-embedded/cargo-binutils) | `nm`/`objdump`/`size` Rust 适配 |
| [Feroxbuster-Rec](https://github.com/epi052/feroxbuster) | 强制项目重构辅助 |

### Ghidra/IDA 配置
- 默认冷调用约定处理：先尝试 `__fastcall`（Linux/macOS x86_64）或 `__stdcall`（Windows）
- 自动化脚本：使用 `nm <binary> \| rustfilt` 生成符号导入脚本
- Rust 不使用 SEH/异常展开器（而是 `panic=abort` 或通过 `__rust_start_panic`）

## 注意事项

1. **溢出检查**：debug 构建有整数溢出检查（`overflow-checks = true`），release 默认关闭
2. **切片边界检查**：数组/切片索引有边界检查，OOB 调用 `core::panicking::panic_bounds_check`
3. **分配器**：默认使用 `jemalloc`（旧版）或系统 `malloc`，可自定义 `#[global_allocator]`
4. **LTO**：release 构建可能开启 linker LTO，跨 crate 内联使调用边界模糊化
5. **代码膨胀**：泛型实例化 + 内联后体积急剧增大。一个 50 行 Rust 函数可能产生 10KB+ 的汇编

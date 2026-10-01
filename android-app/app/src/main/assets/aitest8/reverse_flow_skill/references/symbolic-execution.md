# 符号执行与约束求解参考

## 概述

符号执行将程序输入替换为符号变量，沿着执行路径维护路径约束（path conditions），在分支点 fork 状态并更新约束。当到达目标点时，约束求解器（SMT solver）给出满足路径条件的输入值。

## 核心工具

### angr (Python, 首选)
```python
import angr
import claripy

# 基础设置
proj = angr.Project('./target', auto_load_libs=False)
state = proj.factory.entry_state()

# 符号化输入
sym_arg = claripy.BVS('input', 8 * 32)  # 32 字节符号输入
state.memory.store(state.regs.rsp + 8, sym_arg)

# 探索到目标地址
simgr = proj.factory.simulation_manager(state)
simgr.explore(find=0x401234, avoid=0x401300)

# 提取解
if simgr.found:
    solution = simgr.found[0].solver.eval(sym_arg, cast_to=bytes)
    print(f"Input: {solution}")
```

### Triton (C++/Python, 动态符号执行)
```python
import triton
ctx = triton.TritonContext()
ctx.setArchitecture(triton.ARCH.X86_64)
# 符号化寄存器
ctx.symbolizeRegister(ctx.registers.rax, 'input')
# 执行具体指令 → 符号表达式自动传播
```

### Manticore (Python, EVM + Native)
```python
from manticore.native import Manticore
m = Manticore('./target')
m.context['solution'] = b''
@m.hook(0x401234)
def on_target(state):
    m.context['solution'] = state.solve_one(state.cpu.RDI)
    m.terminate()
m.run()
```

## 符号化策略

### 什么应该符号化
| 数据 | 方法 | 场景 |
|------|------|------|
| 命令行参数 | 替换 `argv` 指针 | 破解 keygen |
| stdin | hook `read`/`fgets` 系统调用 | 破解密码 |
| 文件内容 | 符号化 `read` 返回值缓冲区 | 格式解析器 |
| 网络数据包 | 符号化 `recv` 缓冲区 | 协议 fuzzing |
| 环境变量 | 符号化 `getenv` 返回值 | 配置路径 bypass |
| 寄存器初始值 | `state.regs.rax = BVS(...)` | 函数级分析 |
| 内存区域 | `state.memory.store(addr, BVS(...))` | 全局变量 bypass |

### 应该保持具体的 (concrete)
- 程序计数器 (PC/RIP)
- 栈指针 (SP/RSP)
- 全局指针 (GP)
- 硬件/IO 端口地址
- 系统调用号
- 函数指针表
- 库代码（通常用 `auto_load_libs=False` + SimProcedures）

## 路径爆炸控制

### 通用策略
| 策略 | angr 实现 | 适用场景 |
|------|----------|---------|
| 深度限制 | `simgr.explore(find=..., avoid=..., step_limit=5000)` | 通用 |
| 循环边界 | `angr.options.LIMIT_STATIC_PATHS` | 有界循环 |
| Veritesting | `angr.options.VERITESTING` | 静态合并分支 |
| 路径剪枝 | `simgr.drop(stash='avoid')` | 快速丢弃 |
| 目标导向 | `simgr.explore(find=0x..., avoid=[0x..., 0x...])` | 有明确目标 |
| 自定义过滤器 | `simgr.move('active', 'avoided', lambda s: custom_check(s))` | 逻辑剪枝 |
| Lazy Solver | `claripy.LightFrontend` | 延迟求解（只检查 sat） |

### Veritesting 适用场景
```python
# 适合: 控制流简单、分支多、不需要每个路径独立分析
# 不适合: 路径间需要独立内存建模
simgr.use_technique(angr.exploration_techniques.Veritesting())
```

### 循环处理
```python
# 方法1: hook 循环出口
@proj.hook(0xloop_exit)
def loop_exit(state):
    state.inspect.loop_count += 1
    if state.inspect.loop_count > 64:
        state.inspect.exit_reason = "loop_bound"

# 方法2: 符号化循环计数器
for i in range(MAX_ITER):
    state.add_constraints(state.regs.rax < MAX_ITER)
```

## 常用 SMT 约束模式

### 等式/不等式
```python
# 密码长度 = 10
solver.add(sym_input.length == 10 * 8)  # 10 字节

# 前 4 字节 == "FLAG"
flag_prefix = claripy.BVV(b'FLAG')
solver.add(sym_input.get_byte(0) == flag_prefix.get_byte(0))
solver.add(sym_input.get_byte(1) == flag_prefix.get_byte(1))
# ... 或一次性添加:
solver.add(state.memory.load(addr, 4) == claripy.BVV(b'FLAG'))
```

### 范围约束
```python
# 可打印 ASCII
for i in range(32):
    byte = sym_input.get_byte(i)
    solver.add(byte >= 0x20)
    solver.add(byte <= 0x7E)
```

### 字符串/哈希
```python
# 子串匹配（用 concretize 然后 add constraints）
# MD5(prefix || sym) == target_hash
# → 复杂 — 通常需要 hook hash 函数然后倒推
```

### 路径可达性
```python
# 如果 solver.satisfiable() → 路径可达
# 如果 solver.unsatisfiable() → 死路径，丢弃
if not state.solver.satisfiable():
    state.history.drop()
```

## 实际场景脚本

### 场景1: Keygen 破解
```python
proj = angr.Project('./keygenme', auto_load_libs=False)
state = proj.factory.entry_state()

# 符号化输入（命令行参数）
sym_input = claripy.BVS('serial', 19 * 8)  # 假设序列号格式: XXXX-XXXX-XXXX-XXXX
for i in range(19):
    b = sym_input.get_byte(i)
    if i in (4, 9, 14):  # '-' 位置
        state.solver.add(b == ord('-'))
    else:
        state.solver.add(b >= ord('0'))
        state.solver.add(b <= ord('Z'))

# 找 success 地址
simgr = proj.factory.simulation_manager(state)
simgr.explore(find=0x401337)  # success message 地址

if simgr.found:
    solution = simgr.found[0].solver.eval(sym_input, cast_to=bytes)
    print(solution.decode())  # → "BEEF-DEAD-CAFE-BABE"
```

### 场景2: 密码恢复
```python
proj = angr.Project('./password_check', auto_load_libs=False)
state = proj.factory.entry_state()

sym_pass = claripy.BVS('pass', 16 * 8)
state.memory.store(0xA00000, sym_pass)  # 全局缓冲区

simgr = proj.factory.simulation_manager(state)

# 避免 "wrong" 路径，找 "correct" 路径
simgr.explore(find=0x401234, avoid=0x401200)

if simgr.found:
    pw = simgr.found[0].solver.eval(sym_pass, cast_to=bytes)
    print(pw)
```

### 场景3: 约束求解器独立使用
```python
import z3  # 或 claripy

# 反编译分析: r0 = (input[0] ^ 0x37) + 0x13
# 需要 r0 == 0x41 ('A')
solver = z3.Solver()
inp = z3.BitVec('inp', 32)
solver.add(((inp ^ 0x37) + 0x13) == 0x41)
if solver.check() == z3.sat:
    print(solver.model()[inp])  # → 输入的字节值
```

## 对抗反符号执行

### 常见对抗及应对
| 对抗手段 | 问题 | 应对 |
|---------|------|------|
| 大常量乘法 (opaque constant) | 约束复杂化 | concretize 到单一值 |
| 输入依赖循环 | 无限路径 | 设定循环上限 + 符号化计数器 |
| 外部调用 (系统调用/网络) | 环境缺失 | SimProcedures / Unicorn fallback |
| 浮点运算 | SMT 浮点理论弱 | concretize 浮点值 |
| 加密完整性检查 | 路径不可达 | patch binary 移除检查 |
| JIT/自修改代码 | PC 值动态 | 用 Unicorn 替代 angr |

### Opaque Predicate 绕过
```python
# 反编译发现的迷惑分支
# if ((x * 0xAAAAAAAB) >> 3 == 0x55555555) → 永远 false
# angr 可能无法简化 — 手动 concretize

@proj.hook(0xopaque_branch_addr)
def skip_opaque(state):
    # 强制跳转到真实路径 (NOP 掉假分支)
    state.regs.rip = 0xreal_path_addr
```

## 工具选择决策

| 需求 | 首选工具 | 备选 |
|------|---------|------|
| 通用二进制逆向 | angr | Manticore |
| 动态符号执行（带具体输入引导） | Triton | angr + Unicorn |
| 以太坊/EVM 分析 | Manticore | Mythril |
| 嵌入式/小端 ARM | angr + Unicorn | Triton |
| 大规模路径探索 | angr + Veritesting | KLEE |
| Java/DEX | angr (JVM 后端) | 手动 SMT |
| 速度优先 | Triton (C++ 核心) | angr |
| 原型快速开发 | angr Python API | Manticore |

## 约束求解器对比

| 求解器 | 速度 | 许可 | angr 兼容 |
|--------|-----|------|-----------|
| Z3 (Microsoft) | ★★★★★ | MIT | 默认后端 |
| Boolector | ★★★★ | MIT | 可选后端 |
| CVC4/CVC5 | ★★★ | BSD | 可选后端 |
| Yices 2 | ★★★★ | GPLv3 | 独立使用 |
| Bitwuzla | ★★★★ | MIT | 可选后端 |

## 常见陷阱

1. **浮点约束**：SMT 求解器对浮点的支持远弱于位向量——能用定点/整数就不要用浮点
2. **符号内存索引**：`array[sym_idx]` 导致状态爆炸——concretize 索引或限制范围
3. **ROS (Return-Oriented Symbolics)**：angr 默认不处理 ROP——用 Unicorn Engine 模拟
4. **多线程目标**：angr 和 Manticore 在线程调度方面有限——单一执行线程假设常见
5. **过大的符号内存**：几百 KB 以上 → 考虑符号化关键字段而非整个缓冲区
6. **libc 函数**：用 `auto_load_libs=False` + SimProcedures 避免加载整个 libc

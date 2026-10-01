# Linux 反调试技术补充参考

> 本文件补充 `anti-reverse.md` 中未覆盖的 Linux 特定反调试技术。

## 进程自检方法

### /proc/self/status 检查
```c
// TracerPid 检测 — 最经典的 Linux 反调试
int tracer = 0;
FILE *f = fopen("/proc/self/status", "r");
char buf[256];
while (fgets(buf, sizeof(buf), f)) {
    if (strncmp(buf, "TracerPid:", 10) == 0) {
        tracer = atoi(buf + 10);
        break;
    }
}
fclose(f);
// tracer != 0 表示被调试
```
**绕过**: `echo 0 > /proc/sys/kernel/yama/ptrace_scope` 或使用 LD_PRELOAD hook

### /proc/self/cmdline 检查
```bash
# 检查父进程是否为调试器
cat /proc/$PPID/cmdline  # 可能包含 gdb/lldb/strace/ltrace
```

### /proc/self/maps 检查
```bash
# 检查内存映射中是否有调试器痕迹
cat /proc/self/maps | grep -E '(gdb|lldb|libc-2\.\d+\.so$)' > /dev/null
```
**绕过**: `set environment LD_PRELOAD` + hook `fopen` / `open`

## ptrace 自跟踪

### 基本自跟踪
```c
#include <sys/ptrace.h>
// ptrace(PTRACE_TRACEME, 0, 0, 0) 成功返回 0
// 如果已被调试，返回 -1
if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
    // 已被调试 — 退出或执行反制措施
    exit(1);
}
```
**绕过 ptrace**: gdb 中用 `catch syscall ptrace` + 修改返回值；或用 LD_PRELOAD hook

### ptrace 变种
```c
// 变种1: fork() 后子进程 attach 到父进程
if (fork() == 0) {
    ptrace(PTRACE_ATTACH, getppid(), 0, 0);
    // 如果父进程被调试，attach 会失败
}

// 变种2: 多次 PTRACE_TRACEME (线程级检测)
for (int i = 0; i < 3; i++) {
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
        // 已被调试
    }
}
```

## 时间检测

### RDTSC 时间戳计数器
```asm
rdtsc           ; 读取时间戳到 EDX:EAX
mov esi, eax    ; 保存低32位
; ... 执行中间的代码 ...
rdtsc
sub eax, esi    ; 差值 = 经过的周期数
cmp eax, 1000   ; 阈值
jg  debugged    ; 超过阈值 → 有单步或软件断点
```
**绕过**: 在 gdb 中用 `set $eax=0` 伪造返回值，或用 ScyllaHide 等反反调试插件

### clock_gettime 精度检测
```c
struct timespec t1, t2;
clock_gettime(CLOCK_MONOTONIC, &t1);
// 执行简单操作
int x = 1 + 1;
clock_gettime(CLOCK_MONOTONIC, &t2);
long diff_ns = (t2.tv_sec - t1.tv_sec) * 1000000000L + (t2.tv_nsec - t1.tv_nsec);
if (diff_ns > 10000) {
    // 被单步执行干扰 → 有调试器
}
```

## 信号处理反调试

### SIGTRAP 处理
```c
signal(SIGTRAP, handler);
asm("int3");  // 产生 SIGTRAP
// 如果有调试器，int3 被调试器捕获而不发给进程
// 如果 handler 没有被调用 → 被调试
```

### 阻塞 SIGTRAP
```c
// 阻塞所有信号 → int3 后调试器无法正常控制
sigset_t mask;
sigfillset(&mask);
sigprocmask(SIG_BLOCK, &mask, NULL);
asm("int3");  // 调试器收到 SIGTRAP 但进程状态混乱
```

## 环境变量检测

```c
// 常见调试工具环境变量标记
const char *env_indicators[] = {
    "LD_PRELOAD",           // 函数hook
    "LD_LIBRARY_PATH",      // 库路径劫持
    "LD_DEBUG",             // 链接器调试
    "LD_AUDIT",             // 审计模块
    "CORECLR_PROFILER",     // .NET profiler
    "JAVA_TOOL_OPTIONS",    // Java agent
    "FROZEN_CRAB", "UMBA",  // Rust 调试宏残留（少见）
    NULL
};
```
**绕过**: gdb 中用 `unset environment VAR`，或 `set exec-wrapper env -u VAR`

## /proc 文件系统技巧

### /proc/self/exe 检测
```bash
# 检查自身是否通过非标准路径执行
ls -la /proc/self/exe
# 调试器可能通过 "/memfd:" 或 "/tmp/..." 启动
```

### /proc/self/fd 检测
```bash
# 检查是否有意外文件描述符（调试器的 pipe/socket）
ls -la /proc/self/fd | wc -l
# 正常进程 fd 数量通常较少（< 20）
```

### /proc/self/mem 检测
```c
// 尝试直接写 /proc/self/mem — 某些内核配置下可检测调试器
int fd = open("/proc/self/mem", O_RDWR);
// 成功打开并写入 → 可能被调试器挂载的 proc 文件系统暴露
```

## 硬件断点检测

```c
// 检查调试寄存器（DR0-DR3, DR7）
// 通过 fork + ptrace 或直接内联汇编
unsigned long dr0, dr1, dr2, dr3, dr7;
asm volatile(
    "mov %%dr0, %0\n"
    "mov %%dr1, %1\n"
    "mov %%dr2, %2\n"
    "mov %%dr3, %3\n"
    "mov %%dr7, %4\n"
    : "=r"(dr0), "=r"(dr1), "=r"(dr2), "=r"(dr3), "=r"(dr7)
);
if (dr0 | dr1 | dr2 | dr3) {
    // 设置了硬件断点 → 被调试
}
```
**绕过**: gdb 用软件断点替代；`set $dr0=0`

## ELF 完整性自校验

### .text 段校验和
```c
// 计算代码段的 CRC/MD5，检测软件断点 (int3 = 0xCC)
unsigned char *text_start = &__executable_start;  // 链接器符号
size_t text_len = &__etext - &__executable_start;
uint32_t crc = crc32(text_start, text_len);
if (crc != expected_crc) {
    // 代码被修改 → 断点或 hook
}
```

### 函数头检测
```c
// 检查关键函数第一条指令是否为 int3 (0xCC)
if (*(unsigned char *)critical_function == 0xCC) {
    // 该函数入口被设置了软件断点
}
```

## 父子进程联动

### fork 守护模式
```c
pid_t child = fork();
if (child == 0) {
    // 子进程: 持续 ptrace 父进程
    ptrace(PTRACE_ATTACH, getppid(), 0, 0);
    while (1) {
        waitpid(getppid(), &status, 0);
        ptrace(PTRACE_CONT, getppid(), 0, 0);
    }
} else {
    // 父进程: 正常执行
    // 调试器若 attach 到父进程，子进程的 ptrace 会失败
}
```

### 心跳检测
```c
// 父子进程通过 shared memory 或 pipe 维持心跳
// 调试器介入 → 心跳停止 → 进程自杀
```

## 工具与绕过方法

### 通用反反调试
| 工具 | 方法 |
|------|------|
| ScyllaHide (x64dbg) | 用户态/内核态 hook 清理 |
| HyperHide | Hypervisor 级反反调试 |
| TitanHide | 内核驱动级隐藏 |
| Frida | `Interceptor.attach` hook ptrace |
| LD_PRELOAD | `.so` 注入 hook 关键 libc 函数 |

### Frida 绕过脚本示例
```javascript
// Hook ptrace 返回 0 (表示成功)
const ptrace = Module.findExportByName(null, "ptrace");
Interceptor.attach(ptrace, {
    onLeave(retval) {
        if (this.context.rdi.toInt32() === 0) {  // PTRACE_TRACEME
            retval.replace(0);
        }
    }
});
```

### gdb 命令速查
```
# 绕过常见反调试
set environment LD_PRELOAD=
handle SIGTRAP nostop noprint pass
set $rax=0                          # 修改返回值
catch syscall ptrace
  commands
    set $rax=0
    continue
  end
```

## 检测优先级流程

```
1. ptrace(PTRACE_TRACEME)          ← 最快，零依赖
2. /proc/self/status TracerPid     ← 经典，易绕过
3. 时间检测 (RDTSC / clock_gettime) ← 检测单步执行
4. ELF .text 段校验和               ← 检测软件断点
5. 硬件断点检测 (DR0-DR3)           ← 检测硬件断点
6. 环境变量检测                     ← 辅助手段
7. 父进程检测                       ← fork() + 心跳
```

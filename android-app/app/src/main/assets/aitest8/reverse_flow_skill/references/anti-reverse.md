# Anti-Reverse Engineering Techniques & Bypasses

## Detection taxonomy

### Anti-debugging (user-mode)

| Technique | API / Method | Bypass |
|---|---|---|
| BeingDebugged flag | `IsDebuggerPresent()` | Patch return value to 0; set PEB.BeingDebugged = 0 |
| NtGlobalFlag | Check `PEB.NtGlobalFlag` (0x68+0x70 flags) | Set to 0 in PEB |
| Heap flags | `PEB.ProcessHeap.Flags` / `ForceFlags` | Set Flags=2, ForceFlags=0 |
| DebugObject handle | `NtQueryInformationProcess(ProcessDebugPort)` | Return 0 (no debug port) |
| DebugObject check | `NtQueryInformationProcess(ProcessDebugObjectHandle)` | Return STATUS_PORT_NOT_SET |
| CloseHandle exception | `CloseHandle()` with invalid handle → EXCEPTION if debugged | NOP the check or patch exception handler |
| INT 2D (x86) | `int 0x2d` — kernel debugger check | NOP or skip; instruction behaves differently under debugger |
| INT 3 scan | Scanning code for 0xCC bytes (software breakpoints) | Use hardware breakpoints or avoid INT 3 in protected code |
| INT 1 (TF check) | Check Trap Flag in EFLAGS | Patch TF check; use VEH not SEH |
| Timing checks | `rdtsc` / `QueryPerformanceCounter` before/after | Patch timing check; set breakpoint AFTER timing |
| Self-debugging | Process debugs itself → prevents 2nd debugger attach | Attach before self-debug or patch out child process creation |
| Parent process check | Check if parent is not explorer.exe / cmd.exe | Launch target normally, then attach |
| Process list scan | Look for debugger process names (x64dbg, windbg, ollydbg, etc.) | Rename debugger executable; kernel debugging |
| Window title scan | `FindWindow("OllyDbg", NULL)` | Rename window class; use command-line debugger |

```c
// PEB offsets (x64)
// PEB = GS:[0x60] (x64) or FS:[0x30] (x86)

// PEB.BeingDebugged @ offset 0x2
// PEB.NtGlobalFlag  @ offset 0xBC (x64) or 0x68 (x86)

// Manual bypass in debugger (x64dbg):
// dump gs:[60] + 2 → set byte to 0
// dump gs:[60] + BC → set dword to 0
```

### Anti-VM / Sandbox detection

| Technique | Indicators | Bypass |
|---|---|---|
| CPUID hypervisor bit | `cpuid` leaf 0x1, ECX bit 31 | Set hypervisor bit to 0 in VM config; patch check |
| VM MAC addresses | 00:50:56 (VMware), 00:0C:29, 00:1C:42, 00:05:69, 08:00:27 (VirtualBox), 00:15:5D (Hyper-V) | Change VM MAC address |
| VM registry keys | HKLM\SOFTWARE\VMware, HKLM\SYSTEM\...\vbox, HKLM\SOFTWARE\Oracle\VirtualBox | Remove or mask registry keys |
| VM processes | vmtoolsd.exe, vboxservice.exe, VBoxTray.exe | Kill or rename VM tools |
| VM drivers | vmci.sys, vmxnet.sys, vmmouse.sys, vboxguest.sys | Check driver list |
| VM files | C:\Windows\System32\drivers\vmmouse.sys, vmtools.dll | Mask files |
| Disk/device names | "VMWARE", "VBOX", "QEMU", "VIRTIO" in device names | Rename virtual disk |
| CPU cores | < 2 cores → suspicious | Allocate >= 2 cores |
| RAM size | < 2GB → suspicious | Allocate >= 4GB |
| Screen resolution | 800x600 / 1024x768 (classic VM defaults) | Change resolution |
| Hardware IDs | WMI: Win32_ComputerSystem, Win32_BIOS (VM strings) | Modify .vmx BIOS strings |
| Timing attacks | Long instruction sequences slower on VM (especially CPUID/RDTSC) | Accept slightly slower speeds |

```bash
# Common VM detection tools (know your enemy)
# pafish (https://github.com/a0rtega/pafish) — comprehensive VM/analysis tool check
# al-khaser (https://github.com/LordNoteworthy/al-khaser) — anti-analysis test suite
# Use these to understand what malware checks for
```

### Anti-disassembly

| Technique | How it works | Counter |
|---|---|---|
| Opaque predicates | Always-true/false branches that confuse disassembler | Manual NOP; script detection |
| Jump into middle of instruction | `jmp label+1` — disassembler misaligns | Toggle byte at label; rizin `ahi` commands |
| Return address abuse | `push addr; ret` instead of `jmp` | Follow control flow manually |
| SEH-based control flow | Exception handler used as normal control flow | Map all exception handlers |
| Call stack tampering | Overwrite return address dynamically | Trace execution; don't trust ret |
| Import hiding | Dynamic import resolution (LoadLibrary/GetProcAddress) | Dump resolved IAT at runtime |
| API hashing | Hash API name → resolve by hash lookup | Brute-force or dump at runtime; capa/hashdb |
| Encrypted/compressed code sections | Decrypt at runtime | Dump process memory after decryption |
| Stolen bytes | First N bytes of function moved → disassembler sees wrong code | Trace or memory dump |

### Packers and protectors

| Type | Examples | Identification signatures | Unpacking strategy |
|---|---|---|---|
| Simple compressor | UPX, ASPack, FSG | `upx -l`, section names (UPX0/UPX1) | `upx -d` or dump at OEP |
| Intermediate | MPRESS, PECRYPT32, Petite | Sparse imports, small .text, loaders in .rsrc | ESP trick, memory breakpoint on OEP |
| Advanced protector | Themida, VMProtect, Enigma, Obsidium | VM section, many anti-debug, driver component | Kernel debugger, API logging, TitanHide/ScyllaHide |
| Custom packer | Unique stub, unknown patterns | No tool match, manual analysis | Dynamic trace + dump at OEP + import reconstruction |
| .NET obfuscator | ConfuserEx, .NET Reactor, Obfuscar | Corrupted IL, encrypted strings, proxy calls | de4dot, ILSpy+dnlib, UnConfuserEx |
| JavaScript/Python obfuscator | obfuscator.io, PyArmor, PyInstaller | encoded strings, VM-based bytecode | AST deobfuscation, bytecode extraction |

```bash
# UPX detection and unpacking
upx -l <file>                   # List compression info
upx -d <file> -o <unpacked>     # Decompress

# Detect It Easy (packer/compiler identification)
diec <file>
diec --all <file>

# Generic unpacking approach
# 1. Load in debugger (x64dbg) with ScyllaHide plugin
# 2. Set breakpoint on VirtualAlloc/VirtualProtect
# 3. Run to OEP (often: pushad → ... → popad → jmp OEP)
# 4. Dump process memory (Scylla plugin)
# 5. Rebuild IAT (Scylla plugin)
# 6. Fix PE headers if needed
```

### Control-flow flattening

```
Normal:           Flattened:
  block_A           block_dispatcher(state_var)
  block_B             case 0: block_A; state = 1; break
  block_C             case 1: block_B; state = 2; break
  block_D             case 2: block_C; state = 3; break
                       case 3: block_D; state = -1; break

Detection:
- Large switch/dispatch blocks with single "next state" variable
- Many unconditional jumps to same dispatcher
- Loops that don't correspond to source-level loops

Recovery:
- Trace execution and record visited block sequence
- Build control flow graph from trace
- Use symbolic execution (angr, Manticore) to enumerate reachable states
- D810 (Ghidra plugin) for automated un-flattening
```

### String obfuscation

| Technique | Example | Recovery |
|---|---|---|
| Stack string | Push bytes to stack, reference ESP | Emulate function; FLOSS stack-string mode |
| XOR string | XOR each byte with key | Find XOR key, decode |
| RC4-encrypted strings | RC4(K, plaintext) | Extract key + ciphertext, decrypt |
| AES-encrypted strings | AES decrypt at runtime | Dump after runtime decryption |
| Chained decryption | str = decodeB(decodeA(data)) | Emulate or dump at final stage |
| Compressed strings | zlib/LZMA decompress strings at use | Dump after decompression |
| Encrypted string table | All strings in one encrypted blob, indexed | Dump post-decryption table |

```bash
# FLOSS for obfuscated string extraction
floss <binary>                          # All modes
floss --only static <binary>            # Static strings only
floss --only stack <binary>             # Stack strings only
floss --only decoded <binary>           # Decoded strings only
floss -q <binary> > strings.txt         # Quiet output to file

# Manual XOR string decode (Python)
def xor_decode(data: bytes, key: int | bytes) -> str:
    if isinstance(key, int):
        return bytes(b ^ key for b in data).decode('utf-8', errors='replace')
    return bytes(data[i] ^ key[i % len(key)] for i in range(len(data))).decode('utf-8', errors='replace')
```

## Bypass tools and techniques

### ScyllaHide (x64dbg plugin)

```
Hides debugger presence:
- PEB.BeingDebugged = 0
- PEB.NtGlobalFlag = 0
- NtQueryInformationProcess hooks
- NtSetInformationThread (HideFromDebugger) hooks
- NtClose (invalid handle) hooks
- Process/thread creation hooks (inheriting debug state)
```

### TitanHide (kernel driver)

```
Kernel-level hiding:
- DebugObject hiding via SSDT hook
- Process/thread hiding
- Works against kernel-level anti-debug checks
```

### Frida bypass patterns

```javascript
// Hook IsDebuggerPresent
Interceptor.attach(Module.findExportByName('kernel32.dll', 'IsDebuggerPresent'), {
    onLeave: function(retval) {
        retval.replace(0);  // Always return FALSE
    }
});

// Hook CheckRemoteDebuggerPresent
Interceptor.attach(Module.findExportByName('kernel32.dll', 'CheckRemoteDebuggerPresent'), {
    onLeave: function(retval) {
        // Set pbDebuggerPresent to FALSE
        var arg0 = this.context.rcx;  // HANDLE hProcess
        var arg1 = this.context.rdx;  // PBOOL pbDebuggerPresent
        if (arg1.isNull() === false) {
            arg1.writeInt(0);
        }
        retval.replace(1);  // Return TRUE (success)
    }
});

// Hook NtQueryInformationProcess (ProcessDebugPort)
var ntQueryInfo = Module.findExportByName('ntdll.dll', 'NtQueryInformationProcess');
Interceptor.attach(ntQueryInfo, {
    onLeave: function(retval) {
        // ProcessDebugPort = 7
        if (this.context.edx === 7) {
            var debugPort = this.context.r8;
            if (!debugPort.isNull()) {
                debugPort.writeU64(0);  // Zero out debug port — no debugger
            }
            // Do NOT return an error status; leave STATUS_SUCCESS (0)
            // so NT_SUCCESS() macros pass and the caller sees a clean result
        }
    }
});

// Hook timing check (QueryPerformanceCounter)
Interceptor.attach(Module.findExportByName('kernel32.dll', 'QueryPerformanceCounter'), {
    onLeave: function(retval) {
        // Add small random variation to evade timing detection
        // while still returning "real enough" values
    }
});

// Anti-anti-VM: Patch VM detection in WMI queries
// Common approach — hook OLE32/StringFromCLSID or COM object creation
```

### x64dbg scripting

```
; x64dbg script: bypass common anti-debug
; Set BeingDebugged to 0 (via ScyllaHide plugin or manual PEB edit)
; ScyllaHide: tick "PEB->BeingDebugged" in plugin settings
; Manual: dump gs:[60]+2, set byte to 0 in memory map
; Set NtGlobalFlag to 0 (ScyllaHide handles this automatically)
; dump gs:[60]+BC, set dword to 0

; Patch IsDebuggerPresent to return 0
bphws <IsDebuggerPresent>, "x"
bpgoto <OEP>
```

## Obfuscation deconstruction workflow

```
1. Detect obfuscation type (entropy, import count, anti-debug presence)
2. Identify packer/protector family (DIE, PEiD, manual signatures)
3. If known packer → use known unpacking method
4. If unknown/custom:
   a. Run in debugger with anti-anti-debug plugins
   b. Break on VirtualAlloc/VirtualProtect/WriteProcessMemory
   c. Find OEP (Original Entry Point) via stack trace or tail jump
   d. Dump unpacked memory
   e. Reconstruct IAT (Scylla / ImportREC)
   f. Fix PE headers and section alignment
5. Post-unpacking:
   a. Extract strings (FLOSS)
   b. Identify real imports
   c. Search for residual anti-analysis code
   d. Locate and decode obfuscated data sections
6. Document: packer type, OEP offset, unpacking method, post-unpack hashes
```

## Anti-tamper / Integrity checks

| Type | How it works | Bypass |
|---|---|---|
| Checksum/CRC | Compute hash of .text section, compare with stored value | Patch stored hash or hook comparison |
| Self-hashing | Code computes hash of itself | Patch the compute function to always return correct hash |
| Dual-layer integrity | Loader checks main code, main code checks loader | Patch both checks or memory-patch after all checks pass |
| Timed re-check | Periodically re-validates code integrity | Hook timer or patch all check instances |
| Remote integrity | Server validates client binary hash | MITM response or patch client's hash function |
| Hardware-bound | Tied to machine ID, TPM, or specific hardware | Emulate expected hardware; patch hardware check |

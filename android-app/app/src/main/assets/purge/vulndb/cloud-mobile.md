# 云与移动面速查（云元数据 / 容器 / 移动端 / 小程序）

## 云元数据与凭据

- 探测：`curl http://169.254.169.254/latest/meta-data/`（AWS）、
  `http://metadata.google.internal/computeMetadata/v1/`（GCP，需 Metadata-Flavor 头）、
  Azure IMDS `http://169.254.169.254/metadata/instance?api-version=2021-02-01`。
- 取临时凭据：AWS `.../iam/security-credentials/<role>`；GCP service-account/token。
- 滥用：S3 未授权读写（`aws s3 ls s3://bucket`）、云函数/ECS 环境变量里的密钥。

## 容器 / K8s

- 暴露面：`kubelet 10250` 未鉴权（`/pods`）、kube-apiserver 匿名访问。
- SA token 滥用：`cat /var/run/secrets/kubernetes.io/serviceaccount/token` 调 API。
- 逃逸面：privileged 容器、hostPath 挂载（读 `/host`）、docker.sock、cgroup release_agent。
- 集群提权：RBAC 宽松（list secrets）、helm chart 后门、image 供应链投毒。

## 移动端（Android / iOS）

- 静态：jadx/apktool 定位 CHECK_FN、硬编码密钥（`strings` / `grep -r key`）。
- 动态：frida hook HOOK_PTR（证书校验/加密函数）；`objection` 绕过 root/SSL 检测。
- 加固绕过：脱壳（frida-dexdump）、模拟器指纹对抗、so 层算法还原。
- iOS：class-dump / frida-objc 挂钩；IPA 重签名（`codesign -f -s -`）。

## 小程序 / 混合应用

- 解包：WXAPKG_TOOL 解包 wxapkg；反编译提取 ENDPOINT_LIST 与加密 CHECK_FN。
- 抓包：hook `wx.request` 看明文请求；绕过证书校验后抓 HTTPS。
- 回灌：修改后重打包/热更新验证（SIGN_BYPASS_SNIPPET）。

## 修复要点

元数据服务禁访问 + IMDSv2 强制；容器最小权 + 非 root + 只读 FS；RBAC 最小化；
移动端密钥服务端托管 + 混淆加固 + 证书固定可配置化；小程序敏感逻辑服务端校验。

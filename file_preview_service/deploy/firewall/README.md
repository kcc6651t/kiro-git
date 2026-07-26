# Agent 端口防火墙白名单

每台目标服务器上的 Agent 只监听内网端口（默认 `9443`），且**只允许中心主服务的 IP 访问**。这是 mTLS 之外的第一道网络边界。

假设中心主服务 IP 为 `10.10.0.5`，Agent 端口为 `9443`。

## iptables

```bash
# 只放行中心主服务，其余一律拒绝
iptables -A INPUT -p tcp -s 10.10.0.5 --dport 9443 -j ACCEPT
iptables -A INPUT -p tcp --dport 9443 -j DROP

# 持久化（示例，视发行版而定）
# Debian/Ubuntu: netfilter-persistent save
# RHEL/CentOS:   service iptables save
```

## firewalld

```bash
firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="10.10.0.5/32" port protocol="tcp" port="9443" accept'
# 默认区域不放行 9443，其它来源自动被拒绝
firewall-cmd --reload
```

## 云安全组

在安全组入站规则中：

- 允许：来源 `10.10.0.5/32`，协议 TCP，端口 `9443`
- 拒绝 / 不添加其它任何 `9443` 入站规则（安全组默认拒绝）

## 校验

```bash
# 从中心主服务器应能连通
curl -k https://<agent-ip>:9443/agent/v1/health   # 仍会因缺少客户端证书失败，但 TCP 可达

# 从其它主机应超时或被拒绝
```

> Agent 同时在 `agent.yml` 的 `security.allowedSourceCidrs` 中再次校验来源 IP，形成纵深防御。即便防火墙误配置，Agent 仍会拒绝非授权来源。

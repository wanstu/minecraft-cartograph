# Cartograph 局域网原型预览服务

## 地址

- 推荐（eadm 已实测）：**http://wanstu-dev.local:8765/**
- IP 备用（2026-11-09）：**http://192.168.0.106:8765/**
- 健康检查：http://wanstu-dev.local:8765/healthz
- 服务仅允许 `192.168.0.0/24` 与本机 `127.0.0.0/8` 访问，其他地址返回 403。服务为只读静态网站，无上传、写入和执行接口。

## 文件与更新方式

- 首页：`preview/live/index.html`
- 大地图：`preview/live/cartograph_大地图_交互高保真.html`
- 小地图：`preview/live/cartograph_小地图_HUD原型.html`
- 总设计：`preview/live/cartograph_总设计与开发规范.html`

所有资源都存放在 `/home/wanstu/projects/minecraft-cartograph/preview/live/`，服务仅发布该目录，**不暴露源码、环境配置或 Git 信息**。以后更新原型，把 HTML 覆盖到对应路径即可；服务不需要重启，用户刷新浏览器就能看到最新文件（HTML 不缓存）。

## 服务管理（在 dadm）

系统使用 Linux Mint 22.3 与 Python 3 标准库，**不需要 Nginx、Docker、WebGUI 或额外运行时**。

- 服务程序：`scripts/cartograph_prototype_server.py`
- systemd 用户服务：`~/.config/systemd/user/cartograph-prototype.service`
- 端口：`8765`，所有 IPv4 网卡监听，程序内检查局域网来源地址。
- 检查：`systemctl --user status cartograph-prototype.service`
- 启动：`systemctl --user start cartograph-prototype.service`
- 重启：`systemctl --user restart cartograph-prototype.service`
- 停止：`systemctl --user stop cartograph-prototype.service`
- 自动启动：已经 `systemctl --user enable --now`，**用户登录后自动启动**。
- 日志：`journalctl --user -u cartograph-prototype.service -n 50 --no-pager`
- 本地测试：`curl --noproxy '*' -sS http://127.0.0.1:8765/healthz`

**重启后未登录也要启动**：dadm 当前 `loginctl show-user wanstu -p Linger` 是 `Linger=no`，因此用户服务在用户登录前不会启动。如果需要开机无登录自动运行，必须在 dadm 用管理员权限手动执行一次 `sudo loginctl enable-linger wanstu`。这一步尚未执行（sudo 需要密码），不能声称支持“未登录即自启”。

如果机器 LAN 段将来不是 `192.168.0.0/24`，请同步更新服务器脚本 `ALLOWED` 地址段并重启服务；如果 DHCP 地址变化，优先访问 `wanstu-dev.local` 而不是固定 IP。

## 本轮测试

- dadm：`systemctl --user` 回报 `active (running)` 且 `enabled`；GET `/healthz` 返回 JSON；首页和 HTML 响应正常。
- eadm（Windows 192.168.0.104）：直接网络请求 `/`、`/healthz`、三份 V3 HTML 均返回 **HTTP 200**。
- eadm：`http://wanstu-dev.local:8765/healthz` 与 `http://wanstu-dev:8765/healthz` 均返回 200。
- 这只是原型服务的 HTTP 可达验证，**不等于** Java 模组原生 UI 已实现或高性能地图已经验收。

## 设计范围

本地局域网、个人原型测试使用。不要把 8765 端口转发到公网；如以后希望外部访问，应单独部署鉴权和 HTTPS。

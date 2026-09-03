# ArkDownloader Android

面向 Android 的**原生**多线程分片下载器（Kotlin + Jetpack Compose），与 [ArkDownloader](https://github.com/mengfz123/ArkDownloader) 桌面版 / [Mobile](https://github.com/mengfz123/ArkDownloader-Mobile) 功能集对齐：百度网盘直链友好、断点续传、前台服务保活，并提供 Gopeed 风格本地 RPC。

**当前版本：** 1.0.6

---

## 为什么选原生版

| 特色 | 说明 |
|------|------|
| Kotlin + Compose | Material 3，无 WebView / uni-app 壳 |
| 分片多线程 | OkHttp Range；连接数 1–16，分片 1–5 MB |
| 前台服务 | 下载保活，支持完成通知 |
| 本地 RPC | 默认端口 `18766`，路径与桌面 / Mobile 一致 |

---

## 功能一览

- 标签：正在下载 / 已完成 / 失败
- 全部暂停、全部继续、清空已完成
- 多行粘贴 URL 批量建任务
- 设置：下载目录、连接数、最大同时下载、分片、自动开始、完成通知
- 双 UA：百度 User-Agent / HTTP User-Agent
- RPC：可选令牌；可选绑定 `0.0.0.0` 供局域网调用

---

## 构建

```bash
./gradlew assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`  
要求：Android 8.0+（minSdk 26），JDK 17。

---

## 默认配置

| 项 | 默认 | 范围 |
|----|------|------|
| 连接数（connections） | 8 | 1–16 |
| 分片大小（chunkSizeMb） | 1 MB | 1–5 MB |
| 最大同时下载（maxRunning） | 3 | 1–10 |
| RPC 端口（rpcPort） | 18766 | 空令牌 = 不鉴权 |

---

## RPC

- `GET /health`、`GET /api/v1/info`
- `GET/PUT /api/v1/config`
- `GET/POST /api/v1/tasks`、`POST /api/v1/tasks/batch`
- `PUT /api/v1/tasks/:id/pause|continue`
- `POST /api/v1/resolve`

旧路径 `/api/*` 仍作别名。鉴权：`Authorization: Bearer <token>` 或 `X-ArkDownloader-Token`（`/health` 无需鉴权）。

---

## 相关项目

- [ArkDownloader](https://github.com/mengfz123/ArkDownloader) — Windows 桌面版
- [ArkDownloader-Mobile](https://github.com/mengfz123/ArkDownloader-Mobile) — uni-app Android 版

---

## 许可证

暂未指定开源许可证。发布或二次分发前请确认作者授权。
# ArkDownloader-Andriod

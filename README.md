# ArkDownloader Android

面向 Android 的**原生**多线程分片下载器（Kotlin + Jetpack Compose）百度网盘直链友好、断点续传、前台服务保活， RPC。
结合 网盘直链解析站 https://clouds.arkdream.top 实现百度网盘，夸克网盘不限速下载
<img width="1080" height="2414" alt="3c64e90c03b18b4a68e0b0a29fd4b99b" src="https://github.com/user-attachments/assets/df0283b7-26af-4bce-98ea-594a62809738" />

<img width="1240" height="2772" alt="f27264ec024bc1ac15f0377b91f14538" src="https://github.com/user-attachments/assets/73db778f-b853-4908-9c08-0c870d6cea7a" />


**当前版本：** 1.0.6

---


| 特色 | 说明 |
|------|------|
| Kotlin + Compose | Material 3，无 WebView / uni-app 壳 |
| 分片多线程 | OkHttp Range；连接数 1–16，分片 1–5 MB |
| 前台服务 | 下载保活，支持完成通知 |
| 本地 RPC | 默认端口 `18766` |

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
---

## 许可证

暂未指定开源许可证。发布或二次分发前请确认作者授权。
# ArkDownloader-Andriod

# 云视远程输入法

一款面向智能电视 / 电视盒子的远程输入法 + 遥控器 + 投屏播放器。
手机浏览器访问电视 IP 即可完成方向遥控、触摸板模拟、文字输入与在线投屏，无需安装手机 App。

## ✨ 功能特性

- 🎮 **全屏遥控**：上下左右 / OK / 返回 / 主页 / 菜单 / 音量键，支持长按连发，修复了原项目在弹框 / Dialog / 系统菜单场景下方向键失效的问题
- 👆 **触摸板**：手机滑动模拟触摸光标移动，支持单击 / 双击 / 长按 / 滚轮
- ⌨️ **跨屏输入**：手机/电脑键盘直接输入文字到电视，支持 PC 键盘方向键快捷操作
- 📺 **投屏播放**：基于 ExoPlayer 2.19.1 的新内核，支持 MP4 / MKV / HLS(.m3u8) / DASH / RTSP / RTMP 等主流格式，缓冲策略和硬解码兼容性全面升级
- 🔔 **在线更新**：通过 GitHub Release 发布，每个新版本只提示一次，支持强制更新
- 💾 **配置持久化**：服务端口、触摸灵敏度、自动更新、DLNA 名称等配置使用 SharedPreferences 持久化，APP 端与 Web 前端均可读写
- 🧠 **常驻内存优化**：输入法作为后台常驻服务，投屏 / DLNA / 更新检测等重型模块按需加载，不占用冷启动内存

## 🚀 使用方法

1. 使用 Android Studio 打开本项目编译，或在 GitHub Actions 自动构建出 APK；
2. 在电视/盒子上安装 APK；
3. 进入系统设置 → 输入法，启用「云视远程输入法」并将其**设为默认**；
4. 打开 App，页面会显示本机的 HTTP 服务地址，例如 `http://192.168.1.100:9978`；
5. 将手机 / 电脑 连接到同一局域网，浏览器访问该地址即可开始遥控。

> 提示：部分盒子默认禁用 ADB，若无法远程注入按键，请在电视"开发者选项"中开启「USB 调试」。

## 🔧 编译

本地编译：

```bash
./gradlew assembleRelease
```

输出 APK 位于 `IMEService/build/outputs/apk/release/`。

## 🤖 GitHub Actions 自动构建

仓库内置 `.github/workflows/build.yml`，推送 tag 即可自动编译并发布 Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

工作流会：

1. 使用 JDK 17 + Gradle 7.5 在 Ubuntu 环境下构建 Release APK；
2. 打包 `armeabi-v7a` + `arm64-v8a` 双架构（覆盖市面绝大多数电视/盒子）；
3. 自动创建 GitHub Release 并上传 `TVRemoteIME-<tag>.apk`。

## 🌐 HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | Web 控制端页面 |
| GET | `/api/version` | 获取本地版本 + 远程最新版本信息（前端检查更新用） |
| GET | `/api/config` | 获取当前持久化配置（JSON） |
| POST | `/api/config` | 更新配置（JSON body） |
| POST | `/key`、`/keydown`、`/keyup` | 发送按键 |
| POST | `/text` | 发送文字输入 |
| POST | `/touch` | 触摸板事件（滑动 / 点击 / 长按 / 滚轮） |
| POST | `/play` | 发起视频投屏播放 |
| POST | `/playStop` | 停止投屏 |
| WS | `/ws` | WebSocket 实时通道（按键/触摸更低延迟） |

## 📁 目录结构

```
TVRemoteIME/
├── IMEService/        # 主模块：输入法服务 + HTTP 服务器 + Web 前端 + 播放器
├── DroidDLNA/         # DLNA 投屏依赖
├── ijkplayer/         # 旧版播放器内核（保留兼容）
├── AdbLib/            # ADB 通信辅助
├── released/          # 版本信息（version.json）与发布资源
└── .github/workflows/ # GitHub Actions 自动构建
```

## 📄 License

GNU General Public License v2.0（见仓库根目录 `LICENSE`）

本项目基于 GPLv2 授权的小盒精灵 (TVRemoteIME) 二次开发，依照 GPLv2 的传染性，本衍生产物同样以 GNU General Public License v2.0 授权发布。任何人可自由使用、修改与再分发，但再分发时须同样以 GPLv2 开源并保留版权声明。

## 致谢

本项目基于 [小盒精灵 (TVRemoteIME)](https://github.com/kingthy/TVRemoteIME) 二次开发，在此基础上重新设计了 Web 前端 UI、引入 ExoPlayer 新内核、新增触摸控制、重构配置持久化与版本更新机制。

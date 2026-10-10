# 云视远程输入法

一款面向智能电视 / 电视盒子的远程输入法 + 遥控器 + 投屏播放器。
手机浏览器访问电视 IP 即可完成方向遥控、触摸板模拟、文字输入与在线投屏，无需安装手机 App。

## ✨ 功能特性

- 🎮 **全屏遥控**：上下左右 / OK / 返回 / 主页 / 菜单 / 音量键，支持长按连发，修复了原项目在弹框 / Dialog / 系统菜单场景下方向键失效的问题
- 👆 **触摸板**：手机滑动模拟触摸光标移动，支持单击 / 双击 / 长按 / 滚轮
- ⌨️ **跨屏输入**：手机/电脑键盘直接输入文字到电视，支持 PC 键盘方向键快捷操作
- 📺 **投屏播放**：基于 ExoPlayer 2.19.1 的新内核，支持 MP4 / MKV / HLS(.m3u8) / DASH / RTSP / RTMP 等主流格式，缓冲策略和硬解码兼容性全面升级
- 🔀 **双内核可切换**：Web 端一键在 ExoPlayer（硬解、省资源）与 IJKPlayer（FFmpeg 软解、兼容兜底）之间切换，应对老设备与冷门格式
- 📦 **ABI 分包**：按 CPU 架构分别产出独立 APK，单包体积显著减小；arm64 包不再携带仅 32 位的老内核 so
- 🔔 **在线更新**：通过 GitHub Release 发布，每个新版本只提示一次，支持强制更新
- 💾 **配置持久化**：服务端口、触摸灵敏度、播放器内核 / User-Agent、自动更新、DLNA 名称等配置使用 SharedPreferences 持久化，APP 端与 Web 前端均可读写
- 🧠 **常驻内存优化**：输入法作为后台常驻服务，投屏 / DLNA / 更新检测等重型模块按需加载；播放器独立进程运行，退出即由系统回收

## 🚀 使用方法

1. 使用 Android Studio 打开本项目编译，或在 GitHub Actions 自动构建出 APK；
2. 按电视 / 盒子的 CPU 架构选择对应安装包（不确定就选 `universal` 通用包），安装到设备；
3. 进入系统设置 → 输入法，启用「云视远程输入法」并将其**设为默认**；
4. 打开 App，页面会显示本机的 HTTP 服务地址，例如 `http://192.168.1.100:9978`；
5. 将手机 / 电脑 连接到同一局域网，浏览器访问该地址即可开始遥控。

> 提示：部分盒子默认禁用 ADB，若无法远程注入按键，请在电视"开发者选项"中开启「USB 调试」。

## 🔧 编译

本地编译：

```bash
./gradlew assembleRelease
```

由于启用了 ABI 分包，会输出三个 APK，均位于 `IMEService/build/outputs/apk/release/`：

| 文件 | 说明 |
|---|---|
| `TVRemoteIME-1.0.0-armeabi-v7a.apk` | 32 位 ARM，含 IJK / 迅雷内核 so，兼容老盒子 |
| `TVRemoteIME-1.0.0-arm64-v8a.apk` | 64 位 ARM，体积最小，播放走 ExoPlayer |
| `TVRemoteIME-1.0.0-universal.apk` | 双架构通用包，不确定架构时选它 |

## 🤖 GitHub Actions 自动构建

仓库内置 `.github/workflows/build.yml`，推送 tag 即可自动编译并发布 Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

工作流会：

1. 使用 JDK 17 + Gradle 7.5 在 Ubuntu 24.04 环境下构建 Release APK；
2. 按 ABI 分包产出 `armeabi-v7a` / `arm64-v8a` / `universal` 三个安装包；
3. 自动创建 GitHub Release 并上传以上 APK，命名为 `TVRemoteIME-<tag>-<abi>.apk`。

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
├── ijkplayer/         # 旧版播放器内核（保留兼容，仅 armeabi-v7a）
├── thunder/           # 迅雷下载内核（仅 armeabi-v7a）
├── AdbLib/            # ADB 通信辅助
├── released/          # 版本信息（version.json）与发布资源
└── .github/workflows/ # GitHub Actions 自动构建
```

## 🧩 播放器内核与性能优化

### 双内核对照

| 内核 | 解码方式 | 适合场景 | 资源占用 | 所在安装包 |
|---|---|---|---|---|
| **ExoPlayer**（默认） | 硬件解码（MediaCodec） | 主流格式、绝大多数设备 | CPU / 内存占用低 | 全部架构 |
| **IJKPlayer** | 软件解码（FFmpeg） | 老设备 / 冷门格式 / 硬解不兼容的流 | CPU / 内存占用较高 | 仅 armeabi-v7a |

选择建议：**日常保持 ExoPlayer 即可**；当某台老电视 / 盒子播放黑屏、花屏、格式不支持时，在 Web 端「📺 投屏」页把「播放器内核」切到 IJKPlayer 重试，其 FFmpeg 软解兜底对老设备与冷门封装更友好。

> 注意：IJK / 迅雷内核仅提供 `armeabi-v7a` 的 so，因此 **arm64-v8a 安装包不含这两个内核**，64 位包统一使用 ExoPlayer。需要 IJK 兜底时请安装 `armeabi-v7a` 或 `universal` 包。

### ABI 分包带来的体积收益

APK 体积的大头是 `lib/` 下的原生库（约 15MB，几乎全部是仅 32 位可用的 IJK / 迅雷内核 so）：

- **老做法**：单包同时塞入所有 ABI，安装包臃肿；
- **现在**：`armeabi-v7a` 与 `arm64-v8a` 各自独立成包 —— 32 位包体积小于合并包，64 位包因无需携带这两个 so 而**体积显著更小**；
- `universal` 通用包作为兜底，仅在无法判断设备架构时使用。

### 内存策略（面向低配设备）

- **播放器进程隔离**：三个播放器 Activity 均声明 `android:process=":player"`，播放结束退出后整个播放进程由系统回收，**常驻的输入法主进程内存不受影响**；
- **跨进程配置传递**：播放器在独立进程，配置不再直接读 SharedPreferences，而由主进程通过 Intent 传递 User-Agent，避免读到过期值；
- **按需加载**：DLNA 服务、触摸注入器、更新检测均在首次使用时才初始化；
- **内存压力自愈**：`IMEService.onTrimMemory()` 在系统内存吃紧时释放可重建的反射引用等资源；
- **体积压缩**：Release 开启 R8（`minifyEnabled` + `shrinkResources`），并用 `resConfigs` 只保留中英文资源。

> 关于 UPX：**不推荐**用 UPX 压缩 APK 内的 `.so`。UPX 属于"运行时自解压"，会占用额外内存，且其自解压桩需要可写可执行内存页，在 Android 10+ 的 W^X 限制下极易加载失败或崩溃，与"省内存"目标背道而驰。若要减小体积，应从 ABI 分包、R8、资源精简入手。

## 📄 License

GNU General Public License v2.0（见仓库根目录 `LICENSE`）

本项目基于 GPLv2 授权的小盒精灵 (TVRemoteIME) 二次开发，依照 GPLv2 的传染性，本衍生产物同样以 GNU General Public License v2.0 授权发布。任何人可自由使用、修改与再分发，但再分发时须同样以 GPLv2 开源并保留版权声明。

## 致谢

本项目基于 [小盒精灵 (TVRemoteIME)](https://github.com/kingthy/TVRemoteIME) 二次开发，在此基础上重新设计了 Web 前端 UI、引入 ExoPlayer 新内核、新增触摸控制、重构配置持久化与版本更新机制。

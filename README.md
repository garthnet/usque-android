# Usque VPN Android

<p align="center">
  <img src="icon.png" width="100" alt="Usque VPN Icon">
</p>

<p align="center">
  <strong>Cloudflare WARP / MASQUE VPN Client for Android</strong>
</p>

<p align="center">
  <a href="https://github.com/garthnet/usque-android/releases">
    <img src="https://img.shields.io/github/v/release/garthnet/usque-android?style=flat-square" alt="Release">
  </a>
  <a href="https://github.com/garthnet/usque-android">
    <img src="https://img.shields.io/github/license/garthnet/usque-android?style=flat-square" alt="License">
  </a>
  <a href="https://github.com/garthnet/usque-android">
    <img src="https://img.shields.io/badge/platform-android-blue?style=flat-square" alt="Platform">
  </a>
</p>

---

## ✨ Features

- 🚀 **Cloudflare WARP** - 基于 Cloudflare 的 MASQUE 协议 (RFC 9484)
- 🔒 **全局代理** - 所有应用流量通过 VPN
- 📱 **分应用代理** - 选择特定应用走代理，其他直连
- 🌍 **中英文切换** - 支持中文/英文界面
- 🎨 **Material Design** - 深色主题，紫色配色
- ⚡ **高性能** - 使用 Go gomobile 构建，原生性能

## 📲 Download

从 [Releases](https://github.com/garthnet/usque-android/releases) 页面下载最新版 APK。

> 每次构建自动递增版本号，无需手动管理。

## 🛠️ Build from Source

### Prerequisites

- Go 1.24+
- JDK 17
- Android SDK (API 34)
- gomobile

### Build Steps

```bash
# Install gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# Build AAR
cd android
make android

# Build APK (using Gradle)
cd usque-vpn
./gradlew assembleRelease
```

### GitHub Actions (Recommended)

Push to `main` branch or manually trigger the workflow:

```bash
gh workflow run build-apk.yml
```

APK will be automatically built and published as a GitHub Release.

## 📖 Usage

1. **安装 APK** - 下载并安装到 Android 设备
2. **首次运行** - 应用会自动注册 Cloudflare WARP 账户
3. **配置设置** - 点击 ⚙ Settings 配置 SNI、Endpoint 等参数
4. **选择模式** - Global (全局) 或 Per-App (分应用)
5. **连接** - 点击 Connect 按钮开始使用

### Proxy Modes

| Mode | Description |
|------|-------------|
| **Global** | 所有应用流量都通过 VPN |
| **Per-App** | 仅选中的应用走代理，其他直连 |

### Settings

| Parameter | Default | Description |
|-----------|---------|-------------|
| SNI | `engage.cloudflareclients.com` | TLS SNI 值 |
| Endpoint | `162.159.193.1:2408` | WARP 服务器地址 |

## 🏗️ Architecture

```
usque-android/
├── android/
│   └── usque-vpn/
│       ├── app/
│       │   ├── src/main/
│       │   │   ├── kotlin/          # Kotlin UI 代码
│       │   │   ├── res/             # 资源文件 (布局、图标、颜色)
│       │   │   └── libs/            # Go 编译的 AAR 库
│       │   └── build.gradle.kts
│       └── gradle/
└── .github/workflows/               # CI/CD
```

### Tech Stack

- **UI**: Kotlin + Android Views
- **VPN Core**: Go + gomobile → AAR
- **Protocol**: Cloudflare MASQUE (RFC 9484)
- **Theme**: Material Design (深色模式)

## 🎨 Design

- **主色调**: `#6c5ce7` (Purple)
- **高亮色**: `#a29bfe` (Light Purple)
- **背景**: `#1a1a2e` (Dark Navy)
- **卡片**: `#252542` (Dark Purple)
- **成功**: `#fdcb6e` (Gold)
- **错误**: `#e17055` (Coral)

## 📝 License

This project is based on [usque](https://github.com/Diniboy1123/usque) by Diniboy1123.

## 🙏 Acknowledgements

- [usque](https://github.com/Diniboy1123/usque) - Original CLI implementation
- [Cloudflare WARP](https://developers.cloudflare.com/warp-client/) - WARP protocol
- [MASQUE](https://datatracker.ietf.org/doc/rfc9484/) - RFC 9484

## 📮 Contact

- GitHub: [@garthnet](https://github.com/garthnet)

---

<p align="center">
  <sub>Made with ❤️ for a faster, more private internet</sub>
</p>

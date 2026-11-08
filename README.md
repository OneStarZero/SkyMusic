# SkyMusic
一个可以将midi文件转换为Sky Studio可读的txt文件并且可以自动软件的程序
<div align="center">

# 🎹 SkyMusic
**一款为《光·遇》量身定制的极客级 MIDI 智能转谱与自动演奏工具**

[![Build Status](https://img.shields.io/github/actions/workflow/status/OneStarZero/SkyMusic/build.yml?branch=main&style=for-the-badge&logo=github)](https://github.com/OneStarZero/SkyMusic/actions)
[![Release](https://img.shields.io/github/v/release/OneStarZero/SkyMusic?style=for-the-badge&color=38BDF8)](https://github.com/OneStarZero/SkyMusic/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%206.0%2B-3DDC84?style=for-the-badge&logo=android)](https://www.android.com/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

</div>

---

## 📖 简介 (Introduction)

**SkyMusic** 是一款完全开源、纯本地运行的安卓辅助工具。它将强大的 MIDI 解析能力与安卓原生的**无障碍自动化点击**技术完美结合。

不再需要费力地手动扒谱或寻找专门的纯音乐版本！只需导入普通 MIDI 文件，SkyMusic 就能运用极客级的算法，瞬间将繁杂的音符提取并清洗，转化为适合光遇弹奏的精准键位，**并在游戏内通过悬浮窗全自动演奏！**

## ✨ 核心特性 (Features)

- 🧠 **极客级 MIDI 解析**：内置 `tone-midi` 离线解析引擎，无需联网。智能过滤碎音、提炼主旋律、将宽音域自动平移至光遇 15 键范围。
- 📊 **全景数据面板**：转换完成后，详细展示原曲 BPM、时长、清洗掉的废音数量、智能移调半音数以及文件体积等硬核指标。
- 🪟 **原生悬浮窗控制**：无需切屏，在光遇游戏内即可实时控制播放、暂停。
- 🎯 **高精度键位校准**：每个手机屏幕比例不同？悬浮窗内置 15 键位拖拽校准模式，一次校准，永久缓存。
- 🤖 **无障碍自动演奏**：基于安卓 `AccessibilityService`，支持多指同时按下（和弦完美还原），毫秒级时间轴精准触发。
- 🔒 **完全离线与安全**：没有任何联网后门，不需要服务器，你的谱面和数据 100% 留在本地。

## 🚀 快速开始 (Quick Start)

### 1. 下载安装
前往 [Releases](https://github.com/OneStarZero/SkyMusic/releases) 页面，下载最新版本的 `SkyMusic-Release-APK.apk` 并安装到你的安卓手机（最低支持 Android 6.0）。

### 2. 开启必要权限
为了能够悬浮在游戏上并模拟点击，你必须手动开启两项权限：
- **悬浮窗权限**：允许应用显示在其他应用上层。
- **无障碍服务**：在系统设置 -> 辅助功能 -> 已下载的服务中，找到 `SkyMusic` 并开启。

### 3. 开始使用
1. 打开 SkyMusic，导入你的 `.mid` 或 `.midi` 文件。
2. 在网页界面中选择音轨，点击“开始全量还原”。
3. 点击网页底部的 **“🚀 发送到 App 自动演奏”**。
4. 切回《光·遇》，打开乐器界面。
5. 点击悬浮窗上的 **“🎯 拖拽校准键位”**，将 15 个点拖到琴键上并保存。
6. 点击 **“🎵 开始演奏”**，解放双手！

## 🛠️ 构建与开发 (Build)

本项目基于 Kotlin 和原生 Android WebView 开发，利用 GitHub Actions 实现全自动云端编译与签名。

```bash
# 克隆项目
git clone https://github.com/OneStarZero/SkyMusic.git

# 编译 Debug 版本
./gradlew assembleDebug

# 编译 Release 版本 (需配置签名环境变量)
./gradlew assembleRelease

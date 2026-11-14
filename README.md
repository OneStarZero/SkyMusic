<div align="center">

<!-- 图标展示区 -->
<!-- 如果你用的是 PNG 图片，请确保路径与此处一致；如果你用的是 SVG，请把 src 改为 "./logo.svg" -->
<img src="./app/src/main/res/mipmap-xxxhdpi/app_icon.png" alt="SkyMusic Logo" width="160" style="border-radius: 36px; box-shadow: 0 8px 30px rgba(56, 189, 248, 0.3); margin-bottom: 20px;">

# 🎹 SkyMusic
**《光·遇》MIDI 智能转谱与全自动演奏工具**

[![Release](https://img.shields.io/github/v/release/OneStarZero/SkyMusic?style=for-the-badge&color=38BDF8)](https://github.com/OneStarZero/SkyMusic/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%206.0%2B-3DDC84?style=for-the-badge&logo=android)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-7F52FF?style=for-the-badge&logo=kotlin)](#)
[![Web](https://img.shields.io/badge/Web-HTML5%2FJS-E34F26?style=for-the-badge&logo=html5)](#)

**[功能特性](#-核心特性) • [使用流程](#-快速开始) • [底层架构](#-底层架构) • [常见问题](#-常见问题-faq) • [自行编译](#️-自行编译)**

</div>

---

## 📖 简介 (Introduction)

**SkyMusic** 是一款基于 Kotlin 开发的纯本地安卓辅助工具。它将强大的 **JavaScript 音频解析能力**与安卓原生的 **无障碍自动化点击**技术完美结合。

不再需要费力地手动扒谱！只需导入普通 MIDI 文件，SkyMusic 就能运用极客级的算法，瞬间将繁杂的音符提取并清洗，自动平移至光遇 15 键的适配音域，**并通过悬浮窗在游戏内实现毫秒级全自动演奏。**

## ✨ 核心特性 (Features)

- 🧠 ** MIDI 解析**：网页端内置 `@tonejs/midi` 离线解析引擎。智能过滤碎音、提炼主旋律、解决和弦挤压。
- 📊 **全景数据面板**：原曲 BPM、时间跨度、剔除的废音数、智能移调半音数等硬核指标一目了然。
- 🪟 **原生悬浮窗控制**：基于 `WindowManager` 实现，无需切屏，在光遇游戏内即可实时控制播放。
- 🎯 **高精度键位校准**：不同手机屏幕比例不同？悬浮窗内置 15 键位拖拽校准模式，一次校准，永久本地缓存。
- 🤖 **多指自动演奏**：基于 `AccessibilityService` 与 `GestureDescription`，完美还原多指和弦，毫秒级时间轴精准触发。
- 🔒 **完全离线与隐私**：没有任何联网后门，不需要服务器，你的 MIDI 和谱面数据 100% 留在本地，断网也能用。

## 🚀 快速开始 (Quick Start)

### 1. 下载安装
前往 [Releases](https://github.com/OneStarZero/SkyMusic/releases) 页面，下载最新的 `app-release.apk` 并安装到你的安卓手机（兼容 Android 6.0 及以上）。

### 2. 开启必要权限
为了能够悬浮在游戏上并模拟点击，必须手动开启两项权限：
1. **悬浮窗权限**：允许应用显示在其他应用上层。
2. **无障碍服务**：在系统设置 -> 辅助功能 -> 已下载的服务中，找到 `SkyMusic` 并开启。

### 3. 极简使用流程
1. 打开 SkyMusic，在网页界面导入你的 `.mid` 文件。
2. 选择音轨（推荐选择钢琴或主旋律轨），点击“开始极速全量还原”。
3. 点击网页底部的 **“🚀 发送到 App 自动演奏”**。
4. 切回《光·遇》，打开乐器界面。
5. 点击悬浮窗上的 **“🎯 拖拽校准键位”**，将 15 个点拖到光遇琴键上并保存。
6. 点击 **“🎵 开始演奏”**，解放双手，享受音乐！

## 🛠️ 底层架构 (Architecture)

SkyMusic 采用了**混合开发（Hybrid）**架构，兼顾了网页 UI 的绚丽与原生底层的性能。

---

## ⚠️ 常见问题与免责声明 (FAQ & Disclaimer)

**Q: 为什么悬浮窗或者播放按钮点没反应？**
A: 请务必在系统设置中开启两项权限：
1. **悬浮窗权限**（显示在其他应用上层）。
2. **无障碍服务**（在系统设置 -> 辅助功能 -> 已下载的服务中找到 SkyMusic 并开启）。

**Q: 提示找不到谱面文件夹或无法保存？**
A: 请确保 App 拥有存储读写权限。首次运行并在网页端转换成功后，App 会自动在手机存储根目录下创建 `/storage/emulated/0/SkyMusic-OneStar/` 及其相关子文件夹。

**Q: 谱面能在别的设备上用吗？**
A: 可以的。`AppSheets` 和 `LocalSheets` 里保存的均是标准的 `txt` 格式（Sky Studio 兼容格式），你可以直接将其导出，发送给其他光遇玩家。

> **📜 免责声明**
> 本工具基于 Android 无障碍服务开发，仅供个人学习、交流与娱乐使用，请勿用于任何商业用途。使用本工具产生的任何游戏内风险（包括但不限于账号处罚等），由使用者自行承担，开发者不承担任何连带责任。

---

## 💖 致谢 (Credits)

- 感谢 [@tonejs/midi](https://github.com/Tonejs/Midi) 提供的核心 MIDI 解析支持。
- 感谢 [Sky Studio](https://github.com/Sky-Studio-Music) 提供的标准谱面格式规范。
- 感谢所有为 SkyMusic 提交 Issue 和提供测试反馈的光遇玩家。

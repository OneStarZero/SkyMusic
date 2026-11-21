<div align="center">

<!-- 图标展示区 -->
<img src="./app/src/main/res/mipmap-xxxhdpi/app_icon.png" alt="SkyMusic Logo" width="160" style="border-radius: 36px; box-shadow: 0 8px 30px rgba(56, 189, 248, 0.3); margin-bottom: 20px;">

# 🎹 SkyMusic
**《光·遇》MIDI 智能转谱、悬浮控制与全自动演奏工具**

[![Release](https://img.shields.io/github/v/release/OneStarZero/SkyMusic?style=for-the-badge&color=38BDF8)](https://github.com/OneStarZero/SkyMusic/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%206.0%2B-3DDC84?style=for-the-badge&logo=android)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-7F52FF?style=for-the-badge&logo=kotlin)](#)
[![Web](https://img.shields.io/badge/Web-HTML5%2FJS-E34F26?style=for-the-badge&logo=html5)](#)
[![CI](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions)](#)

**[项目简介](#-简介-introduction) • [核心特性](#-核心特性-features) • [快速开始](#-快速开始-quick-start) • [深度功能指南](#-深度功能与交互细节-deep-dive) • [底层架构](#-底层架构-architecture) • [存储规范](#-本地存储与谱面规范-storage) • [开发与编译](#-开发工作流与自行编译-build--ci) • [常见问题与免责](#-常见问题与免责声明-faq--disclaimer) • [更新日志](#-更新日志-changelog) • [致谢](#-致谢-credits)**

</div>

---

## 📖 简介 (Introduction)

**SkyMusic** 是一款基于 Kotlin 原生服务与 WebView 混合架构开发的纯本地安卓辅助工具。它将灵活的 **前端音轨解析与星光交互界面** 与安卓原生的 **悬浮窗服务（`WindowManager`）及无障碍手势点击（`AccessibilityService`）** 深度融合，让 MIDI 转谱、音轨筛选、谱面保存、键位校准与自动演奏在移动端形成完整闭环。

不再需要费力手动逐音扒谱！只需导入普通 `.mid` 文件，SkyMusic 即可提取音轨与旋律、清洗冗余音符、适配光遇键位排布，并通过悬浮面板与演奏控制舱在游戏内实现毫秒级全自动演奏。

---

## ✨ 核心特性 (Features)

- 🧠 **智能 MIDI 转谱与多模型识别**：内置离线解析与转谱能力，支持音轨选择、碎音过滤、主旋律提炼与多版本提取模型识别（如 `SkyFastPro`、`SkyPro`、`SkyFast` 等），并在曲目列表与播放器标题中以高亮蓝（`#60A5FA`）精细标出模型名称。
- 📊 **全景谱面数据洞察**：原曲 BPM、时间跨度、音符总数与谱面详情一览无余，演奏与导出前即可全面掌握曲目信息。
- 🪟 **深空玻璃悬浮窗与微型 44dp 星灵球**：
  - 悬浮球采用精巧克制的 **44dp**（约星标再大两圈）深空圆盘搭配温润星光 `✦` 核心视觉，去除多余外圈描边，减少屏幕遮挡并保留利落点按与拖拽手感。
  - 面板与控制舱默认采用 **85%（0.85f）** 视觉平衡透明度，在 **Android 12（API 31）及以上** 启用 `RenderEffect` 实时背景模糊，较低版本自动降级为高对比半透明图层兜底。
- 🎯 **自适应 15 键 / 9 键校准矩阵**：
  - 内置 **15 键（5×3 网格）** 与 **9 键（3×3 网格）** 校准模式，收紧网格间距并动态适配横竖屏比例，拖动 `1` 号锚点即可联动平移其余键位，支持 15 键与 9 键坐标独立持久化以及单曲键位模式覆盖。
- 🎛️ **演奏控制舱、胶囊倍速步进器与音符跳转**：
  - 配备圆角胶囊样式的减速（`−`）与加速（`+`）按钮及按压缩放回弹反馈，实时显示当前倍速、音符进度、时间进度与可拖拽进度条。
  - 点击音符进度支持弹窗输入序号快速跳转至指定音符，并支持 **播完自动返回曲目列表** 开关。
- 🔄 **原生 ↔ WebView 双向实时同步**：
  - 通过 `JavascriptInterface`（`AndroidBridge`）与 `evaluateJavascript` 打通前端与原生状态同步（如 `onResume` 恢复、页面加载完成与配置变更同步），并支持随机星空背景刷新联动。
- ⚡ **异步并发、低功耗与全版本 Android 兼容**：
  - 曲库扫描后台化与内存列表缓存、拖拽视口边界钳制、页面隐藏（`visibilitychange`）时暂停星空动画、Android 14+ 前台服务类型规范（`FOREGROUND_SERVICE_SPECIAL_USE`）适配及高低版本 SDK 兼容分支。
- 🔒 **完全离线与隐私安全**：无需联网服务器，谱面转换、保存、键位配置与自动点击均在本地设备完成，断网可用，不上传个人数据。

---

## 🚀 快速开始 (Quick Start)

### 1. 下载安装
前往 [Releases](https://github.com/OneStarZero/SkyMusic/releases) 页面下载最新版本的 Release APK 并安装到安卓设备（支持 Android 6.0 及以上，建议在 Android 12+ 设备体验完整模糊特效）。若从旧版本升级遇到签名冲突，卸载旧版后重新安装即可。

### 2. 开启必要权限
为了能够显示悬浮窗并在游戏内模拟点击，请手动开启以下权限：
1. **悬浮窗权限（显示在其他应用上层）**：允许应用在游戏或其他界面上方展示悬浮星灵球、曲库面板与演奏控制舱。
2. **无障碍服务（`AccessibilityService`）**：在系统设置 → 辅助功能 / 无障碍 → 已下载的服务中找到 **SkyMusic** 并开启。
3. **后台运行与电池策略（强烈建议）**：在系统设置中将 **SkyMusic** 设为“允许后台运行 / 无限制”，防止切入游戏后被系统清理悬浮服务。

### 3. 极简使用流程
1. 打开 **SkyMusic**，在网页界面导入你的 `.mid` 文件。
2. 选择音轨（推荐选择钢琴或主旋律轨），执行转换生成谱面。
3. 点击 **“发送到 App 自动演奏”** 或保存到本地曲库。
4. 切回《光·遇》并打开乐器琴键界面。
5. 点击悬浮星灵球展开面板，进入 **设置** 选择 **15 键校准** 或 **9 键校准**，拖动 `1` 号键对齐首个琴键后点击保存。
6. 选择谱面进入演奏控制舱，点击 **播放**，即可自动演奏；演奏中可随时通过 `−` / `+` 胶囊按钮调速或点击音符进度跳转。

---

## 🔍 深度功能与交互细节 (Deep Dive)

### 1. 悬浮窗面板与滚动状态记忆
- **三页全能面板**：包含「本地」（扫描 `LocalSheets`）、「发送」（展示 App 接收/发送的谱面与 `AppSheets` 文件）与「设置」三大分页，顶部支持标题跑马灯、手动刷新（`↻`）与收起为悬浮球（`✕`）。
- **滚动位置保持**：在刷新列表、切换设置项或拖动透明度滑块时，尽量保留当前滚动位置并处理滑动冲突，避免频繁跳回顶部。
- **阻尼与回弹动画**：悬浮球、全能面板、演奏控制舱与按钮均加入缩放与平滑显隐动画（`DecelerateInterpolator` / `OvershootInterpolator`），提升触控高级感。

### 2. 键位校准与演奏控制细节
- **紧凑自适应网格**：15 键（5×3）与 9 键（3×3）根据屏幕宽高与横竖屏状态动态计算键位步长与居中偏移，减少边缘溢出与空隙过大问题。
- **首键锚点联动**：拖动 `1` 号键时，其余键位按当前步长同步移动；也可单独微调某个键位点后保存。
- **灵活演奏控制**：支持默认倍速设置、演奏中 `±0.1x` 胶囊步进调速、点击容差配置、按音符序号跳转、进度条拖拽定位及播完自动回列表。

### 3. 前后端双向联动与视觉统一
- **模型高亮（`#60A5FA`）**：统一在曲目列表与播放器标题中识别并高亮显示模型名，让多模型对比更直观。
- **顶部操作栏治理**：主界面顶部快捷按钮放入圆角胶囊容器并支持横向滚动，避免窄屏挤压或状态栏遮挡。
- **星空背景省电**：前端星空动画在页面不可见时自动暂停，切回前台后继续运行，降低无意义渲染功耗。

---

## 🛠️ 底层架构 (Architecture)

SkyMusic 采用 **Kotlin 原生宿主 + WebView 前端引擎** 的混合开发架构，兼顾网页交互表现力与系统级自动化能力：

| 层级 | 核心文件 / 组件 | 主要职责与技术要点 |
| :--- | :--- | :--- |
| **前端交互层** | `index.html`、`style.css`、`bg.js` | MIDI 解析、参数调节、谱面载荷构造、星光背景动画与页面可见性暂停、接收原生状态回调与背景刷新指令 |
| **原生桥接层** | `MainActivity.kt`、`AndroidManifest.xml` | 承载 `WebView`、暴露 `AndroidBridge` 接口、处理本地保存与命名清洗、权限跳转、服务启动与高低版本 SDK 兼容 |
| **悬浮与演奏层** | `FloatingWindowService.kt` | 管理 `44dp` 悬浮球、三页全能面板、演奏控制舱、15/9 键校准矩阵、倍速与音符跳转、`85%` 透明度与 `RenderEffect` 模糊 |
| **手势执行层** | `SkyAccessibilityService.kt` | 基于 `AccessibilityService` 与 `GestureDescription` 接收坐标序列并执行多点/并发点击，还原和弦与节奏 |

---
## ⚠️ 常见问题(FAQ)

​Q: 为什么悬浮窗或者播放按钮点没反应？
A: 请务必在系统设置中开启两项核心权限：  
​悬浮窗权限（显示在其他应用上层）。  
​无障碍服务（在系统设置 → 辅助功能 / 无障碍 → 已下载的服务中找到 SkyMusic 并开启）。  
​Q: 校准点位置和游戏琴键有偏差怎么办？
A: 打开悬浮窗 设置 → 15 键校准 或 9 键校准，拖动 1 号键对齐首个琴键后点击保存；切换横竖屏或更换不同屏幕比例设备时，建议重新校准一次。  
​Q: 为什么会出现 Illegal escape: '\v' 或 \vert{} 相关的编译错误？
A: 这是字符串中误写了非法转义序列（如 \v 或 \vert{}）导致的 Kotlin 语法报错，在普通 Kotlin 字符串中应使用 || 或正确转义的反斜杠 \\。
​Q: 提示找不到谱面文件夹或无法保存？
A: 请确保已授予存储权限。首次运行或保存谱面时，应用会在 /storage/emulated/0/SkyMusic-OneStar/ 下自动创建 LocalSheets 与 AppSheets 目录。  
​Q: 谱面能在别的设备上用吗？
A: 可以的。AppSheets 和 LocalSheets 中保存的 .txt 谱面可直接导出分享给其他玩家，或复制到其他设备的对应目录中使用。  
___
## 📜 免责声明
本工具基于 Android 无障碍服务开发，仅供个人学习、交流与娱乐使用，请勿用于任何商业用途。使用本工具产生的任何游戏内风险（包括但不限于账号处罚等），由使用者自行承担，开发者不承担任何连带责任。  
___
## 📝 更新日志 (CHANGELOG)

​v0.0.8（星灵悬浮与自适应键位校准引擎）
​悬浮球与视觉升级：默认悬浮球调整为 44dp（去除外圈描边，深空圆盘 + 星光核心）；面板与控制舱默认 85% 透明度，Android 12+ 接入 RenderEffect 背景模糊；曲目与播放器标题支持模型名高亮蓝（#60A5FA）。  
​键位校准优化：收紧 15 键（5×3）与 9 键（3×3）校准网格间距与横竖屏自适应计算，支持 1 号锚点联动拖动与坐标保存。  
​播放器与倍速控制：重构圆角胶囊样式 − / + 倍速按钮与按压缩放动画，新增音符进度弹窗跳转与播完自动回列表开关。  
​双向通信与兼容性：扩展 AndroidBridge 并通过 evaluateJavascript 同步状态与背景刷新；优化顶部按钮栏、滚动位置记忆、后台扫描缓存、页面隐藏暂停动画及 Android 14+ 前台服务兼容。  
​问题修复：修复 MainActivity.kt 非法转义编译错误与面板滚动重置问题。 
___ 
## 💖 致谢 (Credits)
​感谢 @tonejs/midi 提供的核心 MIDI 解析支持。  
​感谢 Sky Studio 提供的标准谱面格式规范。  
​感谢所有为 SkyMusic 提交 Issue、测试截图与改进建议的光遇玩家。

___

## 📂 本地存储与谱面规范 (Storage)

谱面统一存放在外部存储根目录 `/storage/emulated/0/SkyMusic-OneStar/` 下：

```text
/storage/emulated/0/SkyMusic-OneStar/
├── LocalSheets/    # 本地曲库存放目录（.txt）
└── AppSheets/      # 应用发送 / 接收谱面存放目录（.txt）




```

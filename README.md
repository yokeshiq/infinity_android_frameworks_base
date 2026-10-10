# frameworks/base (Android 17) — Freeform & Mini Window Enhancement

![Android](https://img.shields.io/badge/Android-17%20(API%2037)-3DDC84?logo=android&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-AOSP%20%2F%20Infinity%20ROM-blue)
![License](https://img.shields.io/badge/License-Apache--2.0-orange)

本仓库为定制版 `frameworks/base`，基于 Android 17 (API 37) 源码构建。主要补全原生 AOSP 下悬浮小窗（Mini Window / Freeform）的底层调度逻辑，修复分屏与画中画切换遮罩冲突，并剔除影响系统稳定性的非必要钩子注入。

---

## 🌟 核心特性与修改点

### 1. 窗口管理器调度增强 (`WindowManagerService` & `ATMS`)
- 开放针对特定 Task 的 Freeform 窗口类型转换接口。
- 支持接收外部高权限广播直接拉起应用进入小窗模式。
- 优化小窗最小化、全屏化与多任务栈层级重排序（Z-Order）逻辑。

### 2. 状态修饰与遮罩层修复 (`DimmerWindow`)
- 修复 `com/android/server/wm/DimmerWindow.java` 在进入窗口固定状态时的绘制异常：
  - 正确接管 `enterPinnedWindowingMode()` 调用。
  - 解决小窗调整尺寸或移动时出现的画面冻结、黑屏及壁纸残影问题。

### 3. 原生 API 纯净度维护
- 剥离对 `bionic` 钩子及特定应用隐藏模块的隐式依赖，避免系统底层调用时触发异常 crash。

---

## 🏗️ 架构协作流程

```text
[Launcher3 / 侧边栏]
         │ (发送启动广播 / Task Action)
         ▼
[frameworks/base (ATMS & WMS)]
   ├── 校验 Task ID 与 Calling UID
   ├── 调整 WindowingMode 为 WINDOWING_MODE_FREEFORM
   └── DimmerWindow 同步透明通道与触控层
         │
         ▼
[LMOFreeform 控制容器渲染]

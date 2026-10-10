Android 17 (API 37) 原生悬浮小窗套件集成指南
本项目是一套面向 Android 17 (API 37) 定制 ROM 的原生悬浮小窗（Mini Window / Freeform）完整解决方案。通过整合底层窗口管理服务、系统桌面多任务触发器以及前台悬浮控制器，为开源 AOSP 系统提供类商业 ROM 的多任务小窗交互体验。

目录与组件仓库导航
本套件由三个深度协同的代码仓库组成：

底层框架服务 (frameworks/base) —— 负责 WMS/ATMS 层级切换、小窗模式调度与 Dimmer 遮罩层修复。

系统桌面与多任务 (packages/apps/Launcher3) —— 负责多任务卡片小窗快捷动作项注册、广播唤醒及 SDK 37 依赖适配。

悬浮小窗控制器 (packages/apps/LMOFreeform) —— 负责前台悬浮容器、手势拖拽、双向缩放、贴边吸附与状态栏装饰。

全流程部署与编译指南 —— Local Manifest 配置、机型引入及测试编译步骤。

功能验证与操作规范 —— 系统刷入后的运行检验流程。

协同架构流程
Plaintext
┌─────────────────────────────────────────────────────────────┐
│ 1. packages/apps/Launcher3 (桌面与多任务层)                 │
│    https://github.com/yokeshiq/packages_apps_Launcher3      │
│    用户在 Recents 任务卡片长按/下拉，点击「小窗模式」动作项  │
└──────────────────────────────┬──────────────────────────────┘
                               │ 发送显式广播 Intent (含 Target Task ID)
┌──────────────────────────────▼──────────────────────────────┐
│ 2. frameworks/base (系统底层中枢)                           │
│    https://github.com/yokeshiq/frameworks_base             │
│    WMS/ATMS 校验调用权限，切换为 WINDOWING_MODE_FREEFORM     │
│    DimmerWindow 维持透明图层与触控通道绑定                 │
└──────────────────────────────┬──────────────────────────────┘
                               │ 激活系统级前台浮动服务
┌──────────────────────────────▼──────────────────────────────┐
│ 3. packages/apps/LMOFreeform (前台悬浮窗控制器)             │
│    https://github.com/yokeshiq/packages_apps_LMOFreeform    │
│    接管小窗外边框绘制、标题胶囊栏、双指缩放手势与侧边吸附气泡│
└─────────────────────────────────────────────────────────────┘
1. 底层框架服务 (frameworks/base)
仓库地址：https://github.com/yokeshiq/frameworks_base

分支：17

源码路径：frameworks/base

核心修改点
窗口管理器调度增强 (WindowManagerService & ATMS)：

开放特定 Task 的 WINDOWING_MODE_FREEFORM 直接转换接口，允许系统特权组件拉起小窗。

优化小窗最小化、全屏化与多任务栈层级重排序（Z-Order）逻辑，防止窗口层级覆盖错乱。

优化 Activity 配置刷新（onConfigurationChanged）分发通道，避免小窗切换引起 Activity 重建导致数据丢失。

状态修饰与遮罩层修复 (DimmerWindow)：

修改文件：services/core/java/com/android/server/wm/DimmerWindow.java。

解决在窗口固定状态时正确接管 enterPinnedWindowingMode() 调用，修复拖拽或调整尺寸时出现的画面冻结、黑屏与残影。

2. 桌面与多任务模块 (packages/apps/Launcher3)
仓库地址：https://github.com/yokeshiq/packages_apps_Launcher3

分支：17

源码路径：packages/apps/Launcher3

核心修改点
多任务卡片小窗入口 (Recents Task Action)：

在 TaskMenuView 的卡片操作弹窗中注入小窗选项。

点击时捕获当前卡片目标 taskId，构造并发送系统广播唤起悬浮控制器。

API 37 平台兼容 (minSdkVersion 提升)：

修改 Android.bp，将 minSdkVersion 提升至 37，解决 Android 17 上游引入最新版 SettingsLib 引起的编译报错。

内置简体中文支持 (values-zh-rCN)：

补齐桌面 Quickspace 快捷信息、Google 智能镜头（Lens）以及 quickstep/res 中新增的 15 项全面屏手势教学与沙盒模式文案。

3. 小窗控制服务 (packages/apps/LMOFreeform)
仓库地址：https://github.com/yokeshiq/packages_apps_LMOFreeform

分支：17

源码路径：packages/apps/LMOFreeform

核心修改点
手势交互引擎：

自由移动：按压顶部胶囊条/标题栏可在屏幕任意区域无级拖动。

比例拉伸：支持双指缩放及右下角边缘拉伸调整窗口宽高。

边缘吸附：向屏幕两侧滑出时自动将小窗收纳为半透明悬浮气泡，点击气泡可还原前台窗口。

系统环境响应：

监听输入法（IME）唤起事件，键盘弹起时智能向上平移避让输入焦点。

遵循 Material You 设计规范，窗口边框自适应当前系统壁纸强调色。

4. 全流程部署与编译指南
步骤一：配置 Local Manifest
在 ROM 源码根目录下的 .repo/local_manifests/freeform.xml 文件中填入以下配置：

XML
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <!-- 1. 替换底层核心 frameworks/base -->
  <remove-project name="platform/frameworks/base" />
  <project path="frameworks/base" 
           name="yokeshiq/frameworks_base" 
           remote="github" 
           revision="17" />

  <!-- 2. 替换桌面与多任务 Launcher3 -->
  <remove-project name="platform/packages/apps/Launcher3" />
  <project path="packages/apps/Launcher3" 
           name="yokeshiq/packages_apps_Launcher3" 
           remote="github" 
           revision="17" />

  <!-- 3. 引入小窗控制器 LMOFreeform -->
  <project path="packages/apps/LMOFreeform" 
           name="yokeshiq/packages_apps_LMOFreeform" 
           remote="github" 
           revision="17" />
</manifest>
步骤二：同步仓库分支
在源码根目录下执行强制同步命令：

Bash
repo sync -c -j$(nproc) --force-sync frameworks/base packages/apps/Launcher3 packages/apps/LMOFreeform
步骤三：在编译配置中声明模块
为确保 LMOFreeform 被编译并打包进系统镜像，在机型 Makefile（如 device/xiaomi/munch/device.mk）或 ROM 统一配置（如 common.mk）中添加该包：

Makefile
# 启用原生悬浮小窗控制器服务
PRODUCT_PACKAGES += \
    LMOFreeform
步骤四：执行编译
1. 单模块测试编译（可选，排查构建错误时使用）：

Bash
# 验证底层服务编译
mma frameworks/base -j$(nproc)

# 验证桌面与多任务模块
mma Launcher3QuickStep -j$(nproc)

# 验证小窗控制器
mma LMOFreeform -j$(nproc)
2. ROM 全量构建打包：

Bash
source build/envsetup.sh
lunch infinity_munch-userdebug
m bacon -j$(nproc)
5. 功能验证与操作规范
刷入系统并首次启动后，按以下步骤验证小窗链路是否正常工作：

服务检查：通过终端检查小窗控制服务包是否正常集成：

Bash
adb shell pm list packages | grep freeform
多任务唤起：打开任意支持小窗的应用（如浏览器），全面屏手势上滑停顿进入多任务视图（Recents），点击或长按应用图标/卡片菜单，确认菜单中出现「小窗模式」选项。

交互手势测试：

拖拽：轻触小窗顶部胶囊条在屏幕内拖动，松手后位置保持固定。

缩放：长按小窗对角边缘拉伸，确认画面自适应缩放无残影。

最小化与吸附：将小窗快速拖拽至屏幕左/右边缘，验证是否能自动收缩为悬浮气泡挂起。

键盘避让：在小窗内点击输入框，验证小窗是否向上抬升避开键盘。

许可证说明
本项目严格遵循 Apache License 2.0 协议开源，修改内容均保留上游项目及 AOSP 原始版权信息。

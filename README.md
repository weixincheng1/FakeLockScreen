# 黑屏挂机助手（FakeLockScreen）

一个面向 Android 的前台服务工具：  
启动后提供可拖动悬浮球，长按悬浮球进入“背光黑屏模式”（关闭背光但系统继续渲染），适合长时间挂机与投屏场景。

---

## 1. 当前版本核心特性

- 可拖动圆形悬浮球（大小可调）
- 长按悬浮球进入黑屏（**仅关闭背光**，不走覆盖层）
- 黑屏状态下可通过“音量加 + 音量减”组合键退出
- 黑屏状态支持投屏工具（如 `qtscrcpy`）继续显示与操作
- 前台通知常驻，支持“解除黑屏 / 停止服务”

---

## 2. 技术原理（简要）

本项目当前黑屏方案不是“画黑色遮罩”，而是 Root 下直接控制系统亮度与背光节点：

- `settings put system screen_brightness 0`
- `cmd display set-brightness 0`
- `/sys/class/backlight/*/brightness`
- `/sys/class/backlight/*/bl_power`
- `/sys/class/graphics/fb*/blank`

并在黑屏期间循环强化，防止系统自动拉亮。

---

## 3. 环境要求

- Android 7.0+（`minSdk 24`）
- Root 权限（必须，且建议 `su` 设为“始终允许”）
- 已开启悬浮窗权限
- 已开启本应用无障碍服务（用于组合键退出黑屏）
- 构建环境：JDK 17、Android SDK（`compileSdk 34`）

---

## 4. 从源码构建

在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

输出 APK：

- `app/build/outputs/apk/debug/app-debug.apk`

安装 APK：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 5. 首次使用配置

### 5.1 授权悬浮窗

打开 App 后点击：`授权悬浮窗权限`。

### 5.2 开启无障碍服务

点击：`开启音量键无障碍`，在系统无障碍设置中开启本应用服务。

### 5.3 确认 Root

首次启动服务时会检测 Root；如果未授权，会提示“无法关闭背光黑屏”。

---

## 6. 使用流程

1. 打开 App，调整悬浮球大小（36dp ~ 96dp）
2. 点击 `启动悬浮服务`
3. 悬浮球会出现在屏幕上，可拖动到任意位置
4. 在任意界面长按悬浮球，进入背光黑屏
5. 退出黑屏：同时按下 `音量加 + 音量减`
6. 停止服务：主界面点 `停止服务` 或通知栏点 `停止`

---

## 7. 常见问题与排障

### 7.1 长按悬浮球后没有黑屏

检查项：

- Root 是否可用（`su -c id` 输出应包含 `uid=0`）
- Magisk/SuperSU 是否已给本应用永久授权
- 是否被省电策略杀后台（请加白名单）

### 7.2 黑屏后仍有可见亮度

可能原因：

- 机型/ROM 使用私有背光节点，当前通用节点未覆盖
- 厂商服务在拉高亮度

建议：

- 确认 Root 永久授权
- 在黑屏中保持服务运行（不要被系统省电中断）
- 若仍有亮度，提取设备节点定制（见 9.5）

### 7.3 音量加减同时按，退出成功率不高

建议手法：

- 两键间隔控制在 1 秒内
- 避免长按其中一个键再按另一个

### 7.4 退出后音量图标反复出现

当前版本已加释放保护。若仍偶发：

- 先松开两键，再等待 1 秒
- 再次进入/退出黑屏复位状态

### 7.5 需要做机型背光节点定制

请在设备上执行并保存输出：

```bash
su -c "ls -R /sys/class/backlight /sys/class/leds /sys/class/graphics | head -n 300"
```

再根据输出新增厂商私有节点写入逻辑（可在 `FloatingService.kt` 中扩展）。

---

## 8. 运行与安全注意事项

- 本项目会写入系统亮度与背光节点，属于高权限行为
- 异常关机/强杀可能导致亮度状态未及时恢复
- 已实现退出和停止时恢复逻辑，但仍建议在可控环境下使用
- 不同 ROM/内核兼容性差异较大，建议逐机验证

---
## 9. 快速命令清单

构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

安装：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

查看日志（可选）：

```powershell
adb logcat | findstr /i "lockscreen FloatingService VolumeKeyAccessibilityService"
```


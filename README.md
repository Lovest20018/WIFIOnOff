# VPN OnOff

自动根据 WiFi 连接状态切换 VPN 的 Android 应用。

- WiFi 断开 → 自动开启 VPN
- WiFi 连接 → 自动关闭 VPN

支持亮屏、后台、锁屏等所有场景下的自动切换。

## 前置条件

**1. 安装并配置好以下任一 VPN 客户端**（至少一个，APP 内可切换）：

| 客户端 | 项目地址 |
|--------|----------|
| Clash Meta for Android (CMFA) | https://github.com/MetaCubeX/ClashMetaForAndroid |
| Bettbox | https://github.com/appshubcc/Bettbox |
| FlClash | https://github.com/chen08209/FlClash |
| Surfboard | https://github.com/getsurfboard/surfboard |

> 所选客户端必须已完成订阅配置，确保手动启动 VPN 可正常使用。

**2. 安装并启动 [Shizuku](https://github.com/RikkaApps/Shizuku)** — 用于在锁屏等受限场景下控制 VPN 切换。

> **缺少 Shizuku 或上述任一客户端，VPN OnOff 将无法正常工作。**

## 权限设置

安装后打开 APP，界面会显示各项权限状态。请确保以下权限均已授予：

| 权限 | 说明 | 授予方式 |
|------|------|----------|
| **Shizuku 授权** | 核心功能，用于后台/锁屏控制 VPN | 点击 APP 内状态文字，弹窗授权 |
| **后台弹出界面** | MIUI/HyperOS 设备专属 | 点击 APP 内状态文字跳转设置 |
| **通知权限** | 前台服务通知 | 首次启动时弹窗授权 |
| **位置权限** | Android 8+ 读取 WiFi SSID 所需 | 首次启动时弹窗授权 |

## 使用方法

1. 确保所选 VPN 客户端已配置好并能正常使用
2. 确保 Shizuku 已启动且正在运行
3. 打开 VPN OnOff，从下拉框中选择要使用的 VPN 客户端
4. 确认所有权限状态为绿色
5. 点击「开始监听」
6. 完成！APP 会在后台自动根据 WiFi 状态切换 VPN

### 目标 WiFi 设置

- **默认**：任意 WiFi 连接即关闭 VPN，断开即开启
- **指定目标**：点击「设为目标」将当前连接的 WiFi 设为目标，仅连接该 WiFi 时关闭 VPN，连接其他 WiFi 或断开时保持开启
- **清除目标**：点击「清除」恢复默认（任意 WiFi 模式）

## 更新日志

### v1.1.0

- **WiFi SSID 自动读取**：页面自动显示当前连接的 WiFi 名称，无需手动输入；点击「设为目标」即可设定
- **修复 WiFi 检测**：增加 `WifiManager` fallback，解决部分设备显示「未知」的问题
- **三星 One UI 兼容**：VPN 关闭采用「验证+重试」机制，解决 `am force-stop` 在三星设备上返回成功但进程未退出的问题
- **GitHub Actions CI**：新增自动构建 workflow，推送即构建 debug APK

### v1.0.9

- 健壮性修复与多客户端文档

### v1.0.8

- 签名基础设施重构

### v1.0.7

- 新增 Bettbox、FlClash、Surfboard 客户端支持
- 添加正式 release 签名

### v1.0.6

- 使用 `am force-stop` 替代原有断开方式

### v1.0.5

- 修复 WiFi 检测与控制可靠性

## 注意事项

- 首次通过本 APP 启动 VPN 时，系统会弹出 VPN 连接确认对话框，点击允许即可，后续不会再弹出
- 切换 VPN 客户端后服务会自动重启以应用新选择
- 服务开启后支持开机自启，无需每次手动启动
- 建议在系统设置中将本 APP 加入电池优化白名单，避免被系统杀后台
- **三星设备**：若 VPN 关闭不生效，请检查 Shizuku 是否正常运行，并确保 APP 未被三星「深度休眠」限制

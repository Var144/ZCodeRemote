# ZCode Remote

Android WebView 壳应用：把桌面端 ZCode 的「移动端远程控制」页面装进手机。

## 功能

- 扫码 / 手动填入远控链接（URL 持久化），WebView 全屏加载
- **v4 登录适配**（app_version ≥ 3.16.1）：多窗口承接 OAuth 登录弹窗，z.ai / bigmodel.cn 域族跳转留在应用内，启用第三方 Cookie；✕ / 返回键 / 页面自关三种方式关闭登录窗口
- **暗色跟随**：`prefers-color-scheme` 随系统暗色/亮色切换（API 33+ 算法暗色，API 29–32 forceDark），页面「跟随系统」主题选项可用；应用窗口同为 DayNight
- 悬浮 FAB：单击操作菜单 / 双击隐藏 30 秒 / 拖动吸附边缘 / 可彻底关闭
- 跨域链接（登录域族之外）交系统浏览器；JS 对话框转原生

## 构建

JDK 17 + Android SDK（compileSdk 34），Gradle 8.11.1（wrapper 已配腾讯镜像）：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 版本

| 版本 | 要点 |
|---|---|
| 1.2 | 暗色跟随（WebView + 应用 DayNight）、死代码清理 |
| 1.1 | v4 登录适配（OAuth 弹窗、域族放行、第三方 Cookie） |
| 1.0 | 初始版本：扫码配置 + WebView 壳 + FAB |

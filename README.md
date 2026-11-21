# Simple Files · 简洁文件

轻量文件管理器，支持 Android 8.0 及以上。

- 浏览内部存储和 U 盘，返回目录时保留位置。
- 预览常见图片、PDF、文本；DOCX/XLSX/PPTX 只做文本预览，GIF 为静态预览。
- 文件浏览使用存储权限，无需 Root；弹出 U 盘使用系统权限或 Root。
- `Android/data`、`Android/obb` 通过 Root 只读访问。
- “权限”入口可验证 Root，暂不支持 ADB 激活或 Shizuku，没有广告和统计上报。

当前为 `0.1.0-alpha.2` 开发版本，仓库仅用于源码备份和追溯。

## 构建

JDK 17、Android SDK 35：

```sh
./gradlew assembleDebug
```

源码采用 PolyForm Noncommercial 1.0.0，仅授权非商业用途；商业用途需另行取得许可。Gradle Wrapper 保留原许可。

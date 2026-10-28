# 构建

项目不依赖手机导出的 framework-res，也不包含设备专属路径或签名密钥。

准备以下工具，并把路径传给 `build.ps1`：

- JDK（本次使用 Temurin 21，`javac`、`java`、`jarsigner` 在 PATH 中）。
- 官方 Android SDK Platform 36 的 `android.jar`。
- Xposed API 82 的 `api-82.jar`，仅供编译。
- Android 构建工具的 `aapt2` 和包含 `com.android.tools.r8.D8` 的 R8 JAR。
- 自己的签名密钥；更新已有安装时须使用原密钥。

```powershell
$env:XIAOBU_STORE_PASSWORD = '<密钥库密码>'
$env:XIAOBU_KEY_PASSWORD = '<私钥密码>'
./build.ps1 -AndroidJar /path/android.jar -XposedJar /path/api-82.jar `
  -R8Jar /path/r8.jar -Aapt2 /path/aapt2.exe `
  -Keystore /private/module.keystore -KeyAlias xiaobu
```

输出 `XiaobuScreenEnhancer-v2.1.3.apk`。编译临时目录在 `build/`，已被 Git 忽略。`compile-stubs/` 中的隐藏 Android 类型只参与 Java 编译，不打包进 DEX；Xposed API 同样不随 APK 分发。构建会检查各工具退出码并验证 APK 签名。

## 状态测试

```powershell
./test.ps1
```

这些 JVM 测试覆盖截图代次、过期结果、保存期间的生命周期、失败重试、重复点击、资源释放，以及手势取消、旧回调、单次启动和并发否决。系统接口、原生动画、应用分享接收与手势冲突仍需要在手机上验证。


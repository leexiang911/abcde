# 怎么把这个工程变成手机上能装的 APK

阶段一的完整工程，不依赖电脑上的 Android Studio 也能出包。

---

## 路线 A：GitHub Actions（推荐）

全程在手机上操作，三分钟出包。

1. 在 GitHub 建一个仓库，**选 Public**（公开仓库 Actions 免费无限时长；私有仓库每月 2000 分钟，也够用）。
2. 把这个目录整个传上去。手机上可以用 GitHub 官方 App，或者在网页版逐个 `Add file → Create new file` 粘贴——注意路径要连目录一起写，比如新建文件时文件名填 `app/src/main/java/com/sopcam/MainActivity.kt`，GitHub 会自动建目录。
3. 传完后 Actions 会自动跑。也可以进 **Actions → Build APK → Run workflow** 手动触发。
4. 跑完点进那次运行，页面底部 **Artifacts** 区有 `sopcam-debug-apk`，手机浏览器直接下载，解压装。

第一次装要在系统设置里允许「安装未知来源应用」。三星会额外弹一次「已阻止有害应用」，选「仍要安装」。

**关于 gradle-wrapper.jar**：仓库里没有这个二进制文件，所以 workflow 里直接装了 Gradle 本体（`gradle-version: '8.9'`）而不是走 `./gradlew`。等你哪天用 Android Studio 打开过一次、它自动生成了 wrapper，就可以把那行删掉，改成 `./gradlew assembleDebug`，构建会更快（有缓存）。

**想要 release 包**：debug 包已经自带调试签名，能直接装能直接用，日常迭代用它就行。真要出 release，需要生成 keystore 并配 GitHub Secrets，那是另一套流程，等功能稳定了再说。

---

## 路线 B：Termux 在 S25+ 上直接编译

能跑通，但不建议日常用——Compose 全量编译在手机上几分钟起步，还烫手。适合没网、临时改一行的场景。

```bash
# Termux 从 F-Droid 装，别用 Play 商店那个（已停更）
pkg update && pkg install openjdk-21 gradle wget unzip
pkg install tur-repo && pkg install aapt2   # aarch64 版的 aapt2，关键

# Android SDK command-line tools
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest

export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

**必须做的一步**：Gradle 默认从 Maven 拉 x86-64 版的 aapt2，在 ARM 上根本跑不起来。在 `~/.gradle/gradle.properties` 里加一行指向 Termux 的 aarch64 版本：

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

然后：

```bash
cd sopcam-app
gradle assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk /storage/emulated/0/Download/
```

`--no-daemon` 很重要，手机内存扛不住 Gradle 守护进程常驻。

---

## 路线 C：国内 CI（网络不好时）

GitHub 拉依赖慢的话，这几个国内平台都能跑同样的流程，Maven 走国内源快得多。工程的 `settings.gradle.kts` 里已经把 aliyun 镜像放在最前面了。

| 平台 | 说明 |
|---|---|
| Gitee + 流水线 | 代码托管和 CI 一体，国内速度最好 |
| 阿里云云效 Flow | 有免费额度，和 aliyun maven 同网 |
| 腾讯 CODING DevOps | 有免费额度，配置界面比较友好 |
| Codemagic | 境外，但有现成 Android 模板，点几下就出包 |

配置逻辑和 Actions 一样：装 JDK 17 → 跑 `gradle assembleDebug` → 收 `app/build/outputs/apk/debug/*.apk`。

---

## 装上以后先验这一条

阶段一唯一要验的东西：

1. 点「方向 锁横屏」，**竖着拿手机**拍一张
2. 点「方向 锁竖屏」，**横着拿手机**拍一张
3. 插数据线传到电脑，用 Windows 照片打开这两张

**水印必须在同一个角，文字水平可读，构图方向符合锁定设置。**

如果电脑上看水印跑到别的角去了，说明 EXIF 那步没生效——检查 `CapturePipeline.writeToMediaStore` 里写 `ORIENTATION_NORMAL` 的代码有没有执行到。这是整个方案的地基，不过这关别往下走。

---

## 工程结构

```
sopcam-app/
├── .github/workflows/build-apk.yml   自动打包
├── settings.gradle.kts               含 aliyun 镜像
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/sopcam/
        │   ├── MainActivity.kt        权限、装配、快门接线
        │   ├── watermark/Watermark.kt 方向模型 + EXIF 正立 + 水印烧录
        │   ├── capture/CapturePipeline.kt  CameraX + 落盘队列
        │   └── ui/CameraScreen.kt     界面
        └── res/values/
```

阶段二要加的 `Sop.kt`（Room + 命名规则）和电脑端的 `archive_photos.py` 在上一批文件里，等这个跑通了再并进来。

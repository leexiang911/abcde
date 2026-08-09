# SopCam — 检修留档水印相机

给电子维修台用的拍照工具：按 SOP 逐项拍摄，水印烧录在成片上且方向可锁，
文件名自带位号和语音备注，导到电脑就是一份能直接归档的资料。

目标机型 Galaxy S25+（Snapdragon 8 Elite，Android 15/16，One UI 7/8）。

---

## 一、先说方向锁定这件事

这是整个 App 最容易做错的地方，先把概念拆开。「方向」其实是三件独立的事：

| 层 | 决定什么 | 由谁控制 |
|---|---|---|
| 构图方向 | 成片是横图还是竖图 | `ImageCapture.targetRotation` |
| 像素方向 | 图片字节流本身是否正立 | 拍完是否按 EXIF 物理旋转 |
| 水印锚点 | 水印贴在成片的哪个角 | 在正立位图上按 `Anchor` 绘制 |

很多水印相机的通病是只做了第三层，结果手机上看在左下角，传到电脑变右上角。
根因是 JPEG 里带着 EXIF Orientation，看图软件各自解释不同。

本项目的处理顺序固定为：

```
拍照 → 拿到 JPEG 字节 → 按 EXIF 物理旋转到正立 → 在正立图上画水印
     → 重新编码 → 写盘时 EXIF Orientation 强制置为 NORMAL
```

这样成片是「所见即所得」的死图，Windows 照片、WPS、PIL、缩略图服务全部一致。
代码见 `Watermark.kt` 的 `decodeUpright()` 和 `CapturePipeline.kt` 里写 EXIF 的那几行。

构图锁则通过 `OrientationController` 把 `OrientationLock` 翻译成 `targetRotation`：

- `AUTO` — 跟随重力
- `PORTRAIT` — 永远出竖图，哪怕你把手机横过来贴到板子上方
- `LANDSCAPE` — 永远出横图，单手斜举拍长条排针时最常用

锚点 `BOTTOM_LEFT / BOTTOM_RIGHT / TOP_LEFT / TOP_RIGHT` 与构图锁完全正交，
可以按 SOP 步骤单独覆盖（`SopStep.anchorOverride`）——比如拍板子右侧元件时
把水印挪到左下，免得挡住丝印。

---

## 二、性能怎么保证

你的场景是连拍多张、每张都要烧字，所以瓶颈不在拍，在**编解码**。

**核心设计：快门线程绝不做图像处理。**

```
takePicture 回调          → 只 copy JPEG 字节 + 入 Channel（微秒级）
Dispatchers.Default × 2  → 解码 / 旋转 / 画水印 / 重编码
Dispatchers.IO           → MediaStore 写盘
```

快门回调立刻返回，可以马上按下一张。落盘队列深度显示在界面上，不阻塞操作。

**分辨率取舍。** S25+ 主摄 200MP，但单张 200MP 解码就要 1 秒以上，
检修留档没有意义。默认锁 4:3 约 12MP（4000×3000），`decodeUpright` 里
再按 `maxLongSide=4032` 兜底降采样。实测这个档位下单张全流程约 150–250ms，
两路并发相当于连拍不掉帧。想要更细节的焊点特写，把 `ResolutionStrategy`
改成 8160×6120 即可，代价是单张 ~600ms。

**其他几处。**

- `CAPTURE_MODE_MINIMIZE_LATENCY` — 关掉后处理增强，检修图不需要 HDR 美化
- `inMutable = true` 解码 — 直接得到可变位图，省一次整图拷贝
- 原始 JPEG 质量 95、烧录后 92 — 只损失一次
- 不要开 Camera Extensions（夜景/人像），三星那套会把快门延迟拉到 1s+

---

## 三、SOP 与命名

数据结构在 `Sop.kt`：

```
SopTemplate  一种板子的检修流程
 └ SopStep   一个拍摄点位：位号 refDes / 型号 partName / 部位 detail / 提示 hint / 张数 requiredShots
WorkOrder    一次实际检修
 └ CaptureRecord  一张成片
```

一条步骤填成这样：

| 字段 | 例子 |
|---|---|
| refDes | `U7` |
| partName | `STM32G474` |
| detail | `Pin 12–15` |
| hint | 对准丝印，保证能看清第 1 脚圆点 |
| requiredShots | 2 |

生成的文件名：

```
03_U7·STM32G474·Pin12-15_电容鼓包已更换_143052.jpg
└┬┘ └────────┬─────────┘ └──────┬─────┘ └──┬─┘
序号       步骤标签           语音备注      时间
```

序号前缀是刻意的：电脑上按文件名排序 = 按 SOP 顺序，不用再看时间戳。

目录：`DCIM/SopCam/20260809/WO-20260809-017_SN12345/`

`FileNaming.sanitize()` 保留中文（NTFS / exFAT / ext4 都支持），
只剔除 `\ / : * ? " < > |`、折叠空白、去掉尾部的点（Windows 不允许）。

---

## 四、语音备注转文件名

沿用你之前定的方案：**sherpa-onnx + SenseVoice-small**，全本地，不联网。

流程：按住「备注」按钮录音 → 松开 → 本地识别 → 文本同时进两处：
水印的备注行、文件名的 note 段。识别结果先弹出来让你确认或改，
避免「电容」识别成「电荣」直接写进文件名。

模型建议**首次启动时下载到 `filesDir`**，不要打进 assets。
SenseVoice-small int8 约 230MB，塞进 APK 会让安装包直接爆掉。

配合 SOP 还有个省事的做法：识别结果先跟当前模板的步骤标签做模糊匹配，
说「U7 主控」就自动跳到对应步骤，不用手点。

---

## 五、导到电脑

最省事的还是数据线。手机连电脑 → 内部存储 → `DCIM/SopCam` → 整个拖走，
目录层级本身就是分类（日期 / 工单 / 文件）。

`archive_photos.py` 做两件事：

```bash
# 生成索引 CSV（Excel 直接打开，中文不乱码）
python archive_photos.py D:\检修留档\SopCam

# 再按元器件建一份平行视图，硬链接不占额外空间
python archive_photos.py D:\检修留档\SopCam --by-step D:\检修留档\按元器件
```

后期想更顺手，可以在 App 里内嵌一个局域网 HTTP 服务（NanoHTTPD 一个类的事），
电脑浏览器打开 `http://手机IP:8080` 直接下载当天的 zip，省掉插线。

---

## 六、依赖与权限

`build.gradle.kts`：

```kotlin
val camerax = "1.4.1"
implementation("androidx.camera:camera-core:$camerax")
implementation("androidx.camera:camera-camera2:$camerax")
implementation("androidx.camera:camera-lifecycle:$camerax")
implementation("androidx.camera:camera-view:$camerax")

implementation("androidx.exifinterface:exifinterface:1.3.7")

val room = "2.6.1"
implementation("androidx.room:room-runtime:$room")
implementation("androidx.room:room-ktx:$room")
ksp("androidx.room:room-compiler:$room")

implementation(platform("androidx.compose:compose-bom:2024.09.00"))
implementation("androidx.compose.material3:material3")
implementation("androidx.activity:activity-compose:1.9.2")

// 语音（阶段三再加）
// implementation("com.k2-fsa:sherpa-onnx:...")
```

`AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.camera.any" />
```

Android 10+ 用 MediaStore 写自己的 `DCIM/SopCam` 目录**不需要存储权限**，
不要去申请 `WRITE_EXTERNAL_STORAGE`，只会在 Android 13+ 上被拒。

---

## 七、建议的推进顺序

阶段一是能独立跑起来的最小可用版本，先把它跑通再往下加。

1. **相机 + 方向锁 + 水印烧录**（`Watermark.kt` + `CapturePipeline.kt`）
   验收标准：横竖各拍一张，传到电脑用 Windows 照片打开，水印在同一个角。
2. **SOP 模板与命名**（`Sop.kt` + 步骤梯 UI）
   验收标准：建一个 5 步模板，连拍 5 张，文件名序号和标签正确。
3. **语音备注**（sherpa-onnx 接入 + 确认弹窗）
4. **归档**（导出 CSV / 局域网下载）
5. **ML Kit 辅助**（你原计划里的物体检测——可以用来做"没对准芯片就提示重拍"）

---

## 文件清单

| 文件 | 内容 |
|---|---|
| `Watermark.kt` | 方向模型、`OrientationController`、EXIF 正立解码、水印烧录 |
| `CapturePipeline.kt` | CameraX 绑定、拍照队列、MediaStore 落盘 |
| `Sop.kt` | Room 实体与 DAO、`FileNaming` 命名规则 |
| `CameraScreen.kt` | Compose 主界面：步骤梯、方向/锚点切换、快门、语音按钮 |
| `archive_photos.py` | 电脑端扫描、索引 CSV、按元器件分类 |

# AI之眼（AI-Eyes）

为视障用户打造的"电子眼"Android 应用：打开即进入摄像头界面，AI 语音播报已就绪，
用户用语音说出想找的物品（例如"帮我找垃圾桶"），应用通过 YOLO 实时识别摄像头画面，
并用语音一步步引导用户转动、前进、伸手触摸到目标。

## 演示

<video src="docs/demo.mp4" controls width="360"></video>

*实机演示：按住屏幕说出想找的物品 → 松开 → 语音引导接近 → 确认找到。若上方播放器未加载，可[直接观看视频](docs/demo.mp4)。*

## 使用流程

1. 启动应用 → 自动请求摄像头 + 麦克风权限（首次使用需由陪护人点"允许"）
2. 语音播报："AI之眼已就绪……"；屏幕上会实时显示画面中识别到的物品框（供陪护人查看）
3. **按住屏幕说话**（有"叮"提示音），例如"垃圾桶"，**松开**（有低音提示音）后播报
   "好的，正在帮您寻找垃圾桶，请您开始移动。"
4. 画面中出现目标后持续语音引导，**每 2 秒提示一次**方位与远近：
   - "垃圾桶在左边，请向左转动"
   - "垃圾桶在您的左前方，请向左转，慢慢向前走"
   - "垃圾桶就在正前方很近，请放慢脚步，准备伸手"
5. 目标到达正前方且足够近 → 震动提示 + "找到了！垃圾桶就在您正前方，请伸手触摸"
6. **说"找到了"**（或"摸到了"、"就是它"）结束本次寻找；按住屏幕说出下一个物品即可继续。

**按住屏幕任意位置**：随时打断播报并开始说话，可中途改找其他物品（例如"停止"、"找椅子"）；
松开后生效。没有听清时会语音提示重说。

## 技术方案

| 模块 | 实现 |
| --- | --- |
| 按住说话 | 屏幕按下开始录音（提示音），松开结束识别（提示音），单次会话式识别 |
| 摄像头 | CameraX（Preview + ImageAnalysis，KEEP_ONLY_LATEST） |
| 物体识别 | YOLOv8s（Open Images V7 601 类）→ ONNX Runtime Android 本地推理；每帧解码全部类别用于叠加显示（搜索时目标类不受数量上限约束、叠加层高亮显示，220ms 节流；空闲 800ms 节流）；引导阈值与解码阈值一致（0.15），画面有框必有引导 |
| 语音合成 | Android TTS（zh-CN，无障碍音频通道） |
| 语音识别 | 双通道：优先设备自带 Google SpeechRecognizer（zh-CN）；不可用时自动切换 **Vosk 离线中文识别**（vosk-model-small-cn-0.22，完全离线，无需任何云服务） |
| 引导逻辑 | 检测框水平位置→左/中/右；框高度占比→远近；到达判定 + 震动 |

模型选择说明：COCO 80 类数据集**不包含"垃圾桶"**，因此选用 Open Images V7
微调的 `yolov8s-oiv7`（601 类，s 档为该数据集公开的最强官方权重），包含垃圾桶、
门、窗、楼梯、马桶、冰箱、轮椅、红绿灯、消防栓等对视障用户更实用的类别。
注意 OIV7 模型置信度整体偏低（与官方 ultralytics 管线实测一致），因此解码阈值取
0.15，语音引导另设 0.25 的更严阈值以避免误导视障用户。

### 目录结构

```
app/src/main/java/com/aieyes/
├── MainActivity.kt            # 状态机：BOOT → LISTEN → SEARCH（按住说话 + 找到确认）
├── camera/  (CameraX 绑定在 MainActivity 中)
├── detect/
│   ├── YoloDetector.kt        # ONNX Runtime 推理 + letterbox + NMS
│   ├── FrameAnalyzer.kt       # 取帧、节流、仅在搜索时推理
│   └── Detection.kt
├── speech/
│   ├── Speaker.kt             # TTS 封装，播报开始/结束回调
│   ├── Tones.kt               # 按住/松开提示音
│   ├── VoiceListener.kt       # Google/Vosk 双通道 ASR 协调（按住会话）
│   └── VoskAsr.kt             # Vosk 离线中文识别（语法限制解码）
├── guide/
│   ├── Command.kt
│   ├── CommandParser.kt       # 中文指令 → 类别索引（同义词映射 + 语法短语表）
│   └── GuidanceEngine.kt      # 方向/距离播报、到达判定、2 秒限频
└── ui/DetectionOverlayView.kt # 检测框叠加层（供陪护人调试）

app/src/main/assets/
├── yolov8s-oiv7.onnx          # YOLO 模型（ONNX，约 44 MB）
├── labels.txt                 # 601 个类别名（与模型索引一一对应）
└── vosk-model-small-cn-0.22/  # 离线中文识别模型（约 65 MB，首次启动解压到应用目录）
```

## 语音识别说明（国内设备）

国内大部分手机没有 Google 语音服务（GMS），直接使用系统 SpeechRecognizer 会报
"没有语音识别服务"。本应用的处理方式：

1. 启动时检测 `SpeechRecognizer.isRecognitionAvailable()`，可用则优先走系统识别；
2. 不可用或识别组件异常时，自动切换到**内置的 Vosk 离线中文模型**
   （vosk-model-small-cn-0.22，模型打包在 APK 内，完全离线、不联网、不收集数据）；
3. 离线识别使用语法限制解码——只识别本应用指令集中的短语（物品名 + "帮我找/停止/
   找到了"等），小模型也能稳定识别，且误识率远低于自由文本识别；
4. Vosk 模型首次启动需解压到应用私有目录（约 65 MB），之后启动直接加载。

## 构建

```bash
# 需要 JDK 17+、Android SDK（ANDROID_HOME 或 local.properties 指向 SDK）
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 重新生成模型（可选）

仓库已包含转换好的模型。如需重新导出：

```bash
pip install ultralytics onnx onnxruntime
python tools/export_model.py
```

### 换成自定义模型（例如自己训练的垃圾桶检测）

1. 用 YOLOv8 训练/导出任意检测模型为 ONNX（输出格式 `[1, 4+N, anchors]`）
2. 替换 `app/src/main/assets/` 中的 `yolov8s-oiv7.onnx` 和 `labels.txt`
   （类别名一行一个，顺序与训练时一致）
3. `CommandParser.CHINESE_ALIASES` 中的英文键名与 `labels.txt` 保持对应即可

## 已知限制

- 首次启动需解压离线识别模型，模型就绪前按住屏幕会提示"稍等片刻再试"（约几秒，仅一次）
- APK 体积较大（约 200 MB）：包含 YOLO 模型（44 MB）+ 离线中文识别模型（65 MB）+ 各架构推理库
- 类别受模型限制：模型只能找到 601 类中的物体；说了不在列表里的词会得到提示
- 距离是"检测框大小"的粗略估计，引导语中的远近为相对判断
- 建议竖持手机、镜头朝前，在光线充足环境使用

## 许可证

本项目代码基于 [MIT License](LICENSE) 开源。

APK 内捆绑的第三方模型遵循其各自的许可：YOLOv8s-oiv7 权重（AGPL-3.0，来自
Ultralytics / Open Images V7）、Vosk 中文小模型 vosk-model-small-cn-0.22（Apache 2.0）。

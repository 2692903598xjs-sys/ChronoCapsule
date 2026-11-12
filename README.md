# ChronoCapsule

Android 端侧屏幕感知模块：**无障碍自动抓屏 → 端侧 OCR → 结构化 → 跨屏去重 → 按契约上传 → 建任务取卡片**。

整条流水线在本机完成，云端只接收结构化文本，不上传截图。

```text
聊天窗口切换
   │  AccessibilityService（无需每次授权，抓不到 FLAG_SECURE 页面）
   ▼
截图 Bitmap ──► PP-OCRv6 tiny（ONNX Runtime，det + rec）
                     │
                     ▼
              文字行 → 消息结构化（说话人 / 时间 / 左右气泡 / bbox）
                     │
                     ▼
              跨屏去重（会话 + 说话人 + 文本计数，历史落盘）
                     │
                     ▼
              POST /api/screens（契约 v0.4，失败落盘补传）
                     │  201 + screen_id
                     ▼
              POST /api/tasks（上传只是材料）
                     │  轮询 GET /api/tasks/{id}
                     ▼
              云端卡片（标题 / 摘要 / 字段）
```

## 特性

- **自动采集**：监听微信 / QQ 窗口切换，界面稳定 350ms 后抓一屏；1.5 秒最小间隔、每分钟 20 张、息屏锁屏跳过、上一张未处理完时只保留最新一屏。
- **端侧 OCR**：PP-OCRv6 tiny（det 1.7MB + rec 4.3MB），完全离线，模型随 APK 打包。
- **结构化**：区分单聊 / 群聊，识别时间分隔符、左右气泡、昵称，输出契约要求的字段。
- **跨屏去重**：按消息指纹计数，只上传新增消息；去重历史落盘，进程被杀后仍然有效。
- **离线补传**：网络不可达时整屏 JSON 落盘（最多 20 屏），恢复后按原 `event_id` 补传，服务端幂等。
- **自动出卡片**：上传成功后自动 `POST /api/tasks` 并轮询任务，拿到卡片直接显示在界面上。
- **可配置云端**：应用内填写地址、一键测试连通，无需重新打包。
- **可扩展**：新增聊天应用只需在 `CHAT_PACKAGES` 加一行。

## 环境要求

- Android 11+（`minSdk 30`，`targetSdk 37`）
- ABI：`arm64-v8a`（真机）/ `x86_64`（模拟器）

## 快速开始

```bash
# 构建（Android Studio 自带的 JDK 17）
JAVA_HOME="D:/Android/Android Studio/jbr" ./gradlew.bat assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 App → 「开启自动记录（无障碍）」→ 在系统无障碍设置里启用 ChronoCapsule
2. 填写云端地址（模拟器可先 `adb reverse tcp:8765 tcp:8765`，再填 `http://127.0.0.1:8765`）
3. 点「测试连接」确认 `HTTP 200`
4. 切到微信 / QQ 的聊天页，切一次会话就会自动采集并上传

Release 构建：`./gradlew.bat assembleRelease`；根目录有 `keystore.properties` 时自动签名，缺省回退 debug 签名。

## 测试

```bash
JAVA_HOME="D:/Android/Android Studio/jbr" ./gradlew.bat --offline testDebugUnitTest
```

20 个单元测试：跨屏去重（含落盘/恢复）13 个、离线队列 6 个、模板 1 个。

## 目录结构

```text
app/src/main/java/com/chronocapsule/
├── MainActivity.kt              手动上传、自动记录开关、云端地址
├── capture/ScreenWatcherService.kt   自动抓屏与调度
├── pipeline/ScreenPipeline.kt   一屏的完整处理流程
├── ocr/                         PP-OCRv6 tiny（ONNX Runtime）
├── struct/ScreenStructurer.kt   文字行 → 消息
├── contract/ScreenContract.kt   契约 v0.4 映射与自检
├── dedup/CrossScreenDeduper.kt  跨屏去重 + 历史落盘
└── net/                         上传、配置、离线队列
app/src/main/assets/models/      OCR 模型与字典
```

## 端云契约（v0.4）

`POST /api/screens`，顶层字段固定为 `event_id / session_id / app_package / app_name / page_title / captured_at / blocks[]`，服务端 `extra="forbid"`；单屏 ≤ 60 块、单块 ≤ 4000 字、整屏 ≤ 24000 字；`captured_at` / `message_at` 必须带时区；`bbox` 为 `[left, top, right, bottom]` 归一化到 0–1000。详细字段映射与错误码见 [交付说明.md](交付说明.md)。

## 已知限制

- 真机微信 / QQ 尚未实测（模拟器上用系统短信 App 验证了同一条代码路径）。
- 只在窗口切换时采集；停留在同一会话期间的新消息等下一次切换才记录。
- 只抓聊天页：微信的网页 / 图片查看器 / 公众号文章、QQ 的相册 / 空间 / 支付页会按类名跳过。
- 输入法弹出时的事件包名为输入法，不做兜底采集（会把键盘按键当聊天内容）。
- 跨零点消息的时间会被记到前一天。

## 文档

- [交付说明.md](交付说明.md)：安装、契约、自动记录行为、排障、已知限制（给协作方）

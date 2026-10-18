# CastView 安卓投屏（发射端 + 接收端）

同一个 APK 内含两种角色，界面风格参考 LetsView：首页两张入口卡片（接收端 / 发射端）、附近设备列表（mDNS 自动发现）、手动输入 IP。

- 仅投画面，不传声音
- MediaProjection 抓屏 → MediaCodec H.264 硬编（CBR 10Mbps，60fps，最长边 ≤1920）→ TCP(NODELAY) → 接收端硬解直出 Surface
- 低延时：KEY_LOW_LATENCY、关键帧请求、发送队列拥塞时丢弃旧帧并重发关键帧、解码后立即渲染
- 端口 53317；两台设备需在同一局域网

构建：`gradle assembleDebug`（需 Android SDK 34、JDK 17），APK 位于 `app/build/outputs/apk/debug/`。

使用：一台设备点“我是接收端”，另一台点“我是发射端”，选择设备后“开始投屏”。

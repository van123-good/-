# CastLink 安卓投屏（发射端 + 接收端）

一个 APK 同时包含 **发射端** 与 **接收端**，仅传输画面（不含声音），面向低延时、高清、稳定。

## 使用
1. 两台设备连接同一 Wi-Fi，安装同一个 APK。
2. 接收端设备：打开 App → 「接收端」，屏幕显示本机 IP 并等待连接。
3. 发射端设备：打开 App → 「发射端」，在列表中选择自动发现的接收端（或手动输入 IP），选择分辨率/帧率/码率 → 「开始投屏」并授权录屏。

## 实现要点
- 采集：`MediaProjection` + `VirtualDisplay` 直接输出到编码器 Surface。
- 编码：H.264 硬编码，CBR、无 B 帧、`KEY_LOW_LATENCY`、最高优先级，1 秒一个关键帧，静止画面自动重复帧。
- 传输：TCP（`TCP_NODELAY`），自定义轻量封包；发送队列有界，网络拥塞时丢帧并立即请求关键帧，避免延时累积。
- 解码：接收端 `MediaCodec` 低延时解码，直接渲染到 `SurfaceView`，不做缓冲。
- 发现：mDNS/NSD 自动发现，也可手动输入 IP（端口 53515）。

## 构建
```
./gradlew assembleDebug
```
APK 位于 `app/build/outputs/apk/debug/`。需要 JDK 17 与 Android SDK 34。

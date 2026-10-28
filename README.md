# rose-card

LSPosed 模块：把 Rose EarFeel-i5 接入 ColorOS 原生耳机卡片（控制中心设备卡 + 欢律 OneSpace 面板），降噪模式/强度可点。

- 作用域：`com.oplus.melody`（主进程写卡片、处理点击；`:fg` 进程给 OneSpace 面板喂数据）
- 卡片：用欢律内置 mydevices SDK `DeviceInfoManager.h(DeviceInfo)` 写入，宿主 `com.heytap.mydevices` 只信任欢律
- 点击：宿主/面板发 `com.oplus.melody.setgate` → hook `NoiseReductionCommand.onReceive` → BLE 写 Rose
- 面板：hook `com.oplus.melody.onespace.b.onViewCreated`，构造 `WhitelistConfigDTO`/`Ba.z`/`l9.b` 喂给三个观察者
- 模式映射：1 关闭→普通，2 通透，3 弱降噪→降噪+轻度，5 降噪→降噪+中度，4 强降噪→降噪+深度

构建：`./build.sh`（Windows 侧 SDK，无 Gradle；Xposed API 用 `stubs/` 桩类编译、不打包）。
混淆名绑定欢律 17.6.3，欢律更新后需重新核对。

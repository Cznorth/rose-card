# rose-card

LSPosed 模块：把 Rose EarFeel-i5 接入 ColorOS 原生耳机卡片（控制中心设备卡 + 欢律 OneSpace 面板），降噪模式/强度可点。

- 作用域：`com.oplus.melody`（主进程写卡片、处理点击；`:fg` 进程给 OneSpace 面板喂数据）
- 卡片：用欢律内置 mydevices SDK `DeviceInfoManager.h(DeviceInfo)` 写入，宿主 `com.heytap.mydevices` 只信任欢律
- 点击：宿主/面板发 `com.oplus.melody.setgate` → hook `NoiseReductionCommand.onReceive` → BLE 写 Rose
- 面板：hook `com.oplus.melody.onespace.b.onViewCreated`，构造 `WhitelistConfigDTO`/`Ba.z`/`l9.b` 喂给三个观察者
- 电量：BLE 帧 `01 01`（读回包与主动通知同格式），payload 7 字节 `?? ?? 左 右 ?? ?? 盒`，盒 `ff` = 未知（未开盒）。
  卡片只填 `batteryInfoList`（填 `mBatteryList` 宿主解析失败）；面板喂 `OneSpaceHeaderPreference$initObserver$4`（`P9.a`）
- 连接：面板底部「立即连接」由 `ContentFragment.z(fragment, 2)` 隐藏；主进程监听 ACL，断开时 `DeviceInfoManager.g` 删卡、连上时重读并插入；每 10 分钟轮询一次
- 「耳机设置」→ 显式组件启动 RoseLink（欢律无 `<queries>`；ColorOS 会弹关联启动确认）
- 「断开连接」→ 同欢律做法，HFP/A2DP profile 代理反射 `disconnect(device)`；失败则打开蓝牙设置
- 模式映射：1 关闭→普通，2 通透，3 弱降噪→降噪+轻度，5 降噪→降噪+中度，4 强降噪→降噪+深度

构建：`./build.sh`（Windows 侧 SDK，无 Gradle；Xposed API 用 `stubs/` 桩类编译、不打包）。
混淆名绑定欢律 17.6.3，欢律更新后需重新核对。

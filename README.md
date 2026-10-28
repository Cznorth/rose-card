# rose-card

LSPosed 模块：把 **Rose EarFeel i5（蓝牙名 ROSE EARFREE i5）** 接入 ColorOS 原生耳机组件——控制中心设备卡片 + 欢律 OneSpace 降噪面板，不用再打开 RoseLink 切降噪。

## 功能

- 控制中心出现 Rose 设备卡片，带左右耳电量环；耳机断开时卡片自动消失，连上后自动出现
- 卡片/面板里点降噪模式直接生效：普通、通透、降噪（轻度/中度/深度三档对应 ColorOS 的弱/中/强降噪）
- OneSpace 面板显示左右耳电量（充电仓电量需开盒时才有）
- 面板「耳机设置」跳转 RoseLink，「断开连接」真正断开耳机

## 环境要求

- ColorOS 17（Android 17，测试机 PLK110 `17.0.0.105`），已 root + LSPosed
- 欢律 `com.oplus.melody` **17.6.3**（混淆名绑定该版本，其他版本大概率失效）
- 耳机已在系统蓝牙中配对（按蓝牙名 `ROSE EARFREE` / `ROSE EARFEEL` 识别）

## 安装

1. 从 [Releases](../../releases) 下载 `rose-card.apk` 安装
2. LSPosed 中启用模块，作用域勾选 **欢律（com.oplus.melody）**
3. 强行停止欢律（或重启手机），连上耳机，约 5 秒后控制中心出现卡片
4. 首次点「耳机设置」时系统会询问是否允许欢律打开 RoseLink，选「30 天内允许」即可不再弹

## 已知问题

- 欢律更新后需要重新适配
- 只测试过 EarFeel i5 一款耳机

## 实现说明

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

## 免责声明

非官方项目，与 Rose（若水）、OPPO/一加无关。修改系统应用有风险，使用后果自负。

## License

MIT

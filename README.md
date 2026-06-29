# Running

一款类似 Nike Run Club 的 Android 跑步应用原型工程。

当前目标不是一次性做完整社区，而是先把最关键的 P0 能力跑通：

- 户外 GPS 跑步追踪
- 锁屏/后台状态下持续记录
- 前台服务常驻通知
- 真跑前权限与电池优化检查
- 跑步中实时诊断面板
- 跑后地图轨迹展示
- TTS 语音教练（开始、暂停、继续、结束、每公里播报）
- 跑步数据状态机
- GPS 点位过滤与距离/配速计算
- 本地历史、跑步详情、简洁轨迹预览
- 后续可接高德地图、语音播报和云同步

## 工程结构

```text
.
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/yiaha/running/
│           ├── MainActivity.kt
│           ├── core/model/
│           ├── core/tracker/
│           ├── data/
│           └── service/
└── docs/
    └── product-technical-plan.md
```

## 当前实现边界

这是第一版工程骨架，重点是把核心追踪架构写清楚并落到代码入口：

- `RunTrackingService`：前台服务入口，承载定位订阅、通知栏控制、模拟跑调试、未结束跑步恢复。
- `RunVoiceCoach`：封装 Android TTS，负责跑步控制提示和每公里分段播报。
- `AndroidGpsLocationProvider`：基于系统 GPS 的定位 Provider，后续可替换为高德定位实现。
- `RunSessionEngine`：跑步状态机，管理开始、暂停、恢复、结束。
- `LocationFilter`：GPS 点位准入与异常点过滤。
- `DistanceCalculator`：Haversine 距离计算。
- `PaceCalculator`：配速计算。
- `RunPoint` / `RunSessionSnapshot`：核心数据模型。
- `RunningDatabase` / `RunPointDao`：Room 本地点位持久化。
- `RunSessionDao`：跑步会话摘要持久化，用于历史记录列表与恢复判断。
- `MainActivity`：极简 Compose UI，覆盖权限/电池优化引导、开始、暂停、继续、结束、跑步诊断、恢复确认、独立历史记录页、详情页、高德原生地图轨迹和离线轨迹兜底。

## 真机调试

当前首页提供两个入口：

- `开始户外跑`：同时订阅系统可用的 `gps / fused / network / passive` provider；GPS 冷启动期间可先显示网络定位诊断，只有通过精度过滤的点才累计里程。
- `调试：模拟跑步`：每秒注入一个模拟点，走同一套过滤、距离计算、通知栏刷新和 Room 落库链路。

已在 `ATK-DLRK3568 - Android 13` 上验证：

- 应用可安装并启动。
- 首页可显示真跑前检查：定位权限、后台定位、电池优化放行。
- 小屏竖屏下权限引导按钮使用纵向全宽布局，不挤压。
- 前台服务可从 UI 正常启动。
- 该设备没有 `gps` provider，实际可用 provider 为 `fused` / `passive`。
- 定位实现已支持 provider 自动降级。
- 模拟跑可正常累计距离、刷新通知栏并写入 Room。

已在 `M9E1V2 - Android 9` GPS 真机上验证：

- 首页可显示真跑前检查：定位权限已就绪、电池优化待开启。
- UI 覆盖完整流程：开始户外跑、模拟跑步、暂停、继续、结束。
- 跑步中可显示实时诊断：最近定位时间、定位来源、精度、有效/过滤点位。
- 跑步中实时订阅当前会话有效点，在高德地图增量追加轨迹并以街道级缩放跟随当前位置；暂停时保留已绘制路线。
- 10 秒仍无 GPS 首点时会提示前往室外并提供定位设置入口；低精度网络点会显示过滤原因，不会虚增里程。
- 模拟跑完整闭环通过，结束后停留在摘要态，不会重新变成跑步中。
- 模拟跑暂停/恢复会延续原轨迹索引，恢复后的首点仅建立新基线，不累计暂停期间位移。
- 前台服务使用后台线程驱动 ticker 与模拟点注入，避免部分 Android 9 / MTK 定制系统上协程或主线程 Handler 调度不稳定。
- 真实户外跑可正确订阅系统 `gps` provider。
- 实测可用 provider：`passive`、`gps`、`network`。
- 跑后历史记录可显示最近一次跑步摘要：距离、时长、配速、点位数。
- 首页通过“历史记录”按钮进入独立列表页；详情返回历史页，历史页返回首页。
- 支持未结束跑步恢复确认：进程被杀后重新打开，会提示“发现未结束跑步”，可选择继续或结束保存。
- 前台服务被系统回收后支持从 Room 自动恢复最近一次 Running 会话；Paused 会话保持暂停。
- 已验证恢复结束保存后，记录进入历史列表。
- 支持跑步详情页：从历史记录进入，可查看摘要、轨迹点统计、简洁轨迹预览和分段信息。
- 已在详情页真机验证 60 个模拟点的轨迹预览入口与起终点说明。
- 支持跑后地图轨迹：详情页使用高德原生 3D 地图，展示双层轨迹线、起点、终点并自动缩放到完整路线。
- 地图代码已独立到 `ui/map/RouteMapPreview.kt`，系统 GPS 点位会先从 WGS-84 转换为 GCJ-02，避免轨迹与高德道路错位；离线 Canvas 轨迹继续作为兜底。
- GPS 过滤包含精度、时间戳、异常速度和精度感知的静止漂移门限；点位与会话摘要通过单线程队列有序写入 Room。

## 高德 Key 预留

工程已经预留高德 Android Key 注入入口。请在不会提交到 Git 的项目级 `local.properties` 中加入：

```properties
AMAP_API_KEY=你的高德AndroidKey
```

`local.properties` 已列入 `.gitignore`。CI 环境可通过 Gradle 属性 `AMAP_API_KEY` 注入，不要把真实 Key 写入受版本控制的文件。

该值会同时生成 `BuildConfig.AMAP_API_KEY` 并注入 Manifest 的 `com.amap.api.v2.apikey`。工程已接入高德 3D 地图 SDK；首次查看轨迹时会先进行 SDK 隐私授权，用户同意后才初始化地图。

常用调试命令：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug

& "D:\Software\AS\sdk\platform-tools\adb.exe" devices
& "D:\Software\AS\sdk\platform-tools\adb.exe" -s <serial> logcat -d -t 300 | Select-String -Pattern "RunTrackingService|RunLocationProvider|RunSessionEngine"
```

## 下一步

1. 做 5km / 10km 锁屏实测，继续调 GPS 过滤参数与后台保活提示。
2. 接入高德定位 Provider，与当前系统 GPS Provider 做真机精度和功耗对比。
3. 增加语音开关、播报频率和音量策略设置。
4. 增加跑鞋寿命、周/月报表等轻量数据分析入口。

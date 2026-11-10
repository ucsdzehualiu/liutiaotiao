# 刘跳跳（LiuTiaoTiao）

一个**干净、可审计、可测试、无网络权限**的 Android 自动跳过广告工具，思路参考「李跳跳」。

> 本项目为你自己从零编写的实现，**不包含**李跳跳的任何原始代码，也没有动态加载 / 注入 / 联网行为。
> 反病毒报“高风险”多数是因为第三方 App 申请了「无障碍 + 悬浮窗 + 网络」并被加壳混淆——本项目反其道而行：
> **无网络权限、无第三方 SDK、关闭混淆、规则数据驱动、核心逻辑有单元测试**，可直接逐行审计。
>
> **能做什么**：跳过控件树上带文字/ID 的开屏广告按钮（真机实测 QQ音乐/知乎/贴吧等均稳定命中）。
> **不能做什么**：视频/SurfaceView 自绘广告里的"跳过"不是控件，无障碍读不到，这类 App 目前无法跳过
> （详见第五节；OCR 兜底在 TODO 规划中）。

---

## 更新日志

### v1.11 —— 会话切换两步确认：通用机制替代硬编码包名（对照 GKD ForegroundTracker）

v1.10 用硬编码 `com.hihonor.hiboard` 修好了荣耀的会话劫持，但换个 ROM/换台手机
（或下一代 MagicOS 改个包名）同样的事故还会发生。通读 GKD 的 `ForegroundTracker`
后，把修法升级为**不依赖任何具体包名**的通用机制：

**核心思想：state 事件只是"线索"，不是"事实"；会话切换必须由真实窗口内容佐证。**

- **两步确认**（`SessionGate`，纯逻辑可单测）：非排除包的 `TYPE_WINDOW_STATE_CHANGED`
  只把候选包**挂起**（pending），不立即切换会话；每次扫描时用活动窗口根节点的
  真实包名做**证据**——证据支持候选 → 确认切换；证据支持当前会话 → **否决挂起**
  （迟到的桌面/浮层劫持事件被自然丢弃）；证据是第三个包 → 证据优先直接切换；
  挂起 1.2 秒仍无证据 → 超时自动确认（兜住"窗口读不到根节点"的 ROM，会话不卡死）。
- 任何 ROM、任何"会捣乱的系统包"、任何乱序事件都走这同一条路径；
  v1.10 的 hiboard 硬编码保留（排除列表多一层保险无害），但不再是正确性的依赖。
- v1.10 的"劫持后纠正"保留为防御纵深。
- 新增 `SessionGateTest`（10 条，覆盖劫持否决/正常切换/证据优先/超时兜底等）；
  全项目 100 条 JVM 单测全绿。

### v1.10 —— 修「偶发不跳」：荣耀桌面事件劫持扫描会话（真机日志实锤）

用户反馈"偶尔还是不稳定"，从识别记录看到弹出的应用是 `com.hihonor.hiboard`
而自己开的是 QQ音乐。回查 11-07 的真机日志，抓到完整事故链：

```
12:11:42.309 已尝试点击(第1次/手势): com.tencent.qqmusic|跳过 >
12:11:42.312 开始扫描会话 pkg=com.hihonor.hiboard   ← 点击后 3ms，会话被劫持
12:11:42.327 候选包名不符已丢弃: node=com.tencent.qqmusic session=com.hihonor.hiboard
```

**根因**：`com.hihonor.hiboard` 是**荣耀 MagicOS 的桌面**（HiBoard）。从桌面点
图标打开 App 的瞬间，桌面自己的残留 state 事件会到达——它不在排除列表里
（列表只有老包名 `com.hihonor.android.launcher`），动态解析桌面也解析不到它
（不带标准 CATEGORY_HOME resolve 结果）。于是它被当成"切换到了新 App"，
把正在验证点击效果的 QQ音乐 会话劫持走。复扫发现跳过按钮还在时，却因
"候选包名≠会话包名"被丢弃——**换招重试链路被废**。第一下点中就没事，
第一下没点中就再也没人管了 → 表现为"偶发不跳"。

- **排除荣耀桌面**（`ScanFilter`）：`com.hihonor.hiboard` 加入系统包前缀列表；
- **桌面兜底识别**（`AdSkipService.launcherPackage`）：动态解析失败时，检查
  本机是否装着已知国产桌面包名（hiboard/miui.home 等），让"回桌面清会话"
  判定对 MagicOS 生效；
- **会话劫持纠正**（防御纵深）：即使会话被任何浮层劫持，复扫发现"候选正是
  上一轮刚点过的目标（包名+关键词都对上）"时，把会话纠正回候选包名、继续
  走"验证→换招重试"，而不是丢弃。该场景写入持久化日志，方便复查；
- 新增 `hiboard` 排除与前缀不误伤的回归测试；全项目 90 条 JVM 单测全绿。

### v1.9 —— 根治「重启/重授权后失效」：绑定修复 + 持久化日志（对照 GKD 源码）

v1.8 修好了崩溃，但真机反馈「装好后第一次能用，重启或重新打开授权后就不跳了」。
通读 GKD（gkd-kit）源码后找到了我们缺的核心机制——GKD 的
`fixRestartAutomatorService` 里有段注释：**"无障碍出现故障，先移除再添加以触发
系统重启服务。必须等待一段时间，否则概率不会触发系统重启无障碍。"**

失效时的真实状态是：开关列表里**还有**本服务（系统设置显示"已开启"），但系统
**并没有真正绑定**服务——事件不送达，表现为"授权是开的、就是不跳"。旧代码的
`isRegistered` 只看"在不在列表里"，在就返回"一切正常"，**永远不做任何事**，
所以重新打开授权也没用。

- **绑定修复**（`AccessibilitySwitch.ensureBound` / `scheduleRebind`）：
  检测"开关在、服务没连上"这个坏状态，**先从列表摘除 → 等 1 秒 → 再写回**，
  强迫系统重新走绑定流程（GKD 同款）。修复有节流（`FixGate`，两次至少隔 10 秒），
  防止把绑定途中的服务又摘一次。
- **开机自检**（`BootReceiver`）：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 后
  延迟 5 秒做一次健康检查+修复——这是修复的最早时机，不用等用户打开 App。
- **持久化运行日志**（`ServiceLog`）：服务连接/解绑/销毁、每次点击、每次修复
  动作全部落盘（`filesDir/logs/service.log`，环形截断防膨胀），**跨重启保留**。
  识别记录页可查看+一键复制。v1.8 之前诊断全在内存里，进程一死现场就没了，
  "重启后坏了"永远查不到原因——这次补上了。
- 前台服务心跳、首页每秒刷新全部升级为 `ensureBound`（能修两种坏状态，
  不再只修"开关被摘"）。
- 首页状态新增"正在自动修复（摘除重绑）"的如实提示。
- 新增 `FixGateTest`（4 条）；全项目 88 条 JVM 单测全绿。

### v1.8 —— 根治「第一次都跳不了」：前台服务崩溃修复

v1.7 引入的 `ForegroundKeeperService` 在 Manifest 声明了
`foregroundServiceType="specialUse"` 却**漏声明 `FOREGROUND_SERVICE_SPECIAL_USE`
权限**。targetSdk 34 的真机上 `startForeground` 抛 `SecurityException` →
进程崩溃 → 系统把无障碍服务标记为 "Crashed services" 并**禁用**。死循环：
一连上就挂通知，一挂就崩，一崩就被禁用——所以 v1.7「第一次都跳不了」。

- Manifest 补上 `FOREGROUND_SERVICE_SPECIAL_USE` 权限；
- `startForeground` 包 `runCatching`：通知挂不上就放弃并 `stopSelf`，
  **绝不允许前台通知把无障碍服务进程带崩**；
- 前台服务加 30 秒自愈心跳（检测开关被摘并写回）。

### v1.7 —— 架构修正：扫描不再被会话门控（对齐李跳跳触发模型）

v1.4~v1.6 三轮修复都在"修门闩"，但门本身不该存在。李跳跳/GKD 的触发模型是
**「每个无障碍事件都扫一次（限频），防误点靠规则+几何+重试上限，不靠时间窗」**；
旧实现把所有扫描关在"会话必须正确开启"这扇门后——ROM 之间"哪个事件能开窗"
差异极大（有的恢复前台只发 content-changed、有的乱序、有的干脆不发），任何一条
开窗路径失效 = 整个服务失明。这就是"修了三轮还在坏"的真正原因。

- **事件即触发**（`ScanThrottlePolicy`，纯逻辑可单测）：任何非排除包的
  window-state / window-content 事件都触发扫描；会话/时间窗**只影响节奏**
  （窗内 80ms / 窗外 500ms 限频），永远不再决定"扫不扫"。`SplashPolicy.onContentChanged`
  的会话门控逻辑随之废弃（@Deprecated 保留）。
- **v1.6 的 gap 判定用错时钟，等于没修**：`event.eventTime` 基于 `uptimeMillis`，
  手机休眠期间不走——"放一会儿"通常伴随息屏，算出的间隔几乎为 0，后台回归检测
  恰好失效。该机制整体移除，由事件即触发取代。
- **过期会话有条件重开**：同包会话过期后，仅当"预算消耗过 + 距上次点击 >3s"才重开
  （拿回全新点击预算与密扫窗口）；信息流里的持续误命中因点击不断刷新 lastClickAt
  凑不满静默期，天然止血，不会反复重开预算乱点。
- **键盘弹出不扫**：打字界面不可能有开屏广告，还能防误点聊天文本；检查放在限频
  之后做（`windows` 是跨进程调用，事件风暴下每事件一查会打满主线程）。
- **被系统关掉后自动恢复**（`AccessibilitySwitch`，李跳跳同款）：adb 一次性授权
  `WRITE_SECURE_SETTINGS` 后，App 检测到无障碍开关被系统悄悄关掉时自动写回，
  无需用户手动重开。首页新增授权状态展示与"复制 adb 命令"按钮；未授权时
  自动退化为只检测，功能不受影响。
- 新增 `ScanThrottlePolicyTest`；全项目 80 条 JVM 单测全绿。

### v1.6 —— 修「最小化/关闭放一会儿再打开，广告弹出来却不跳」

v1.5 修好了会话内的问题，但"放一段时间再回来"这条路径仍有两个独立的坑，叠加起来表现为时好时坏：

- **后台回归不重开会话（主因）**：部分 ROM（尤其国产 ROM 的省电调度）把 App 从后台恢复到前台时**不发** `TYPE_WINDOW_STATE_CHANGED`、只发 `TYPE_WINDOW_CONTENT_CHANGED`。此时会话早已超时，旧逻辑对"过期 + 同包名"的 content 事件一律忽略 → 一次都不扫。现在 `SplashPolicy.onContentChanged` 增加**事件间隔判定**：距该包上一个 content 事件超过 5 秒且窗口已过期 → 判定为"回到前台"，重开扫描会话；连续到达的（信息流刷屏）继续忽略，不误开。服务侧按包名记录 content 事件时刻（跨会话保留、LRU 淘汰）。
- **服务进程被省电策略杀掉**：国产 ROM 会把"没有前台通知"的进程划入待杀名单，杀掉后系统开关仍亮着但事件不再送达——"放一会儿才坏、时好时坏"正是它的指纹。新增 `ForegroundKeeperService` 前台常驻通知（`IMPORTANCE_MIN`，不响不弹，带"停止服务"按钮），无障碍服务连上即挂、断开即撤；Android 13+ 在首页请求通知权限。通知消失 = 服务被杀，用户一眼可见。
- 前台服务的实现细节按系统契约处理：`startForegroundService` 后必先 `startForeground` 再停（否则 API 26+ 必崩）；`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` / `setForegroundServiceBehavior` 分别只在 API 34 / 29+ 调用。
- 新增 3 条 resume-gap 回归测试；全项目 86 条 JVM 单测全绿。

### v1.5 —— 修「第一条能跳，后面的就不能继续跳了」

v1.4 修好了"再进同一 App 不扫"的会话问题，但真机反馈仍集中在**连续多条广告只能跳第一条**。逐条排查后抓到三个叠加的根因，全部修复：

- **点掉后 2.5 秒就收工 → 二段广告失明（主因）**：v1.4 的扫描循环里有一条"点掉主广告后观察 2.5s 即彻底停扫"的退出分支，和它自己"放慢但不停、兜住两段式广告"的设计目标直接矛盾。两段式开屏的第二段广告恰恰在第一段跳过后才渲染，而它的出现通常只伴随 `TYPE_WINDOW_CONTENT_CHANGED`（窗口列表没变，不会有 `WINDOWS_CHANGED`），循环一停就再也没人扫它。现在节奏决策抽成 `ScanLoopPolicy`（纯逻辑可单测）：**已点掉只放慢（700ms）、不收工**，一直观察到 6 秒会话窗口结束。
- **重试预算不重置 → 第二段被 RetryPolicy 拒掉**：同一会话内目标按「包名|关键词」记尝试次数（上限 4 次）。第一段跳过后第二段若弹出**同一个按钮**，会沿用旧计数直接被拒。现在复扫确认目标消失时，自动清掉该目标的计数并归还总预算。
- **`onUnbind` 返回 false → `onRebind` 是死代码**：部分 ROM 解绑重绑后不回调 `onServiceConnected`，v1.4 指望 `onRebind` 恢复状态，但 `onUnbind` 返回了 `super`（false），系统**永远不会**回调 `onRebind`——服务"用着用着就掉线、关开一次又好"。现在返回 true，`onRebind` 真正生效。
- 新增 `ScanLoopPolicyTest`（6 条）回归测试；全项目 83 条 JVM 单测全绿。

### v1.4 —— 根治「时灵时不灵 / 第一次能跳、后面再进就不跳」

参考李跳跳 / GKD 一类实现的思路，把「开窗时机 + 扫描节奏 + 点击验证」重做了一版：

- **扫描会话状态机**（`SplashPolicy`，纯逻辑可单测）：回桌面即清空会话、换 App 即开会话、同一 App 新 Activity 在窗内继续。修掉了旧版「`windowPkg` 从不清理，再次进入同一 App 若只收到 content-changed 就被判定为同一个窗、而旧窗早已超时 → 一次都不扫」的头号 bug。
- **会话内轮询扫描**：开窗后按 130ms（前 1.5s）→ 380ms 的节奏反复扫，直到会话结束。跳过按钮无论早渲染晚渲染都会被打到，不再依赖某几个固定时刻。
- **点击后复扫验证 + 换招重试**（`RetryPolicy`，纯逻辑可单测）：不再把 `performAction` / `dispatchGesture` 的“已派发”当成“已成功”；同一目标若还在，就手势 ⇄ ACTION_CLICK 交替再试（每目标最多 4 次），确实消失才算成功。
- **定时任务全部可取消**：开会话先清掉上一个会话遗留的扫描，避免旧任务在新界面上乱扫。
- **点掉后短暂观察 2.5s** 兜住两段式广告的第二段，之后停扫省电，减少主界面误点。
- 新增 `SplashPolicyTest`（13 条）、`RetryPolicyTest`（3 条）回归测试，全部为 JVM 纯逻辑测试。

---

## 一、这类软件的设计思想与架构（为什么能跳广告）

核心机制本质上就是**无障碍服务 + 规则匹配 + 自动点击**：

1. **常驻无障碍服务（AccessibilityService）**：监听 `TYPE_WINDOW_STATE_CHANGED`（新窗口）与 `TYPE_WINDOW_CONTENT_CHANGED`（内容变化，如倒计时每秒刷新）。
2. **规则库**：一份按 App 包名索引的规则表，含目标按钮文字 / 控件 id / 正则。
3. **控件树遍历与匹配**：`rootInActiveWindow` 拿到控件树，递归读取每个节点的 `text` / `contentDescription` 与规则比对。
4. **执行点击**：命中且可点击 → `ACTION_CLICK`；不可点击（图片按钮）→ `dispatchGesture` 按坐标点击。
5. **开屏广告识别**：跳过按钮渲染有延迟，窗口出现后 1~2 秒内**多次错峰扫描**。

**为什么是无障碍而不是别的？** 无 root 前提下，一个 App 想“看到并点击”另一个 App 界面的按钮，安卓只开放无障碍这一条路。root+hook 更重更危险，Shizuku 门槛更高——**无障碍就是这一品类的最佳实践**。

---

## 二、本项目的工程化设计（最佳实践）

代码分层清晰、职责单一，核心匹配逻辑可脱机单元测试：

```
LiuTiaoTiao/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
└─ app/
   ├─ build.gradle.kts            // 关闭混淆；含 JUnit 测试依赖
   ├─ proguard-rules.pro
   └─ src/
      ├─ main/
      │  ├─ assets/rules.json     // ★ 数据驱动规则库（与代码分离，可直接改）
      │  ├─ AndroidManifest.xml
      │  ├─ java/com/liutiaotiao/
      │  │  ├─ MainActivity.kt     // 设置界面：状态/开关/自定义关键词
      │  │  ├─ AdSkipService.kt    // 无障碍服务：事件→遍历→匹配→点击
      │  │  ├─ RuleMatcher.kt      // ★ 纯逻辑：匹配与打分（无 Android 依赖，可单测）
      │  │  ├─ RuleRepository.kt   // 加载/解析 assets/rules.json，失败自动兜底
      │  │  └─ Prefs.kt            // 本地设置
      │  └─ res/…（layout / values / xml / drawable）
      └─ test/java/com/liutiaotiao/
         └─ RuleMatcherTest.kt     // ★ 9 个 JVM 单元测试
```

**用到的工程最佳实践：**

| 实践 | 体现 |
|---|---|
| 关注点分离 | 规则加载 / 纯匹配 / 无障碍交互 / 设置 各自独立 |
| 可测试性 | 匹配逻辑抽为纯 Kotlin，`RuleMatcherTest` 无需真机即可跑 |
| 故障兜底 | `rules.json` 解析失败自动回退内置 `FALLBACK` 规则，服务不崩 |
| 性能与省电 | 事件去抖、扫描最小间隔、节点数与深度上限、点击冷却 |
| 健壮点击 | 点击前 `refresh()`；`ACTION_CLICK` → 祖先点击 → 坐标手势三级兜底 |
| 多窗口覆盖 | 主窗口找不到时回退遍历其它窗口（浮层/弹窗广告） |
| 隐私安全 | 无 `INTERNET` 权限、无第三方 SDK、`allowBackup=false`、关闭混淆 |
| 可扩展 | 规则集中在 `assets/rules.json`，改完重装即可，无需改代码 |

---

## 三、编译运行

### 方式 A：Android Studio（推荐）
1. `Open` → 选择本 `LiuTiaoTiao` 文件夹；首次同步会联网下载 Gradle 依赖（**开发期**联网，App 本身无网络权限）。
2. 手机开 USB 调试，Run ▶。
3. 打开 App →「① 去开启无障碍服务」→ 系统设置开启「刘跳跳」。
4. 确认状态「✅ 无障碍服务已开启」；打开有开屏广告的 App 测试。

### 方式 B：命令行（更轻量，不需要 Android Studio）
```bash
# 需本机 JDK17 + Android SDK，并配置 ANDROID_HOME
cd LiuTiaoTiao
gradle wrapper --gradle-version 8.7      # 首次生成 wrapper
./gradlew test                           # 跑单元测试
./gradlew assembleDebug                  # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 四、规则现状与扩展方法

**如实说明当前规则现状**：`assets/rules.json` 目前只内置**全局默认规则**——
13 条文字关键词（跳过广告/点击跳过/关闭广告/Skip…，带权重打分）与 14 个控件
ID 片段（`splashskip`/`btnskip`/`adskip`…，用于无文字的纯图片按钮）。
**没有按包名的专属规则**（`packages` 为空）。

真机实测（荣耀 MagicOS / Android 17）验证过的 App：QQ音乐、知乎、百度贴吧
（文字、纯图片 ID、带倒计时三种按钮类型均命中）。其余 App 依赖默认规则覆盖，
**不保证每个都能跳**——遇到不识别的按下面方法补规则。

若某个 App 没被识别：

1. **首选**：先看 App 内「识别记录」页——它会列出那一屏实际采集到的控件
   （文字/ID/坐标/拒绝原因），直接告诉你属于哪种情况（见第五节）。
2. 在 App 内「自定义关键词」里追加该按钮的**确切文字**。
3. **进阶**：编辑 `app/src/main/assets/rules.json` 的 `packages`，按包名补充：
   ```json
   "packages": {
     "com.example.app": {
       "keywords": ["关闭", "我知道了"],
       "regexes": ["^\\d+$"],
       "ids": ["xxx_skip_btn"]
     }
   }
   ```
   改完重新安装即可（无网络下发，规则只随安装包更新）。
4. 纯图片按钮：把该图片的 `contentDescription` 或控件 ID 片段加进对应规则。

---

## 五、已知局限（如实说明）

**个别 App 无法跳过，这是当前架构的固有限制，不是 bug**：

- **视频/SurfaceView 自绘广告（最主要盲区）**：部分 App（常见于抖音系、
  部分游戏的广告 SDK）把整屏广告连同"跳过"按钮一起**画在 SurfaceView /
  TextureView / 视频图层**上——无障碍服务读到的控件树里**什么都没有**
  （识别记录会显示"遍历 0 个节点"）。文字关键词、控件 ID、正则全部失效，
  因为按钮根本不是控件，只是像素。这类 App 目前**无法跳过**。
- **无文字、无 contentDescription、无 ID 的图片按钮**：三样全没有时规则
  无从命中，需逐 App 补规则。
- **故意反自动化的广告 SDK**：按钮可点击属性为 false 且点击区域与控件
  错位、或点击后校验真实触摸轨迹的，手势点击也可能无效。
- **误触风险**：宽泛词（"关闭/X"）可能误关正常弹窗，默认规则刻意保守，
  换来的是部分 App 需手动补规则。
- **国产 ROM 限制**：MIUI/ColorOS/MagicOS 等需额外允许「自启动、后台常驻、
  锁定后台」，否则服务会被回收（v1.9 的绑定修复 + 开机自检能自动恢复大部分场景）。
- **商店合规**：无障碍类 App 基本无法上架国内主流商店，只能自签名侧载。
- **无网络 ⇒ 规则不会自动更新**，需自行维护 `rules.json`。

### TODO：OCR 兜底检测（规划中，未实现）

针对上面第一类盲区（视频自绘广告），计划引入**本地 OCR 兜底**：

- **思路**：无障碍服务自带的 `takeScreenshot`（API 30+）截屏 → 本地 OCR 引擎
  识别屏幕像素中的「跳过 / Skip / ×」文字及其坐标 → `dispatchGesture` 按坐标点击。
- **触发时机**：仅当控件树遍历**一无所获**（0 节点或无命中）且处于开屏会话
  窗内时才截图走 OCR——控件树能解决的绝不动用截图，控制耗电与延迟。
- **硬约束**：保持**无网络权限**不变，OCR 引擎必须打包进 APK 本地运行
  （候选：ML Kit 文字识别离线版 / Tesseract），模型体积与识别耗时是主要
  取舍点；识别结果同样走几何准入（只认屏幕上方小按钮），防止乱点。
- **风险**：截图 + OCR 的耗电显著高于控件树遍历；API 30 以下设备无
  `takeScreenshot`，该兜底不可用。

---

## 六、安全声明

- ✅ 无 `INTERNET` 权限：不联网、不上传、不下发规则、不热更新。
- ✅ 无第三方 SDK、无广告、无统计埋点。
- ✅ 关闭混淆：反编译产物即源码。
- ✅ 核心逻辑有单元测试，行为可验证。

个别杀软若仍误报，属于“无障碍类 App”的类别级启发式告警，不代表代码有问题；可用 `apksigner` 核对签名或提交误报。

# WaterWidget

WaterWidget 是一个面向慧生活798用户的第三方 Android 设备控制客户端，提供多账户管理、饮水设备启动、钱包充值、用水消费统计、桌面小部件、快捷设置磁贴与设备二维码添加功能。

> 非官方项目，与慧生活798服务提供方无关。请仅使用你本人有权访问的账户和设备，并遵守相关服务规则。

## 功能

- 饮水设备一键启动，接水结束后通知本次消费与估算水量
- 自动同步设备名称，支持设备别名、快捷移除和控制中心默认设备切换
- 短信登录与账户管理：设备登录用于同步和启动设备、钱包充值及 App 端任务；补充积分登录可完成支付宝端任务并获得更多积分
- 查看校园钱包余额，并通过支付宝完成充值
- 扫描二维码或手动添加设备编号
- 每日签到、积分任务与任务执行记录，通知栏实时显示任务进度与累计积分
- 本地优先的今日 / 本月 / 本年消费与预计饮水量统计
- 桌面小部件、设备启动快捷设置磁贴
- 浅色、深色和跟随系统的显示模式

## 运行环境

- Android 13（API 33）及以上
- 仅提供 `arm64-v8a` 安装包，适用于现代 64 位 Android 设备

## 构建

项目采用 Gradle Kotlin DSL、Gradle 9.5、JDK 21、Kotlin 2.4 和 Jetpack Compose。源码通过 Gradle `sourceSets` 直接使用仓库根目录的 `src/main` 和 `src/test`。

### 配置参数

项目中的 API 地址、签名盐值等信息通过 `secrets.properties` 注入，**不会提交到版本控制**。构建前需手动创建：

1. 复制项目根目录的 `secrets.properties.example` 为 `secrets.properties`
2. 将其中的占位值替换为实际值

```bash
cp secrets.properties.example secrets.properties
# 然后编辑 secrets.properties 填入实际的 API_GATEWAY / SIGN_SALT / API_CID
```

> **说明**：未配置 `secrets.properties` 时项目仍可正常编译，但构建出的 APK 因缺少必要参数无法连接服务端。

### 构建命令

```bash
# JVM 单元测试
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug

# 正式 Release（需设置签名环境变量）
./gradlew assembleRelease
```

APK 默认输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 常见问题

### 接水时提示「启动失败：请升级最新版APP」

**这是服务端的版本校验，不是本机的登录或设备问题。**

慧生活798 服务端会对每个请求读取 `VersionCode` 请求头做「最低版本」校验；官方 App 每次强制升级，
低于新版本号的客户端（包括旧版官方 App 自己）都会被拒绝，返回 `msg=请升级最新版APP`。
同类事件：2026-09-24（[#16](https://github.com/nocookies111/life-798/issues/16)）、2026-10-20（[#23](https://github.com/nocookies111/life-798/issues/23)）。

本项目对应做过两处调整：

1. **补齐两条链路的协议头。** 公开仓库原来的 `IlifeApi` 既不发送 `VersionCode`，两条链路又共用
   同一个 `User-Agent`，因此无论服务端是否强制升级，都会被视为「版本过旧」。
   现在按官方客户端实现：MAIN（`1,5`）与 APP（`1,1`）分别带自己的 `User-Agent`、`ApplicationType`、`VersionCode`，
   见 `IlifeApi.requestHeaders()` 与 `IlifeApiProtocolTest`。
2. **按官方 OkHelper 拦截器的规范补全协议。** 反编译官方 `com.cloudora.android` v3.1.9 的
   `com.ilife.lib.coremodel.http.base.OkHelper` 与 `DevControlHelper` 确认，官方客户端会强制：

   | 项 | 官方取值 | 说明 |
   |---|---|---|
   | 请求头 `VersionCode` | `3.1.9` | 用的是**版本名**，不是整数 versionCode（31009） |
   | `User-Agent` | `Android_ilife798_3.1.9` | 固定前缀 + 版本名 |
   | `/dev/start` 参数 | `…&rcp=false&cnt=1` | 多了 `cnt` 通道计数 |

   缺任一项，服务端都会回落到「请升级最新版APP」的旧客户端分支。
   现在客户端按此实现：`IlifeApi.appProfile()` 统一用版本名填 `VersionCode` 与 UA 后缀，
   `IlifeApi.deviceStartUrl()` 补 `cnt=1`，并由 `IlifeApiProtocolTest` 锁定。

   版本名运行时读取本机官方「慧生活798」（`com.cloudora.android`）的 `versionName`，
   官方再发版也无需重新打包；未安装官方 App 时用 `APP_VERSION_NAME`（默认 `3.1.9`）。

仍然失败时按顺序排查：

- 手机上的官方「慧生活798」是否已更新到应用内提示的最新版（应用商店可能滞后）——
  本应用会自动跟随它的 `versionName`；
- 没装官方 App（例如只用小程序）时，在 `secrets.properties` 里显式指定
  `APP_VERSION_NAME=3.1.9` 后重新构建；官方发新包后改成新版版本名即可（应用信息页可见）；
- 报错末尾的 `code=` 是服务端返回码，可据此判断是版本（`请升级最新版APP`）、
  签约（`-88`/`-87`）、欠费（`-52`）还是设备登录（`-21`）。

> 设备控制只能使用「设备登录（ApplicationType=1,1）」的凭据；用积分登录（`1,5`）请求设备接口，
> 服务端同样会给出与真实原因无关的提示，现在客户端会直接说明需要设备登录信息。

## 隐私与数据

应用的账户和设备配置保存在本机。请不要将设备控制登录信息、账户数据或二维码内容分享给他人。

## 致谢与第三方项目

- [miuix](https://github.com/compose-miuix-ui/miuix)：使用 `miuix-ui` 的下拉刷新组件和 `miuix-blur` 的模糊能力，并在应用内适配刷新布局与状态衔接；Apache-2.0 License。
- [QuickieExtended](https://github.com/T8RIN/QuickieExtended)：提供基于 CameraX 与 ML Kit 的二维码扫描能力；MIT License。

上述项目及其代码继续遵循各自的许可证，本项目的 MIT License 不替代其许可证。

## 免责声明

本项目按现状提供，不对可用性、准确性或使用结果作任何保证，使用风险由使用者自行承担。

## 许可证

本项目采用 [MIT License](LICENSE)。

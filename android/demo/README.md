# FAnt Android 后端 Demo

这是 FAnt 的手机/Android TV 示例 App。它既是 `ant-runtime.aar` 的集成示例，
也是一个可以编辑代码、管理 npm 依赖并启动本地 HTTP API 的轻量 IDE。

## Demo 展示的能力

- 编辑和保存 `server.ts`、`package.json`；
- 在停止状态下修改代码，重新启动后执行新版本；
- 添加、搜索、删除并安装 npm 依赖；
- 检查依赖是否使用未支持 Node API 或原生 `.node` 扩展；
- 分别选择项目目录和依赖缓存目录；
- 在目录设置中清理当前项目未使用的缓存，或确认后清空当前缓存目录；
- 直接使用公共存储绝对路径或 SAF `content://` tree URI；
- 在一个前台服务线程中持续调用 `AntRuntime.pump()`；
- 停止 HTTP 服务后再次点击“启动”；
- 在运行页面请求健康检查和格式化示例，并查看可滚动日志。

## 构建 APK

```bash
git clone https://github.com/fish2018/fant.git
cd fant

export ANT_ZIG_BIN="/path/to/zig-0.16.x/zig"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"

./android/build-demo.sh \
  --min-sdk 24
```

输出：

```text
android/demo/app/build/outputs/apk/debug/app-debug.apk
```

连接设备后安装：

```bash
adb install -r android/demo/app/build/outputs/apk/debug/app-debug.apk
```

构建并签名 release APK：

```bash
./android/build-demo.sh \
  --release \
  --abis arm64-v8a \
  --min-sdk 24
```

输出为：

```text
android/demo/app/build/outputs/apk/release/app-release.apk
```

Gradle 直接使用 signing config 输出最终签名 APK，脚本随后执行 `zipalign -c` 和
`apksigner verify`；不会生成或保留 `app-release-unsigned.apk`。如果没有提供生产
keystore，会在 `android/build/fant-demo-release.keystore` 创建本地 Demo keystore
（alias `fant-demo`，密码 `changeit`）。正式发布或覆盖升级必须通过
`ANT_DEMO_KEYSTORE` 等环境变量使用长期 keystore。需要 x86_64 模拟器或 32 位 ARM
真机时显式传 `--abis arm64-v8a,x86_64,armeabi-v7a`；每增加一个 ABI，APK 会增加
一份 native runtime。

只构建真机 arm64：

```bash
./android/build-demo.sh --abis arm64-v8a --min-sdk 24
```

完整工具链说明见 [../../BUILDING.md](../../BUILDING.md)。

## 页面结构

### 项目

项目主页面提供：

- 目录设置；
- 依赖管理；
- 重置示例；
- 保存；
- `server.ts`/`package.json` 文件切换；
- 代码编辑器。

“目录设置”和“依赖管理”分别打开独立页面，通过返回按钮回到项目主页面。

### 运行

运行页面包含：

- 启动、停止；
- 健康检查、格式化示例；
- HTTP 接口测试结果；
- 可滚动输出日志和“清空”操作。

## 项目目录和缓存目录

Demo 只保留两个可配置位置：

1. 项目目录；
2. 依赖缓存目录。

项目目录保存：

```text
server.ts
package.json
ant.lockb
node_modules/
```

缓存目录独立保存依赖下载缓存，可以被多个项目共享。`node_modules` 必须位于
项目目录中，不能设置成另一个任意目录，否则模块解析和锁文件语义会失去一致性。

### 公共存储绝对路径

Android 11 及以上首次启动时，Demo 会主动引导用户授予“所有文件访问权限”。
授权后默认使用：

```text
/storage/emulated/0/FAnt/project
/storage/emulated/0/FAnt/cache
```

这种模式在 Java API 中是 `StorageLocation.FILE_PATH`，走真实文件系统。Demo
不会在权限不足时静默切换到 App 私有目录。

### SAF 目录

如果用户不授予所有文件访问权限，目录选择器会返回 SAF tree URI，例如：

```text
content://com.android.externalstorage.documents/tree/...
```

Demo 持久化读写授权，并把 URI 作为 `StorageLocation.SAF_TREE` 直接交给 FAnt。
依赖安装、缓存、代码编辑和入口执行都使用同一个 Storage Bridge，不生成私有
运行副本。权限被撤销后会明确报错并要求重新选择目录。

SAF 不等同于普通路径，不提供完整 POSIX fd、符号链接、硬链接、`mmap`、
`flock` 或文件系统 watch 语义。

### 缓存清理

删除依赖时，Demo 会物理删除项目 `node_modules` 中不再属于当前依赖图的包，
但不会自动删除共享缓存。目录设置提供两个显式操作：

- `清理未使用缓存`：读取当前项目的 `ant.lockb`，删除缓存中未被当前项目引用的
  包，同时保留 registry 元数据；选择了共享缓存时，其他项目未引用的包也会被删除。
- `清空当前缓存`：二次确认后删除当前缓存目录中的所有包、索引和元数据，但保留
  缓存根目录以及项目源码和 `node_modules`。

两项操作都支持 `FILE_PATH` 和 `SAF_TREE`。SAF 模式通过 Storage Bridge 逐项调用
`DocumentsProvider` 删除，完成后会显示删除数量；撤销授权或 Provider 拒绝删除时会
显示明确错误。

## 示例 HTTP API

内置 `server.ts` 使用 `node:http` 和 npm 包 `lodash`，监听所有网络接口的
`8787` 端口：

```text
GET /api/health
GET /api/format?text=Hello%20FAnt
```

健康检查示例：

```json
{
  "ok": true,
  "runtime": "FAnt (Ant core) 版本号",
  "language": "JavaScript + erasable TypeScript",
  "dependency": "lodash@4.17.21"
}
```

格式化接口会调用已安装的 lodash：

```json
{
  "input": "Hello FAnt Android TV",
  "kebabCase": "hello-fant-android-tv",
  "chunks": [["Hello", "FAnt"], ["Android", "TV"]]
}
```

默认 `package.json`：

```json
{
  "private": true,
  "type": "module",
  "dependencies": {
    "lodash": "4.17.21"
  }
}
```

## 启动流程

点击“启动”时，Demo 会：

1. 保存编辑器中的 `server.ts` 和 `package.json`；
2. 检查项目目录和缓存目录权限；
3. 安装 `package.json` 声明的依赖；
4. 检查已安装依赖的兼容性；
5. 通过 `evaluateFile(project, "server.ts")` 执行真实入口文件；
6. 调用入口暴露的 start hook；
7. 持续 `pump()` 并在就绪后允许接口测试。

停止只关闭当前 JS HTTP Server，不销毁进程级 runtime，因此可以再次启动。
代码和依赖变更只能在停止状态下进行。

## 依赖管理

依赖管理页可以搜索 npm registry、查看包名称和说明、选中后添加并立即安装，
也可以选择已有依赖并删除。底层安装 API 示例：

```java
AntRuntime.InstallOptions options = new AntRuntime.InstallOptions();
options.cacheLocation = cacheLocation;
options.registryUrl = "https://registry.npmjs.org";

runtime.install(projectLocation, options);
```

npm 解析、下载、校验、解压、缓存、锁文件和 `node_modules` 写入默认启用。
包控制的生命周期 shell 脚本默认关闭。

依赖检查器会区分：

- 纯 JavaScript；
- 已支持的 Node API；
- 未支持的 Node API；
- 原生 `.node` addon；
- 无法静态确定的动态加载。

`.node` 文件是面向特定 CPU、Android/系统和 Node ABI 的原生二进制，不能像
普通 JavaScript 包一样直接跨平台运行。

## 重置示例

选择新的项目目录时，如果其中缺少 `server.ts` 或 `package.json`，Demo 会把
内置示例释放到该目录；已有文件不会被覆盖。“重置示例”会在用户确认后同时
恢复内置的两个文件。

## 后台运行说明

启动成功后，Demo 使用 `dataSync` 类型前台服务、常驻通知、
`PARTIAL_WAKE_LOCK`、Wi-Fi lock 和健康检查维持运行。停止时立即释放锁。

这可以提高 Activity 进入后台后的可靠性，但 Android 不保证任何 App 在用户
强制停止、严重内存压力、过热、网络断开或 OEM 特殊限制下永远存活。通用 API
服务不应冒用 `mediaPlayback`；该类型只适合真正播放音视频的宿主 App。

## 重要限制

- 一个进程只能有一个 `AntRuntime`；
- runtime API 必须在创建它的同一线程调用；
- 当前支持 `arm64-v8a`、`x86_64` 和 `armeabi-v7a`；32 位 ARM 使用解释器，
  不启用 Silver MIR JIT；
- TypeScript 只支持可擦除类型语法，不执行类型检查，也不支持必须转换代码的
  TypeScript 特性；
- FAnt 不是完整 Node.js，使用未实现 API 的程序需要适配；
- runtime 不是安全沙箱，脚本拥有宿主授予的文件和网络权限。

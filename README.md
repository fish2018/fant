# FAnt

FAnt（Fish Ant）是基于 Ant JavaScript runtime 的 Android 二开移植版。
重点是把 JavaScript/TypeScript 运行时、npm 依赖安装和文件存储桥接能力集成到 Android 项目。

## 项目定位

FAnt 不是 Node.js 的 Android 打包版，也不是必须使用特殊 JavaScript 语法的脚本解释器。它是一个独立的 JavaScript runtime，并提供一部分 Node 兼容 API。能否直接运行某个 npm 包，取决于该包使用的 API 和原生能力：

- 纯 JavaScript 包通常可以直接安装和运行；
- 已实现的 `node:*` API 可以使用；
- 未实现的 Node API、动态加载、依赖原生 `.node` 扩展的包会被依赖检查器标记；
- npm 的 `install`/`postinstall` 生命周期脚本默认不执行，必须由宿主显式信任后执行。

Android 运行时支持 `FILE_PATH` 和 `SAF_TREE` 两种存储位置。项目目录和缓存目录可以使用 Android 公共存储绝对路径，也可以直接使用 SAF 的 `content://` tree URI。SAF URI 不会被伪装成 POSIX 路径，也不会被静默复制到 App 私有目录。

## 当前产物

下面是本仓库在 2026-08-25 的一次 Android `arm64-v8a` release 构建结果。不同编译器、ABI 和构建变体的大小会变化，构建脚本会输出你本次构建的精确字节数。

| 产物 | ABI/变体 | 大小 |
| --- | --- | ---: |
| `ant-runtime.aar` | `arm64-v8a` | 3.52 MiB |
| [app-release.apk](android/demo/app/build/outputs/apk/release/app-release.apk) | `arm64-v8a`，minSdk 24 | 9.06 MiB |

## 构建

Android 是 FAnt 的主要构建目标。先安装 [BUILDING.md](BUILDING.md) 中的工具链，然后执行：

```bash
git clone https://github.com/fish2018/fant.git
cd fant

export ANT_ZIG_BIN="/path/to/zig-0.16.x/zig"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
export ANDROID_NDK_ROOT="/path/to/android-sdk/ndk/29.0.14206865"

./android/build-demo.sh --min-sdk 24
```

成功后：

- AAR：`android/build/ant-runtime.aar`
- debug APK：`android/demo/app/build/outputs/apk/debug/app-debug.apk`

也可以使用统一脚本：

```bash
./scripts/build-all.sh --android-only \
  --sdk "$ANDROID_SDK_ROOT" \
  --ndk "$ANDROID_NDK_ROOT" \
  --abis arm64-v8a
```

`--release` 会生成并验证已签名的 release APK：

```bash
./scripts/build-all.sh --android-only --release \
  --sdk "$ANDROID_SDK_ROOT" --ndk "$ANDROID_NDK_ROOT" \
  --abis arm64-v8a
```

输出为 `android/demo/app/build/outputs/apk/release/app-release.apk`。Gradle 会直接
生成已签名 APK，脚本随后执行 `zipalign` 和 `apksigner verify` 校验；不会留下
unsigned release APK。未提供生产 keystore 时，
脚本会在 `android/build/fant-demo-release.keystore` 创建 Demo 专用 keystore
（alias `fant-demo`，密码 `changeit`），仅用于本地安装和测试；正式发布或
覆盖升级必须通过 `ANT_DEMO_KEYSTORE`、`ANT_DEMO_KEY_ALIAS`、
`ANT_DEMO_STORE_PASSWORD`、`ANT_DEMO_KEY_PASSWORD` 传入长期 keystore。

仓库提供 [Android AAR and Demo](.github/workflows/android-build.yml) workflow：PR 和
主分支推送默认构建 arm64 debug；手动运行可选择 ABI、minSdk 和 release；`v*` tag
会构建已签名 release。没有配置正式 keystore 时，workflow 使用一次性的 Demo keystore
保证产物仍可安装；配置 `FANT_RELEASE_*` secrets 后才使用可升级发布的长期签名。
Action 上传 AAR、APK、各 ABI `.so` 和 `SHA256SUMS`，详细步骤见
[BUILDING.md](BUILDING.md#10-github-actions)。

Android runtime 支持 `arm64-v8a`、`x86_64` 和 `armeabi-v7a`；其中 32 位 ARM
使用解释器模式，不启用 Silver MIR JIT。桌面 `ant` CLI 仍可选构建，但不是 FAnt
Android 移植的必需产物：

```bash
./scripts/build-ant.sh --build-dir build
```

桌面构建需要 Clang 18+ 或 GCC 14+，详细说明见 [BUILDING.md](BUILDING.md)。

## Demo

Demo 工程位于 [android/demo](android/demo)，同时适配手机和 Android TV。它包含：

- 可编辑的 `server.ts` 和 `package.json`；
- 依赖添加、删除、安装和兼容性检查；
- `FILE_PATH`/`SAF_TREE` 项目目录与缓存目录选择；
- 前台服务、停止后再次启动和日志输出；
- `node:http` HTTP API 示例。

启动 Demo 后，默认示例服务监听 `8787` 端口：

```text
GET /api/health
GET /api/format?text=Hello%20FAnt
```

完整操作和 APK 构建说明见 [android/demo/README.md](android/demo/README.md)。

## 在 Android App 中集成 AAR

将 `android/build/ant-runtime.aar` 放入宿主工程的 `libs/`，并声明：

```gradle
repositories {
    flatDir { dirs("libs") }
}

dependencies {
    implementation(name: "ant-runtime", ext: "aar")
}
```

Java 代码可以在一个长期存活的工作线程或前台服务中创建 runtime：

```java
AntRuntime runtime = new AntRuntime(context);
StorageLocation project = StorageLocation.safTree(projectTreeUri);
StorageLocation cache = StorageLocation.safTree(cacheTreeUri);

AntRuntime.InstallOptions options = new AntRuntime.InstallOptions();
options.cacheLocation = cache;
runtime.install(project, options);
runtime.evaluateFile(project, "server.ts");

// 持续推进定时器、Promise、网络和流事件。
while (runtime.isOpen()) {
    runtime.pump();
}
```

`AntRuntime` 及同一个 isolate 的所有调用必须在同一线程完成。宿主应在生命周期结束时调用 `close()`，不要为每次 Start/Stop 重建 isolate。

## 存储位置

### `FILE_PATH`

`StorageLocation.filePath("/storage/emulated/0/FAnt/project")` 使用真实文件系统路径。Android 11 及以上要访问公共存储绝对路径，宿主需要引导用户授予“所有文件访问权限”（`MANAGE_EXTERNAL_STORAGE`）。获得权限后，运行时可以使用普通文件描述符、流和 libuv 文件能力；但 Android 厂商的 FUSE 层可能使 watch 事件延迟。

### `SAF_TREE`

`StorageLocation.safTree("content://...")` 直接使用用户通过系统文件夹选择器授权的 tree URI。宿主必须保存 URI 权限，并在权限被撤销时提示用户重新授权。SAF 模式支持创建目录、枚举、读写、stat、删除、重命名、临时文件和原子更新的等效实现，但不伪造 POSIX fd、符号链接、硬链接、mmap、flock 或可靠的文件系统 watch。

两种模式都要求项目目录内包含：

```text
package.json
ant.lockb
入口文件（例如 server.ts）
node_modules/
```

缓存目录独立于项目目录，可以由多个项目共用。

## 文档索引

- [BUILDING.md](BUILDING.md)：从 clone 到 AAR/APK 的完整构建流程和故障排查。
- [android/README.md](android/README.md)：AAR API、存储桥和宿主集成说明。
- [android/demo/README.md](android/demo/README.md)：手机/电视 Demo 使用和示例 API。
- [CONTRIBUTING.md](CONTRIBUTING.md)：面向 FAnt 二开的贡献规范。
- [docs/exec-plans/completed/android-embedding.md](docs/exec-plans/completed/android-embedding.md)：Android 移植实现记录和已知限制。

## 许可证

FAnt 继承 Ant 的 MIT 许可证。第三方依赖仍按各自许可证发布，集成和分发时请同时检查对应许可证文件。

## 上游来源与同步基线

FAnt 的 Android 移植直接基于以下 Ant 上游版本：

- 上游仓库：<https://github.com/theMackabu/ant>
- 上游分支：`master`（建立移植时）
- 基线提交：[`4091d86bc1fc9825eea4080ed71685689d81a2c6`](https://github.com/theMackabu/ant/commit/4091d86bc1fc9825eea4080ed71685689d81a2c6)
- 提交标题：`update score.json and pgo`
- GitHub 页面日期：`2026-08-19`（中国时区，页面分组为 “Commits on Aug 19, 2026”）
- Git 原始提交时间：`2026-08-18 21:36:33 -07:00`
- 中国时区时间：`2026-08-19 12:36:33 +08:00`
- FAnt 首个 Android 移植提交：`0f9fc5dbea0fe3b93632fb6b7ae90ba76e8a9db7`

Git 历史确认 `4091d86b` 是 `0f9fc5db` 的直接父提交。后续合并上游前请先阅读
[UPSTREAM.md](UPSTREAM.md)，以该基线区分“上游新增改动”和 FAnt 的 Android
移植改动，重点检查 runtime 全局状态、模块加载、`fs`、包管理器和构建系统的
冲突。

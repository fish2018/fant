# FAnt Android 移植

这里是 FAnt 面向手机、Android TV 和模拟器的 Android embedding。它把 Ant
runtime、npm 依赖管理、`FILE_PATH`/`SAF_TREE` 存储桥接和 Java API 打包成
`ant-runtime.aar`，宿主 App 不需要在设备上安装 Node.js 或 npm。

FAnt 的 Android Java/C API 仍使用 `org.antjs.runtime`、`AntRuntime` 和
`ant-runtime.aar` 名称。这些是稳定的集成接口，不代表项目品牌仍叫 Ant。

## 能力范围

- 执行 JavaScript 和可擦除 TypeScript；
- 使用 `evaluateFile()` 按真实入口文件解析相对路径和 `node_modules`；
- 从 `package.json` 安装 npm 依赖，生成 `ant.lockb`，读写下载缓存；
- 使用 `StorageLocation.FILE_PATH` 访问真实绝对路径；
- 使用 `StorageLocation.SAF_TREE` 直接访问 Android `content://` tree URI；
- 在同一个长期存活的线程中通过 `pump()` 推进 Promise、定时器、网络和流；
- 扫描依赖并标记 Node API、动态加载、原生扩展和生命周期脚本风险。

当前支持 `arm64-v8a`、`x86_64` 和 `armeabi-v7a` Android ABI。最低 API 为 24。
`armeabi-v7a` 保留 64 位 NaN-box 值格式，使用 32 位安全的 IC 元数据；Silver
MIR JIT 在该 ABI 上关闭，运行时使用解释器。

## 构建前置条件

- Android SDK Platform 37（Demo 的 compileSdk）；
- Android Build Tools 37.0.0；
- Android NDK 25+，推荐已验证的 29.0.14206865；
- JDK 17+；
- Zig 0.16.x；
- Python 3、Meson、Ninja、CMake、`pkg-config`、Node.js 22+、npm、Git、`rsync`；
- 首次构建可以访问 Gradle、Maven、npm 和 Meson 依赖源。

工具链和从 clone 到 APK 的命令见 [../BUILDING.md](../BUILDING.md)。

## 构建 AAR 和 Demo

```bash
git clone https://github.com/fish2018/fant.git
cd fant

export ANT_ZIG_BIN="/path/to/zig-0.16.x/zig"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"

./android/build-demo.sh \
  --min-sdk 24
```

产物：

```text
android/build/ant-runtime.aar
android/demo/app/build/outputs/apk/debug/app-debug.apk
```

只构建 AAR：

```bash
./android/build.sh \
  --ndk "$ANDROID_NDK_ROOT" \
  --abis arm64-v8a \
  --min-sdk 24
```

也可以传入 `--sdk`、`--ndk`、`--build-dir`、`--abis`、`--min-sdk`，使用
`--release` 构建已签名 release APK。默认只构建 `arm64-v8a`，需要其他设备或模拟器
ABI 时显式传 `--abis arm64-v8a,x86_64,armeabi-v7a`。Gradle 直接使用 signing config 生成最终
APK，脚本不会保留 `app-release-unsigned.apk`，最后只做对齐和签名校验。输出为：

```text
android/demo/app/build/outputs/apk/release/app-release.apk
```

没有提供 keystore 时，脚本会在所选构建目录生成本地 Demo keystore：

```text
android/build/fant-demo-release.keystore
```

alias 为 `fant-demo`，密码为 `changeit`。这只适合本地安装和测试；正式发布
或需要覆盖升级时，设置 `ANT_DEMO_KEYSTORE`、`ANT_DEMO_KEY_ALIAS`、
`ANT_DEMO_STORE_PASSWORD`、`ANT_DEMO_KEY_PASSWORD` 使用长期生产 keystore。

仓库的 [Android AAR and Demo workflow](../.github/workflows/android-build.yml) 会在
pull request、主分支推送和手动运行时构建并上传 AAR、APK、`.so` 与校验文件。
`v*` tag 或手动 release 在配置 `FANT_RELEASE_KEYSTORE_BASE64`、
`FANT_RELEASE_KEY_ALIAS`、`FANT_RELEASE_STORE_PASSWORD` 和
`FANT_RELEASE_KEY_PASSWORD` 后使用长期签名；未配置时使用一次性的 Demo keystore，
仍然直接生成已签名 APK，不会生成 unsigned APK。正式发布或覆盖升级必须配置长期密钥。

## 集成 AAR

将 AAR 复制到宿主工程的 `libs/`，然后声明：

```gradle
repositories {
    flatDir { dirs("libs") }
}

dependencies {
    implementation(name: "ant-runtime", ext: "aar")
}
```

宿主 Manifest 至少需要：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

如果使用前台服务，还需要按 Android 版本声明 `FOREGROUND_SERVICE`、对应的
服务类型权限和通知权限。FAnt runtime 本身不强制宿主使用前台服务。

## Runtime 生命周期

一个 Android 进程只能创建一个 `AntRuntime`。创建、调用和关闭必须在同一个
长期存活的 worker/service 线程完成：

```java
AntRuntime runtime = new AntRuntime(context);

// 在同一线程中反复调用，直到宿主停止服务。
while (runtime.isOpen()) {
    runtime.pump();
    SystemClock.sleep(10);
}

runtime.close();
```

不要为每次 Start/Stop 创建新的 runtime。Demo 会保留 isolate 和线程，停止
HTTP 服务后可以再次启动；只有整个 App 进程结束时才关闭 runtime。

执行入口文件：

```java
runtime.evaluateFile(project, "server.ts");
```

对 `FILE_PATH` 项目也可以传绝对路径：

```java
runtime.evaluateFile("/storage/emulated/0/FAnt/project/server.ts");
```

对 SAF 项目必须使用带 `StorageLocation` 的重载，不能把 `content://` URI
拼接成伪 POSIX 路径。

## 存储位置

### FILE_PATH：真实绝对路径

```java
StorageLocation project = StorageLocation.filePath(
        "/storage/emulated/0/FAnt/project");
StorageLocation cache = StorageLocation.filePath(
        "/storage/emulated/0/FAnt/cache");

AntRuntime.InstallOptions options = new AntRuntime.InstallOptions();
options.cacheLocation = cache;
runtime.install(project, options);
```

Android 11 及以上使用公共存储绝对路径时，宿主通常需要引导用户授予
`MANAGE_EXTERNAL_STORAGE`。获得权限后，FILE_PATH 走普通 native 文件系统，
可以使用 fd/FileHandle、流和 libuv 文件能力。公共存储经过 FUSE 或厂商
文件系统时，watch 事件仍可能延迟，不能把它当作严格实时通知。

FAnt 不会在权限不足时偷偷改用 App 私有目录。宿主应显示错误，让用户重新
授予权限或改用 SAF。

### SAF_TREE：Android 文件夹授权

```java
StorageLocation project = StorageLocation.safTree(projectTreeUri);
StorageLocation cache = StorageLocation.safTree(cacheTreeUri);

AntRuntime.InstallOptions options = new AntRuntime.InstallOptions();
options.cacheLocation = cache;
runtime.install(project, options);
runtime.evaluateFile(project, "server.ts");
```

`projectTreeUri` 和 `cacheTreeUri` 必须是通过系统文件夹选择器获得并持久化
读写权限的 `content://` URI。撤销权限后，Storage Bridge 会返回明确错误，
宿主必须重新发起授权。

SAF Bridge 直接实现以下操作：创建目录、枚举、读写、stat、删除、重命名、
临时文件、截断以及等效的锁/原子更新。SAF 模式不依赖：

- 符号链接或硬链接；
- `mmap`；
- POSIX `flock`；
- 把目录重命名当作一定瞬时完成；
- 把 URI 转换为虚假的普通路径。

因此 SAF 安装采用复制模式，并使用 SAF 可用的缓存索引和更新策略。项目的
`package.json`、`ant.lockb`、入口文件和 `node_modules` 都留在用户选择的
项目 tree 中，缓存 tree 可以被多个项目共享。

## npm 依赖安装

安装 `package.json` 中的依赖：

```java
AntRuntime.InstallOptions options = new AntRuntime.InstallOptions();
options.registryUrl = "https://registry.npmjs.org";
options.cacheLocation = cache;
options.maxConnections = 6;

AntRuntime.InstallResult result = runtime.install(project, options);
```

也可以追加包规格：

```java
runtime.install(project, options, "lodash@4.17.21", "nanoid@^5");
```

默认执行：解析、下载、完整性校验、解压、缓存、锁文件生成和
`node_modules` 安装。默认不执行包的 `install`/`postinstall` shell 脚本；
需要执行时必须由宿主明确设置 `runLifecycleScripts` 或调用
`runPostinstall()`。SAF_TREE 项目不支持执行需要本地 shell 的生命周期脚本。

安装过程需要宿主声明网络权限。缓存目录可以独立于项目目录，并由多个项目
复用；项目目录不能只读。

## 依赖兼容性

```java
AntRuntime.CompatibilityReport report =
        runtime.inspectDependencies(project);

if (report.hasBlockingDependencies()) {
    // NATIVE_ADDON、UNSUPPORTED_NODE_API、UNKNOWN_DYNAMIC_REQUIRE
    // 需要换包、补实现或由宿主拒绝安装。
}
```

检查器只做静态、保守判断，不执行依赖代码。分类含义：

| 分类 | 含义 |
| --- | --- |
| `PORTABLE_JS` | 主要是纯 JavaScript，可直接尝试运行 |
| `SUPPORTED_NODE_API` | 使用了 FAnt 已实现的 Node 兼容 API |
| `UNSUPPORTED_NODE_API` | 使用了当前 runtime 未实现的 Node API |
| `NATIVE_ADDON` | 声明或包含 `.node`/原生构建相关内容 |
| `UNKNOWN_DYNAMIC_REQUIRE` | 动态加载无法静态确定目标 |

普通 npm 依赖一般是 JavaScript 文件和 JSON，不需要 Node 原生 ABI；Node API
依赖会要求 FAnt 提供相应兼容模块；`.node` 扩展则是针对特定 CPU、系统和
Node ABI 编译的原生代码，不能因为包名能下载就认为可以运行。

## 前台服务与后台运行

Demo 用前台服务承载长期运行的 API，并在运行期间维护 `pump()`、健康检查、
部分唤醒锁和 Wi-Fi 锁。停止时释放锁并移除通知。这个机制提高了切后台后的
存活概率，但不能保证在用户强制停止、极端内存/温度压力、网络断开或厂商
策略下永不被杀。

通用 API 服务使用 `dataSync` 类型；只有真正的音视频播放服务才应使用
`mediaPlayback`。FAnt 不会为了“保活”伪装成音乐播放器。

## 当前验证结果

当前已验证：

- `arm64-v8a`、`x86_64` 和 `armeabi-v7a` native 构建；
- 多 ABI `ant-runtime.aar` 打包；
- Demo debug APK 和已签名 release APK 构建；
- Java API、依赖检查器和脚本静态检查。

当前没有连接 Android 真机/电视，因此 npm 在线下载、特定 SAF Provider、
公共存储授权、后台策略和真实 HTTP 请求仍需在目标设备上回归。

## 产物大小

这是一次 `arm64-v8a` release 构建的实际大小，不是固定承诺：

| 产物 | 大小 |
| --- | ---: |
| `ant-runtime.aar` | 3,695,214 字节 |
| `libant_android.so`（arm64-v8a） | 8,775,128 字节 |
| Demo signed release APK | 9,491,906 字节 |

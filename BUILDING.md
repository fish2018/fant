# FAnt 构建指南

本文档以 FAnt 的主要目标——Android 手机/电视运行时——为主。完成下面步骤后，可以从一个全新 clone 构建：

- Android runtime AAR；
- 手机/Android TV Demo debug APK；
- 已签名的 Demo release APK（默认使用本地 Demo keystore）；
- 可选的桌面 `ant` CLI。

仓库地址：<https://github.com/fish2018/fant>

## 1. 支持范围

| 目标 | 支持状态 | 说明 |
| --- | --- | --- |
| Android `arm64-v8a` | 主要目标 | 手机和电视真机 |
| Android `x86_64` | 支持 | 模拟器、部分 x86 设备 |
| Android `armeabi-v7a` | 不支持 | 当前 runtime 依赖 64 位指针布局 |
| Android minSdk | 24 | AAR 的最低 API |
| Android Demo compileSdk | 37 | 需要安装对应 SDK Platform |
| Linux/macOS/Windows CLI | 可选 | 继承上游能力，不是 Android 集成必需项 |

## 2. 必需工具

### Android 构建

- Git；
- Python 3；
- Meson 和 Ninja；
- CMake；
- `pkg-config`；
- Node.js 22+ 和 npm；
- Zig 0.16.x；
- JDK 17+；
- Android SDK Platform 37；
- Android Build Tools 37.0.0；
- Android NDK 25+，当前验证版本为 `29.0.14206865`；
- `rsync`；
- 首次下载 Meson、npm 和 Gradle 依赖时可访问网络。

### 桌面 CLI 构建

除上述非 Android 工具外，还需要支持 FAnt 所用 C23 特性的编译器：

- Clang 18+；或
- GCC 14+。

旧版 Apple Clang 不能完成当前桌面构建。macOS 建议安装 Homebrew LLVM。

## 3. 安装工具链

### macOS

```bash
xcode-select --install
brew install meson ninja cmake pkg-config llvm node rsync
```

安装 JDK 17，例如：

```bash
brew install --cask temurin@17
```

从 <https://ziglang.org/download/> 下载 Zig 0.16.x，并记录 `zig` 可执行文件的绝对路径。

### Ubuntu/Debian

发行版包名可能不同，至少需要：

```bash
sudo apt-get update
sudo apt-get install -y \
  git python3 python3-pip ninja-build cmake pkg-config \
  nodejs npm openjdk-17-jdk rsync
python3 -m pip install --user meson
```

如果还要构建桌面 CLI，请另外安装 GCC 14+/G++ 14+ 或 Clang 18+。Android-only 构建主要使用 NDK 自带的 Clang。

### Android SDK/NDK

先安装 Android command-line tools，然后执行：

```bash
export ANDROID_SDK_ROOT="/path/to/android-sdk"

"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-37.0" \
  "build-tools;37.0.0" \
  "platform-tools" \
  "ndk;29.0.14206865"

export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"
```

接受许可证：

```bash
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses
```

## 4. Clone 仓库

```bash
git clone https://github.com/fish2018/fant.git
cd fant
```

以下命令都必须在仓库根目录执行。

## 5. 构建 AAR 和 Demo APK

设置工具链路径：

```bash
export ANT_ZIG_BIN="/path/to/zig-0.16.x/zig"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"
```

默认构建手机/电视使用的 `arm64-v8a` AAR 和 debug APK：

```bash
./android/build-demo.sh \
  --min-sdk 24
```

构建成功后会生成：

```text
android/build/ant-runtime.aar
android/demo/app/build/outputs/apk/debug/app-debug.apk
```

脚本会在结束时打印两个文件的精确字节数。

### 构建双 ABI

需要同时支持真机和 x86_64 模拟器时显式指定两个 ABI。双 ABI 会把两份约 9 MB
的 native runtime 打进 APK，因此产物接近 19 MB：

```bash
./android/build-demo.sh \
  --abis arm64-v8a,x86_64 \
  --min-sdk 24
```

### 构建 release APK

```bash
./android/build-demo.sh \
  --abis arm64-v8a \
  --min-sdk 24 \
  --release
```

输出：

```text
android/demo/app/build/outputs/apk/release/app-release.apk
```

`--release` 会把 keystore 交给 Gradle `signingConfig`，由 Gradle 直接生成已对齐、
已签名的 `app-release.apk`，不会生成或保留 `app-release-unsigned.apk`。脚本随后
使用 `zipalign -c` 和 `apksigner verify` 做强制校验。
默认会在 `android/build/` 生成：

```text
android/build/fant-demo-release.keystore
```

默认 Demo keystore 的 alias 是 `fant-demo`，密码是 `changeit`，只用于本地
安装、联调和验收。正式发布或后续覆盖升级时，必须使用你自己的长期 keystore：

```bash
export ANT_DEMO_KEYSTORE="/secure/path/fant-release.keystore"
export ANT_DEMO_KEY_ALIAS="fant-release"
export ANT_DEMO_STORE_PASSWORD="<keystore-password>"
export ANT_DEMO_KEY_PASSWORD="<key-password>"

./android/build-demo.sh --release --abis arm64-v8a --min-sdk 24
```

也可以使用 `--keystore PATH` 和 `--key-alias NAME`。密码只通过环境变量传入，
不要把 keystore 或密码提交到 GitHub。脚本会拒绝没有签名的 release 输出。

### 使用统一脚本

只构建 Android：

```bash
./scripts/build-all.sh --android-only \
  --sdk "$ANDROID_SDK_ROOT" \
  --ndk "$ANDROID_NDK_ROOT" \
  --abis arm64-v8a \
  --min-sdk 24
```

同时构建桌面 CLI 和 Android：

```bash
./scripts/build-all.sh \
  --sdk "$ANDROID_SDK_ROOT" \
  --ndk "$ANDROID_NDK_ROOT" \
  --abis arm64-v8a
```

## 6. 只构建 Android AAR

如果宿主 App 不需要 Demo，可以直接执行：

```bash
./android/build.sh \
  --ndk "$ANDROID_NDK_ROOT" \
  --abis arm64-v8a \
  --min-sdk 24
```

`android/build.sh` 仍然需要 `ANDROID_SDK_ROOT`，因为编译 Java API 和打包 AAR 时需要 `android.jar`。

## 7. 自定义构建目录

默认生成目录是 `android/build`。可以使用独立目录：

```bash
./android/build-demo.sh \
  --sdk "$ANDROID_SDK_ROOT" \
  --ndk "$ANDROID_NDK_ROOT" \
  --build-dir /absolute/path/to/fant-android-build \
  --abis arm64-v8a
```

Gradle wrapper、依赖和 Zig 缓存会放到所选构建目录下面。Demo 会自动引用同一次构建生成的 AAR，不需要手动复制。

建议使用不包含空格的构建路径。

## 8. 构建桌面 CLI（可选）

FAnt 的 Android AAR 不依赖先生成桌面 CLI。只有需要在开发机运行 `ant` 命令时才执行本节。

macOS：

```bash
export ANT_ZIG_BIN="/path/to/zig-0.16.x/zig"
export CC="$(brew --prefix llvm)/bin/clang"
export CXX="$(brew --prefix llvm)/bin/clang++"
./scripts/build-ant.sh
```

Linux GCC 示例：

```bash
export ANT_ZIG_BIN="/path/to/zig-0.16.x/zig"
export CC=gcc-14
export CXX=g++-14
./scripts/build-ant.sh
```

输出：

```text
build/ant
```

脚本默认关闭 Temporal、PGO、CPU 本机调优和 LTO，以提高首次 clone 构建的可重复性。需要 Temporal 时添加 `--temporal`。

## 9. 验证产物

检查 AAR 中的 ABI：

```bash
unzip -l android/build/ant-runtime.aar | grep libant_android.so
```

双 ABI 构建应包含：

```text
jni/arm64-v8a/libant_android.so
jni/x86_64/libant_android.so
```

检查 APK：

```bash
unzip -l android/demo/app/build/outputs/apk/debug/app-debug.apk \
  | grep libant_android.so
```

连接设备后安装：

```bash
adb install -r android/demo/app/build/outputs/apk/debug/app-debug.apk
```

当前仓库已经完成 AAR/APK 的静态构建验证。npm 在线安装、SAF Provider 差异、公共存储权限、前台服务和 HTTP API 仍应在目标手机/电视上做一次真机回归。

## 10. GitHub Actions

仓库内置 [`.github/workflows/android-build.yml`](.github/workflows/android-build.yml)，
固定使用 JDK 17、Node.js 22、Zig 0.16.0、SDK Platform 37、Build Tools 37.0.0
和 NDK 29.0.14206865，并缓存 Gradle 与 Meson 下载内容。

- pull request、`main`/`master` 推送：构建 `arm64-v8a` debug；
- 手动运行：可选择 `debug`/`release`、ABI 列表和 minSdk；
- `v*` tag：构建已签名 release；配置长期密钥时使用长期签名，否则使用一次性的
  Demo keystore；
- 产物：`ant-runtime.aar`、APK、各 ABI 的 `libant_android.so` 和
  `SHA256SUMS`，保留 30 天。

Action 只上传 workflow artifact，并在 tag 构建时创建 GitHub Release。普通 debug 构建
不需要 secrets。没有 secrets 时 release 仍会生成可安装的 Demo 签名包；要让后续
版本覆盖升级，需在仓库的 Settings → Secrets and variables →
Actions 中配置：

```text
FANT_RELEASE_KEYSTORE_BASE64
FANT_RELEASE_KEY_ALIAS
FANT_RELEASE_STORE_PASSWORD
FANT_RELEASE_KEY_PASSWORD
```

`FANT_RELEASE_KEY_PASSWORD` 可以与 store password 相同。为了兼容已有 Android
仓库，workflow 也接受 `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEY_ALIAS`、
`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_PASSWORD`。

macOS 生成 keystore Base64：

```bash
base64 < /secure/path/fant-release.keystore | tr -d '\n'
```

Linux：

```bash
base64 -w 0 /secure/path/fant-release.keystore
```

将输出完整保存为 `FANT_RELEASE_KEYSTORE_BASE64`。keystore 本身及明文密码不能
提交到仓库。缺少 secrets 不会生成 unsigned APK，而是使用一次性的 Demo keystore；
正式发布请务必配置完整的 `FANT_RELEASE_*` secrets。

## 11. 常见问题

### `Set ANDROID_SDK_ROOT or pass --sdk`

没有设置 SDK 路径：

```bash
export ANDROID_SDK_ROOT="/absolute/path/to/android-sdk"
```

### `An Android platform android.jar is required`

未安装 Android Platform，或 SDK 路径错误。确认存在：

```text
$ANDROID_SDK_ROOT/platforms/android-37.0/android.jar
```

也可以通过 `ANT_ANDROID_JAR` 显式指定一个 `android.jar`。

### `Zig 0.16.x is required`

FAnt 当前构建文件面向 Zig 0.16.x：

```bash
export ANT_ZIG_BIN="/absolute/path/to/zig"
"$ANT_ZIG_BIN" version
```

### Gradle 下载失败

首次构建需要下载 Gradle 和 Android Gradle Plugin。检查网络、代理和 Maven/Google 仓库访问。默认 Gradle 用户目录位于 `android/build/gradle-home`，不会依赖可能无权限的全局 `~/.gradle`。

### 桌面构建报告 C23 不支持

安装 Clang 18+ 或 GCC 14+ 并显式设置 `CC`/`CXX`。Apple Clang 15 不满足当前要求。

### `armeabi-v7a` 被拒绝

这是预期行为。当前 FAnt runtime 的值表示和 VM 布局依赖 64 位指针，只支持 `arm64-v8a` 和 `x86_64`。

### npm 包安装成功但无法运行

纯 JavaScript 包不代表一定兼容。包可能间接使用未实现的 Node API、动态 `require()`、原生 `.node` 扩展或生命周期构建脚本。使用 `AntRuntime.inspectDependencies()` 查看分类，具体规则见 [android/README.md](android/README.md#依赖兼容性)。

## 12. 清理生成物

构建目录已经加入 `.gitignore`。需要重新构建时，删除明确的构建输出目录即可；不要删除 `src/`、`include/`、`vendor/packagefiles/` 或 Android 源码。

常见生成目录：

```text
build/
android/build/
android/demo/.gradle/
android/demo/build/
android/demo/app/build/
src/pkg/zig-out/
android/build/fant-demo-release.keystore
android/build/fant-demo-release-aligned.apk
```

不要把这些目录提交到 GitHub。

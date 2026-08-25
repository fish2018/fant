# 参与 FAnt 开发

FAnt 是以 Android 手机/电视移植为重点的 Ant runtime 二开仓库。提交改动时，
优先保证 Android AAR、Demo、npm 安装和存储桥功能稳定，同时避免无必要地破坏
底层 `AntRuntime`/`org.antjs.runtime` 兼容接口。

## 获取源码

```bash
git clone https://github.com/fish2018/fant.git
cd fant
```

Android 工具链和完整构建命令见 [BUILDING.md](BUILDING.md)。常用验证命令：

```bash
export ANT_ZIG_BIN="/path/to/zig-0.16.x/zig"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"

./android/build-demo.sh \
  --abis arm64-v8a,x86_64 \
  --min-sdk 24
```

## Android 改动原则

- 项目目录和缓存目录必须继续同时支持 `FILE_PATH` 与 `SAF_TREE`；
- 不能把 `content://` URI 当成普通 POSIX 路径；
- 不能在权限失败时静默复制或回退到 App 私有目录；
- SAF 的包安装、缓存、模块解析和运行时文件访问必须走同一 Storage Bridge；
- 不能让 SAF 实现依赖符号链接、硬链接、`mmap` 或 POSIX `flock`；
- `node_modules`、`package.json`、`ant.lockb` 和入口文件必须留在项目目录；
- npm 生命周期脚本必须保持显式信任，不得默认执行；
- 一个 Android 进程一个 runtime，同一 isolate 的 API 保持线程亲和性；
- 手机和 Android TV 都应保持可操作，触控和遥控器焦点都要考虑。

## 目录说明

```text
android/                         Android AAR 构建和 Demo
android/src/main/cpp/            JNI 与 native Storage Bridge
android/src/main/java/           AntRuntime、StorageLocation、SAF Bridge
android/demo/                    手机/电视示例 App
src/pkg/                         Zig npm 包管理器
src/modules/、src/esm/           Node 兼容模块与模块加载
src/storage.c、include/storage.h 统一存储抽象
scripts/                         面向 clone 用户的构建入口
vendor/packagefiles/             必须随仓库提交的第三方补丁
```

桌面 runtime 的原始源码仍是 Android 交叉编译的基础，不要因为 FAnt 以 Android
为重点就随意删除 `src/`、`include/`、`meson/` 或 `vendor/` 内容。

## 代码风格

- C 使用 GNU C23，现有文件通常为 2 空格缩进；
- Java 保持当前 Java 8 source compatibility；
- 函数使用 `snake_case`，宏使用大写下划线；
- 保持文件无行尾空格；
- 注释解释约束和原因，不重复代码字面行为；
- 修改第三方代码优先增加 `vendor/packagefiles/patches/` 补丁。

## 验证要求

最低检查：

```bash
bash -n scripts/build-ant.sh scripts/build-all.sh \
  android/build.sh android/build-demo.sh
git diff --check
```

修改 Android runtime、包管理器、存储或 Demo 时，应至少构建：

```bash
./android/build-demo.sh --abis arm64-v8a,x86_64 --min-sdk 24
```

有设备时还应验证：

- 首次启动和所有文件访问授权；
- `FILE_PATH` 公共目录；
- SAF 项目目录与 SAF 缓存目录；
- npm 首次下载和缓存命中；
- 安装、删除依赖；
- Start → Stop → 再次 Start；
- Activity 切后台后的 HTTP 请求；
- 手机触控和电视遥控器焦点；
- SAF 权限撤销后的明确报错。

## 提交前检查

- 不提交 `build/`、`android/build/`、Gradle/Zig 缓存、APK、AAR、`.o` 或
  `.DS_Store`；
- 确认 `android/demo/`、Gradle wrapper、Java/C/Zig 新源码和必要补丁已加入 Git；
- 不提交本机 SDK/NDK 绝对路径；
- 不修改 `../webhtv`，除非单独任务明确要求集成；
- PR 描述中写明验证的 Android 版本、ABI、设备或模拟器。

问题和功能建议请提交到：
<https://github.com/fish2018/fant/issues>

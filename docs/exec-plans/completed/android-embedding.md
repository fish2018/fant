# FAnt Android 移植实现记录

状态：已完成基础实现
最后复核：2026-08-25

## 目标

把 Ant runtime 改造成可嵌入手机和 Android TV App 的 FAnt Android runtime，
使宿主能够直接运行 JavaScript/可擦除 TypeScript、安装 npm 依赖，并同时支持
公共存储绝对路径和 SAF tree URI。当前工作只位于本仓库，尚未集成进
`../webhtv`。

## 已实现

- 交叉编译 `libant.a`、Zig 包管理器 `libpkg.a` 和 JNI shared library；
- 打包 `arm64-v8a`、`x86_64` 的 `ant-runtime.aar`；
- 提供 `AntRuntime` 的执行、入口文件加载、`pump()`、npm 安装、生命周期脚本
  和依赖兼容性检查 API；
- `evaluateFile()` 保留入口文件位置，使相对 import 和相邻 `node_modules`
  正常解析；
- npm 默认完成解析、下载、校验、解压、缓存、`ant.lockb` 和
  `node_modules` 安装；
- 生命周期脚本默认关闭，只有宿主显式信任后才执行；
- 识别纯 JS、已支持/未支持 Node API、动态加载和原生 addon；
- 增加统一 `StorageLocation`：`FILE_PATH` 与 `SAF_TREE`；
- 增加 Java/JNI/C/Zig Storage Bridge，覆盖包管理器和运行时访问；
- SAF 模式不把 URI 当路径，不生成私有副本，使用复制安装模式；
- Demo 提供手机/电视 UI、代码编辑、依赖管理、目录选择、接口测试、日志、
  前台服务和可重复 Start/Stop。

## 关键约束

- Android 最低 API 为 24；
- 当前只支持 `arm64-v8a` 和 `x86_64`，32 位 ABI 会在构建前被拒绝；
- 一个 Android 进程只允许一个 `AntRuntime`；
- runtime 的创建、调用和销毁必须在同一个长期线程；
- `pump()` 由宿主负责持续调用；
- SAF 不提供符号链接、硬链接、`mmap`、POSIX `flock` 或可靠 watch；
- `close()` 在仍有异步 handle 时会拒绝销毁；
- runtime 不是安全沙箱。

## 验证结果

- API 24 `arm64-v8a` AAR 构建成功；双 ABI 仍可通过 `--abis arm64-v8a,x86_64` 显式构建；
- `arm64-v8a` 和 `x86_64` 都是 ELF64，native load segment 满足 16 KB 对齐；
- arm64 AAR：3,913,209 字节；
- arm64 native library：9,321,232 字节；
- x86_64 native library：9,121,912 字节；
- arm64 signed release APK：10,038,010 字节，由 Gradle signing config 直接生成，
  通过 `zipalign -c` 和 `apksigner verify`；
- Demo APK 默认只包含 `arm64-v8a/libant_android.so`；显式双 ABI 构建时才包含两份；
- Gradle wrapper、shell 脚本和静态源码检查通过；
- `../webhtv` 未改动。

最终验证期间没有连接 Android 真机/电视，因此以下项目仍需设备回归：npm
在线安装、SAF Provider 差异、所有文件访问权限、前台服务后台存活和 HTTP API。

## 后续工作

- 在真实手机和电视上完成上述回归；
- 已增加 `.github/workflows/android-build.yml`，构建并上传 AAR、APK、`.so` 和校验文件；
- 在 FAnt API 稳定后单独规划 `webhtv` 集成；
- 只有重构底层 64 位指针假设后才考虑 `armeabi-v7a`；
- 只有重构全局状态和线程模型后才考虑多 isolate。

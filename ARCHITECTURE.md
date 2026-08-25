# FAnt 架构总览

Status: active
Last reviewed: 2026-08-25
Owner: fish2018

本文档是 FAnt runtime 和构建图的顶层索引，用来回答“这项改动应该放在哪个
子系统”。FAnt 的重点是 Android 手机/电视 embedding，但底层 C/Zig runtime
仍由桌面源码交叉编译产生。

## 设计重点

- 保持 runtime 体积小、启动快；
- 优先使用仓库内可复现的实现，避免隐藏的构建魔法；
- 第三方代码放在 `vendor/`，FAnt 自有代码主要位于 `src/`、`include/`、
  `meson/`、`android/` 和 `tests/`；
- Android 的 `FILE_PATH` 与 `SAF_TREE` 必须贯穿包管理器、模块加载和 `fs`；
- 不把 SAF URI 转换成伪造的普通路径，也不在权限失败时偷偷换私有目录。

## Runtime 分层

### 进程和启动

- `src/main.c` 是可选桌面 CLI 的入口；
- `src/ant.c`、`src/runtime.c` 负责 runtime 初始化和进程级状态；
- `src/cli/` 包含版本、包命令等桌面命令行逻辑；
- `android/src/main/cpp/ant_android.c` 负责 JNI 和 Android isolate 生命周期。

### JavaScript 引擎

- `src/silver/` 包含词法、解析、编译器、VM glue 和字节码操作；
- `src/gc/` 负责内存、对象和字符串生命周期；
- `src/errors.c`、`src/descriptors.c`、`src/shapes.c` 等提供共享引擎基础能力。

### 宿主平台能力

- `src/modules/` 实现内置模块和 runtime API；
- `src/builtins/` 保存内置 JavaScript shim 与 Node 兼容模块；
- `src/http/`、`src/net/`、`src/streams/` 提供协议、网络和流；
- `src/esm/` 负责模块加载、导出和内置 bundle；
- `src/storage.c`、`include/storage.h` 提供统一文件位置抽象；
- `android/src/main/java/org/antjs/runtime/StorageBridge.java` 与
  `android/src/main/cpp/storage_bridge.c` 为 SAF 提供直接桥接。

### 工具和生成输入

- `src/tools/` 生成内置 bundle 和 JS snapshot；
- `src/core/` 保存生成过程使用的 TypeScript 源码和元数据；
- `src/pkg/` 是 Zig npm 包管理器；
- Skim Meson 子项目负责可擦除 TypeScript；
- `meson/`、根目录 `meson.build` 和 `android/build.sh` 描述构建图与依赖；
- `scripts/build-ant.sh`、`scripts/build-all.sh` 和
  `android/build-demo.sh` 是面向 clone 用户的构建入口。

## Android 数据流

```text
宿主 App / Demo
    |
    v
AntRuntime（Java，单进程/单线程 isolate）
    |
    +--> JNI ant_android.c
    |       |
    |       +--> FILE_PATH：C/Zig 原生文件系统
    |       |
    |       +--> SAF_TREE：StorageBridge.java <-> storage_bridge.c
    |
    +--> package manager：package.json / ant.lockb / node_modules / cache
    |
    +--> evaluateFile() + pump()：模块解析、网络、Promise、流和定时器
```

`StorageLocation` 是项目目录和缓存目录的统一类型。`content://` URI 永远
不会被当成普通 POSIX 路径；SAF 安装采用复制模式，不依赖符号链接、硬链接、
`mmap` 或 POSIX `flock`。

## 测试和验证

- `tests/` 包含 runtime 定向测试；
- `examples/spec/` 是主要规范回归套件；
- `test262/`、`tools/wpt/` 用于更广泛的标准验证；
- Android 改动至少构建双 ABI Demo，并在有设备时验证 FILE_PATH、SAF、npm、
  前台服务和 Start/Stop。

验证命令见 [docs/repo/testing.md](docs/repo/testing.md)。跨多个阶段的工作记录在
[docs/exec-plans/index.md](docs/exec-plans/index.md)，构建入口见根目录的
[meson.build](meson.build)。

## 改动放置规则

- 解析器、字节码、执行语义和 JIT 放在 `src/silver/`；
- 堆、字符串和生命周期问题通常放在 `src/gc/`；
- 内置 API 按 C runtime、bundle JS、模块加载分别放在 `src/modules/`、
  `src/builtins/`、`src/esm/`；
- 网络和协议放在 `src/http/`、`src/net/`、`src/streams/`；
- 存储抽象放在 `src/storage.c`/`include/storage.h`，Android SAF glue 放在
  `android/src/main/`；
- 构建图优先修改 `meson/`、`meson.build` 或已存在的构建脚本。

## 必须保持的边界

- 除非任务明确要求，不要直接编辑 `vendor/` 第三方源码；
- 长期架构知识放在 `docs/`，不要放进 `todo/`；
- 生成结果必须可复现，生成文件变化时同步记录生成入口；
- 不要把 Android 绝对 SDK/NDK 路径、APK、AAR、`.o`、Gradle/Zig 缓存提交到 Git；
- 除非单独授权，不要修改 `../webhtv`。

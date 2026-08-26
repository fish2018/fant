# FAnt 测试指南

Status: active
Last reviewed: 2026-08-25
Owner: fish2018

本指南按改动风险选择验证范围，同时保护 FAnt runtime 和 Android 移植行为。

## 常用命令

```bash
# 构建 Android AAR 和 Demo（默认 arm64，release 直接签名）
./android/build-demo.sh --min-sdk 24

# tag/release 的多 ABI 产物（发布 AAR 包含 32 位 ARM）
./android/build-demo.sh --abis arm64-v8a,armeabi-v7a --min-sdk 24 --release

# 构建已配置的桌面树
maid build

# 全新桌面配置和构建
maid setup && maid build

# 运行单个 runtime 测试
./build/ant tests/test_<name>.cjs

# 运行规范套件
./build/ant examples/spec/run.js --all

# 检查仓库文档和改动边界
maid knowledge
maid structure
maid validate_changes
```

## 按改动类型验证

### runtime 模块改动

修改 `src/modules/`、`src/esm/` 或 `src/builtins/` 时：

- 运行或新增最贴近改动的 `tests/test_<name>.cjs`；
- 影响共享语义或广泛使用的内置模块时，运行对应的规范测试。

### 引擎核心改动

修改 `src/silver/`、`src/gc/` 或 runtime 核心文件时：

- 使用 `maid build` 重建；
- 先运行定向回归测试；
- 合入执行语义改动前运行 `./build/ant examples/spec/run.js --all`。

### Android、存储或包管理器改动

至少重新构建默认 arm64 Demo；如果改动涉及 ABI 或模拟器，再构建双 ABI：

```bash
./android/build-demo.sh --min-sdk 24
./android/build-demo.sh --abis arm64-v8a,x86_64 --min-sdk 24
./android/build-demo.sh --abis armeabi-v7a --min-sdk 24
```

有设备时还应验证：

- 首次启动和所有文件访问授权；
- `FILE_PATH` 公共目录；
- SAF 项目目录与 SAF 缓存目录；
- npm 首次下载和缓存命中；
- 添加、删除和安装依赖；
- Start → Stop → 再次 Start；
- Activity 切后台后的 HTTP 请求；
- 手机触控和电视遥控器焦点；
- SAF 权限撤销后的明确报错。

### 构建脚本改动

```bash
bash -n scripts/build-ant.sh scripts/build-all.sh \
  android/build.sh android/build-demo.sh
git diff --check
```

桌面构建图改动还需要重新运行对应 Meson 流程。

## 验证限制

- 没有 Android 设备时，明确记录“仅完成 AAR/APK 构建，未完成真机回归”；
- Android 构建和完整规范套件可能需要网络和较长时间；
- 如果验证昂贵或不可用，在对应 [execution plan](../exec-plans/index.md) 中记录缺口。

## 生成物边界

不要提交以下内容：

```text
build/
android/build/
android/demo/.gradle/
android/demo/build/
android/demo/app/build/
src/pkg/zig-out/
*.o
*.apk
*.aar
.DS_Store
```

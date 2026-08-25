# FAnt 上游同步记录

本文档记录 FAnt 的上游来源、精确分叉点和建议同步流程，避免后续把 Android
移植改动与上游 Ant 改动混在一起处理。

## 当前上游基线

| 项目 | 值 |
| --- | --- |
| FAnt 仓库 | <https://github.com/fish2018/fant> |
| 上游项目 | Ant |
| 上游仓库 | <https://github.com/theMackabu/ant> |
| 建立移植时的上游分支 | `master` |
| 上游基线 commit | `4091d86bc1fc9825eea4080ed71685689d81a2c6` |
| 上游 commit 标题 | `update score.json and pgo` |
| GitHub 页面日期 | `2026-08-19`（中国时区，页面分组为 “Commits on Aug 19, 2026”） |
| 上游 Git 原始时间 | `2026-08-18 21:36:33 -07:00` |
| 中国时区时间 | `2026-08-19 12:36:33 +08:00` |
| 首个 Android 移植 commit | `0f9fc5dbea0fe3b93632fb6b7ae90ba76e8a9db7` |

Git 关系：

```text
4091d86bc1fc9825eea4080ed71685689d81a2c6  Ant 上游基线
    |
    v
0f9fc5dbea0fe3b93632fb6b7ae90ba76e8a9db7  add Android runtime embedding
    |
    v
FAnt 后续 Android/SAF/npm/Demo 改动
```

可用下面命令复核：

```bash
git rev-parse 0f9fc5dbea0fe3b93632fb6b7ae90ba76e8a9db7^
git merge-base \
  0f9fc5dbea0fe3b93632fb6b7ae90ba76e8a9db7 \
  4091d86bc1fc9825eea4080ed71685689d81a2c6
```

两个命令都应输出：

```text
4091d86bc1fc9825eea4080ed71685689d81a2c6
```

## 首次配置上游 remote

用户从 FAnt 仓库 clone 后，`origin` 应指向自己的 fork：

```bash
git clone https://github.com/fish2018/fant.git
cd fant
git remote add upstream https://github.com/theMackabu/ant.git
git fetch upstream --tags
git remote -v
```

预期关系：

```text
origin    https://github.com/fish2018/fant.git
upstream  https://github.com/theMackabu/ant.git
```

不要把 `origin` 重新指向上游，否则容易把 FAnt 改动推错仓库。

## 查看基线之后的上游变化

```bash
git fetch upstream --prune --tags

git log --oneline --decorate \
  4091d86bc1fc9825eea4080ed71685689d81a2c6..upstream/master

git diff --stat \
  4091d86bc1fc9825eea4080ed71685689d81a2c6..upstream/master
```

如果上游以后修改默认分支，请把命令中的 `upstream/master` 换成实际分支，
但不要修改历史基线 commit。

## 建议同步流程

不要直接在稳定分支上盲目合并。先创建同步分支：

```bash
git switch main
git pull --ff-only origin main
git switch -c upstream-sync-YYYYMMDD
git fetch upstream --prune --tags
git merge --no-commit --no-ff upstream/master
```

解决冲突后，优先检查这些 FAnt 重点区域：

- `android/`：JNI、AAR、Demo 和 Gradle；
- `src/storage.c`、`include/storage.h`：统一存储抽象；
- `src/modules/fs.c`：FILE_PATH/SAF_TREE 的运行时文件访问；
- `src/esm/`：入口、相对路径和 `node_modules` 模块解析；
- `src/pkg/`：npm、缓存、lockfile、复制安装和 SAF；
- `src/runtime.c`、`src/ant.c`：单进程 isolate 和 Android 生命周期；
- `meson.build`、`meson/`、`packages/libant/`：交叉编译与打包；
- `vendor/*.wrap` 和 `vendor/packagefiles/patches/`：第三方补丁是否仍能应用。

如果冲突范围过大，可以先取消合并：

```bash
git merge --abort
```

再按子系统逐批移植上游提交。

## 合并后的最低验证

```bash
git diff --check
bash -n scripts/build-ant.sh scripts/build-all.sh \
  android/build.sh android/build-demo.sh

./android/build-demo.sh \
  --release \
  --abis arm64-v8a,x86_64 \
  --min-sdk 24
```

必须确认：

- AAR 同时包含 `arm64-v8a` 和 `x86_64`；
- release APK 通过 `apksigner verify`；
- npm 安装仍默认开启；
- lifecycle scripts 仍默认关闭；
- FILE_PATH 和 SAF_TREE 都能安装依赖并运行入口；
- SAF 模式没有产生 App 私有运行副本；
- Start → Stop → 再次 Start 正常；
- 没有修改 `../webhtv`。

## 同步记录模板

每次完成上游合并后，在本文件末尾追加：

```text
同步日期：YYYY-MM-DD
上游目标：<commit SHA>
FAnt 合并提交：<commit SHA>
同步范围：
主要冲突：
保留的 FAnt 行为：
验证设备/ABI：
未完成验证：
```

## 同步历史

### 初始分叉

- 同步日期：2026-08-19（中国时区基线日期）
- 上游目标：`4091d86bc1fc9825eea4080ed71685689d81a2c6`
- FAnt Android 起点：`0f9fc5dbea0fe3b93632fb6b7ae90ba76e8a9db7`
- 说明：从该上游 commit 开始进行 Android runtime、npm、SAF、AAR 和 Demo 移植。

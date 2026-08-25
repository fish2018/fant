# FAnt 仓库文档索引

Status: active
Last reviewed: 2026-04-09
Owner: fish2018

这里是 FAnt 仓库工作流的长期、版本化知识库。优先阅读能直接回答当前问题的
最小文档。Android 移植和 AAR/Demo 是本仓库当前主线。

## 核心文档

- Agent entrypoint: [../../AGENTS.md](../../AGENTS.md)
- Architecture map: [../../ARCHITECTURE.md](../../ARCHITECTURE.md)
- Build instructions: [../../BUILDING.md](../../BUILDING.md)
- Contribution guide: [../../CONTRIBUTING.md](../../CONTRIBUTING.md)
- Upstream baseline and sync guide: [../../UPSTREAM.md](../../UPSTREAM.md)
- Test selection guide: [testing.md](testing.md)
- Execution plans and tech debt: [../exec-plans/index.md](../exec-plans/index.md)

## 使用方式

- 稳定规则放在这里，不依赖聊天记录或临时目录；
- 跨多个决策或检查点的工作使用 execution plan；
- 保持 `AGENTS.md` 简短，通过链接指向本目录；
- 更新后可运行 `maid knowledge`、`maid structure` 和 `maid validate_changes`。

## 何时新增文档

- 当规则、子系统地图或工作流会在多个任务中重复使用时新增文档；
- 每个主题优先使用一份聚焦文档，不要继续堆成一本无边界的大手册。

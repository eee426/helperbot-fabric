# HelperBot

一个 Fabric 服务端模组：在服务器里放一个"真玩家"机器人，你说要什么，它自己规划、采集、合成、带回来。

*A server-side Fabric mod for Minecraft 1.21.11: a bot that is a real player on the server. Ask it for an item and it plans, gathers, crafts and brings it back, using only the tools you give it.*

> **贝塔阶段。** 为了测试能力上限，默认规则是 `open`：除了基岩、TNT、紧挨岩浆的方块，**什么都能挖**，包括玩家的建筑。不要直接放到有别人建筑的正式服上，先看 [docs/LAUNCH_CHECKLIST.md](docs/LAUNCH_CHECKLIST.md)。

## 它会做什么

- **要东西**：`/bot get <物品> [数量] [模式]`。它从游戏数据里查这样东西从哪来（挖、打怪、合成、熔炼、舀水或岩浆……），算出最省时间的做法，然后自己去做。
- **像玩家一样行动**：走、跑、游泳、挖、垫柱子、搭桥，都按原版的物理和真实刻数算；只用你给它的工具。
- **自己保命**：换气、吃东西、打架、搭路的方块不够时去补，都在任务之外自动处理。
- **守规矩**：保护区块里它不挖不放，也不和机关互动；可以限制某种方块不挖不放。
- **客户端遥控器（可选）**：装上 `Helper Bot Remote`，按 H 打开菜单选要什么，不用打指令；还能在俯视地图上选保护区块。

## 安装

需要 Minecraft 1.21.11、Fabric Loader 0.17.3 以上、Fabric API、Java 21。

- **服务器**（单人游戏也算）：把 `helperbot-<版本>.jar` 放进 `mods/`。
- **客户端遥控器**（可选，只在客户端装）：`helperbot-client-<版本>.jar`。

## 指令

| 指令 | 作用 |
|---|---|
| `/bot spawn` · `/bot remove` | 召唤 / 移除机器人（全服一只） |
| `/bot get <物品> [数量] [safe\|normal\|aggressive]` | 去拿东西。模式决定肯跳多高、跑不跑、多不愿意挖地形，默认 `aggressive` |
| `/bot come [模式]` | 过来找你 |
| `/bot cancel` | 停下当前任务 |
| `/bot status` · `/bot log` | 现在在做什么 / 最近的行为记录 |
| `/bot rules [open\|strict]` | 查看或切换规则。`strict`：只挖天然地形，不补方块 |
| `/bot protect` · `list` · `show` | 保护你所在的区块 / 列出 / 显示保护区块 |
| `/bot protect remove <ID>` | 取消保护（只能在服务器后台，权限 4） |
| `/bot restrict block <方块>` · `restrict portals on\|off` | 限制某种方块不挖不放 / 走不走传送门（OP） |
| `/bot debug path` · `/bot perf` | 显示规划的路线 / 每刻耗时 |

右键机器人可以打开它的背包，给它工具、拿走东西。

## 从源码构建

```bash
./gradlew build                 # 服务端，输出 build/libs/
./gradlew -p client build       # 客户端遥控器，输出 client/build/libs/
./gradlew runServer             # 开一个开发服务器
```

## 测试

没有单元测试，测的是**场景**：在新建的超平坦世界里搭好地形、放好机器人，跑真实的游戏刻，看它做没做对。现在有 160 多个场景（`src/main/java/com/vale/helperbot/test/Scenarios.java`，说明见 [docs/REGRESSION_TESTS.md](docs/REGRESSION_TESTS.md)）。

```powershell
scripts\run-scenarios.ps1               # 全部场景，报告写到 run\scenario-report.md
scripts\run-scenarios.ps1 W2,H1         # 只跑编号以 W2 或 H1 开头的
scripts\run-scenarios.ps1 W -Repeat 5   # 有随机性的场景多跑几遍，统计通过次数
```

## 文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层和每个包做什么 |
| [DESIGN.md](docs/DESIGN.md) | 设计决定和理由 |
| [HelperBot 行为图.html](docs/HelperBot%20行为图.html) | 每条任务链实际怎么走 |
| [BUGS.md](docs/BUGS.md) | 找到过的 bug：原因、修法、对应的场景和提交 |
| [UNDERWATER_PLAN.md](docs/UNDERWATER_PLAN.md) | 水下（换气、潜水）的设计 |
| [PERF_BACKLOG.md](docs/PERF_BACKLOG.md) | 性能优化和计划中的新动作 |
| [LAUNCH_CHECKLIST.md](docs/LAUNCH_CHECKLIST.md) | 上正式服之前必须收紧的地方 |
| [VISION.md](docs/VISION.md) | 长期方向 |

## 设计原则

- **游戏数据，不靠猜。** 时间按真实刻数，知识从游戏数据里读，不凭感觉调系数。
- **每个问题只有一个地方回答。** 能不能到（寻路）、怎么做最省（物品规划）、现在保不保命（反射），各由一个内核回答；外层只排先后、省时间、兜底。
- **先写场景，再修 bug。** 每个修好的 bug 都有一个在旧代码上会失败的场景。

## 致谢

寻路的一些做法借鉴了 [Baritone](https://github.com/cabaletta/baritone) 的思路（沿用上一条路线、部分路线的选法、每步超时等），代码是自己写的。

## 许可

见 [LICENSE](LICENSE)。

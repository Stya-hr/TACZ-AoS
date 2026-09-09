# TACZ Long Range Sniping

Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21.

TACZ 的远距离狙击扩展项目，计划提供开镜实体同步、服务端碰撞快照和远程弹道，并兼容 NeoVoxy 远景显示。
当前已完成第一步：服务端配置、枪械／瞄准镜／当前倍率／开镜状态的联合资格判定，以及 TACZ 子弹参数读取接口。
此阶段只观察射击，不修改原有子弹，不加载远端区块，不新增实体同步或伤害逻辑；NeoVoxy 渲染适配尚未实现。

## 项目标识

- 显示名：TACZ Long Range Sniping
- Mod ID：`tacz_long_range_sniping`
- Java 包 / Maven group：`tacz.longrangesniping`
- 主类：`TaczLongRangeSniping`

## 依赖

- 必选：[TACZ 1.21.1 NeoForge Port](https://modrinth.com/mod/tacz-1.21.1)，开发版本 `1.1.8-hotfix-r6`，由 Gradle 从 Modrinth Maven 获取。
- 可选：NeoVoxy `0.4.3-beta.1` 联动版，本地 JAR 放在 `libs`，详见 [本地依赖说明](libs/README.md)。
- 本扩展需要安装在客户端和服务端；NeoVoxy 不作为本扩展的必选依赖。
- 依赖不会打包进本模组 JAR；运行游戏时需要另行安装 TACZ。

## 开发

```powershell
.\gradlew.bat build
.\gradlew.bat runGameTestServer
.\gradlew.bat runClient
.\gradlew.bat runServer
```

默认开发运行环境不启用 NeoVoxy，可用于验证无远景模组时的启动。
兼容开发时，先按 `libs/README.md` 准备 NeoVoxy 的前置，再运行：

```powershell
.\gradlew.bat runClient -Penable_voxy_runtime=true
```

构建产物位于 `build/libs/tacz_long_range_sniping-<版本>.jar`。

## 配置与白名单

启动世界／服务端后生成 `config/tacz_long_range_sniping-server.toml`。
NeoForge 也支持在存档的 `serverconfig/` 下放置同名文件覆盖全局配置。
配置类型为 SERVER，由服务端决定，并通过 NeoForge 同步给客户端；客户端本地配置不能扩大服务器授权。
本模组监听配置加载、重载和卸载事件，每次替换完整的不可变配置快照。
修改运行中的服务端文件可触发 NeoForge 重载；已连接客户端需要重新连接以确保收到最新配置。

```toml
[eligibility]
enabled = true
guns = ["tacz:ai_awp", "tacz:m95", "tacz:m700", "tacz:m107", "tacz:kar98", "tacz:springfield1873"]
scopeRules = [
  "tacz:scope_mk5hd|5,25,1.25|25",
  "tacz:scope_vudu|6.5,1.35|6.5",
  "tacz:scope_lpvo_1_6|6.25,1.25|6.25",
  "tacz:scope_standard_8x|4.5,10|10",
  "tacz:scope_1873_6x|6|6"
]

[limits]
maxDistance = 1024
maxTrackedEntities = 32
snapshotMemoryMiB = 128

[diagnostics]
enabled = false
```

每条瞄准镜规则是 `配件ID|完整有序倍率表|允许的倍率`。例如 `tacz:scope_mk5hd|5,25,1.25|25`
表示该镜有 5×、25×、1.25× 三档，仅 25× 合规。可写 `|5,25` 允许多档；倍率不要求为整数。
完整倍率表不能省略低倍率档位，也不能重排：TACZ 使用 `ZoomNumber % 档位数量` 选档。
倍率属于瞄准镜配件规则，同一瞄准镜规则适用于所有白名单枪械。

判定必须同时满足：主手枪械 ID 在白名单、实际安装的瞄准镜 ID 在白名单、当前档位倍率被允许、玩家正在开镜且存活并非旁观者。
还会确认服务端存在相应枪械／瞄准镜数据。此阶段仅支持安装在 SCOPE 槽的瞄准镜，不支持枪械内置光学瞄具。
空白名单拒绝全部；重复 ID、非法资源 ID、非正数或非有限倍率、允许倍率不属于档位表等规则错误会禁用资格判定并记录原因。
缺失配置键或 TOML 基本类型错误由 NeoForge 自身的配置修复机制处理。

默认枪械是当前依赖内置枪包的六种 `sniper`。枪械白名单不会自动允许新枪包的所有狙击枪。
白名单不改变 TACZ 的配件安装限制；例如 KAR98 获准并不代表它能安装上述每一种镜。
默认排除 4.25× 的专用镜；`scope_standard_8x` 在此 TACZ 版本中实际引用 `scope_scout_display`，
其倍率是 4.5×／10×，因此按 10× 配置，不能由物品名称推算。

服务端枪包数据没有客户端 display 的完整倍率数组，因此管理员需让规则与玩家枪包保持一致。
`TaczClientBridge.evaluateForDisplay` 为后续客户端功能提供倍率表一致性检查接口；它当前尚未接入 HUD，也不是服务端的授权凭据。
新增／修改枪包倍率时必须同步修改规则，并让客户端重新连接。

`limits` 三项仅为后续阶段预留预算，目前不用于延长射程、订阅实体或分配地形缓存。

## 调试与接口边界

- 游戏内执行 `/taczlrs status`，查看自己的枪械、瞄准镜、当前档位、倍率及拒绝原因，无需 OP。
- 开启 `diagnostics.enabled` 后，服务端日志在资格变化时输出状态，在合规子弹生成时输出参数。
  输入聊天命令通常会收镜，观察真实开镜与变倍状态时应使用这个日志开关。
- `TaczBridge.evaluate` 在调用时重新读取装备和状态；服务端使用射手即时开镜状态，避免同步标记延迟。
- `TaczBridge.inspectNewBullet` 在 TACZ 初始化子弹之后读取实际位置、速度、寿命、重力、阻力、穿透数、有效射程、爆头倍率、穿甲比例及爆炸／燃烧标记。
  有效射程是 TACZ 的伤害参数，不等于子弹最大飞行距离。本阶段没有重新计算或覆盖这些参数。
- `TaczShotObservedEvent` 是服务端的不可取消观察事件，包含原始子弹和不可变的开火时快照，供下一阶段接入。
  已加载存档实体、客户端实体、不合规装备以及枪械 ID 不匹配的子弹不会发布此事件。
- 私有参数通过只读 Mixin Accessor 获取。接口按固定的 TACZ `1.1.8-hotfix-r6` 验证；升级 TACZ 后需重新运行测试。

## 验证

`build` 包含 JUnit 测试，覆盖联合白名单、切枪／收镜／变倍、循环变倍序号、非法配置与小数倍率，
并直接读取依赖 JAR 核对默认枪械类别和瞄准镜引用的真实倍率表。
`runGameTestServer` 在 `build/gametest` 下启动无 NeoVoxy 的专用测试服务端，验证配置加载、实际 TACZ 装备状态、
即时开镜判定及子弹参数 Mixin。测试类和测试结构位于独立 `gameTest` 源集，不进入发布 JAR。
客户端实际开镜显示、多人网络联机及 NeoVoxy 渲染兼容仍需在相应功能接入后进行游戏内验证。

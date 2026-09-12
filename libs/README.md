# 本地可选依赖

本扩展使用以下用户提供的客户端依赖，均不打包进发布 JAR：

- `neo-voxy-0.4.3-beta.1-mc1.21.1-neoforge-integrations.jar`，mod ID `voxy`。
- `iris-neoforge-1.8.14-beta.1+mc1.21.1.jar`，mod ID `iris`。
- `sodium-neoforge-0.8.13+mc1.21.1.jar`，上述客户端模组的运行前置。

Voxy 与 Iris 均为 `compileOnly`，编译需把这两个 JAR 放在本目录。服务器和普通客户端不需要安装它们。
本地第三方 JAR 由 `.gitignore` 排除，其他开发者需自行取得，不重新分发。

ParCool 为无版本限制的可选兼容，使用缓存反射，编译与普通运行不需要其 JAR。官方 Modrinth 的 `ParCool-1.21.1-4.0.0.3.jar`、`ParCool-1.21.1-3.4.3.3-NF.jar` 放在此处仅供隔离测试，不同时加载两个版本。4.0.0.3 的发布元数据还要求 Curios，测试使用 `curios-neoforge-9.5.1+1.21.1.jar`。

`runGameTestServer` 可添加 `'-Pparcool_test_jar=ParCool-1.21.1-4.0.0.3.jar'` 和 `'-Pparcool_test_curios_jar=curios-neoforge-9.5.1+1.21.1.jar'`；测试 3.x 时只指定对应 ParCool JAR；不传这些参数则验证未安装环境。检查日志中的 `ParCool ... both actual launch routes passed` 和 `All ... required tests passed`，不能只凭 Gradle 的退出状态判断测试成功。

隔离测试 `runClientScopeTest`、`runClientLongRangeTest` 支持：

- `-Penable_voxy_runtime=true` 启用 Voxy。
- `-Penable_iris_runtime=true` 启用 Iris。
- 两个选项均默认为关闭。任务会将所需 Iris/Sodium 暂存到该运行目录的 `mods/`，保证嵌套库正常发现。
- 光影包另行放入该运行目录的 `shaderpacks/`，在对应 `config/iris.properties` 配置 `enableShaders=true`、`shaderPack=文件名.zip`。
- 不会修改实际整合包。测试世界、配置和截图均在 `build/client-*-test` 内。

兼容实现直接读取附件 Voxy 的地形帧缓冲，使用同一帧矩阵和实际 GL 深度映射；不假设 Iris 会把 LOD 深度复制到主缓冲。
光影烟迹使用 Iris 支持的 particle 通道，不绘制独立的弹头发光点。

普通 `runClient` 如需光影，请将 Iris 和 Sodium 放入 `run/mods/`，Voxy 用既有运行选项启用；不要重复放入相同模组。带 Iris 的兼容测试要求所选光影包确实成功启用，光影编译失败后回退原版不会被算作通过。

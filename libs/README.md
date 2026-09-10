# 本地可选依赖

`neo-voxy-0.4.3-beta.1-mc1.21.1-neoforge-integrations.jar` 为用户提供的 NeoVoxy 联动版，mod ID 为 `voxy`。
上游：https://github.com/NHblock-Johnsnow/neo-voxy

- 默认使用 `compileOnly` 引入，不加入开发运行环境，也不打包到本扩展。
- 使用 `-Penable_voxy_runtime=true` 加入开发运行环境。
- 启用客户端运行前，需将适用于 Minecraft 1.21.1 NeoForge 的 Sodium `[0.8.12,0.9)` 放入 `run/mods`（依据该 JAR 元数据）。
- 也可将 Sodium 0.8.13 放入本目录，文件名 `sodium-neoforge-0.8.13+mc1.21.1.jar`，`runClientLongRangeTest -Penable_voxy_runtime=true` 会将其暂存到隔离测试的 mods 目录；普通 runClient 仍按上文放到 run/mods。
- 已验证 NeoVoxy + Sodium 的隔离远端测距、实体及弹道测试；Iris 光影和完整整合包仍需单独验证。
- 本地第三方 JAR 由 `.gitignore` 排除；其他开发者需自行取得相同文件放入本目录。普通构建目前不引用 NeoVoxy 类，因此没有该 JAR 也能构建。

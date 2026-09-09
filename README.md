# TACZ Long Range Sniping

Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21.

TACZ 的远距离狙击扩展项目，计划提供开镜实体同步、服务端碰撞快照和远程弹道，并兼容 NeoVoxy 远景显示。
当前阶段仅完成项目初始化与依赖配置，尚未实现狙击玩法或 NeoVoxy 渲染适配。

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
.\gradlew.bat runClient
.\gradlew.bat runServer
```

默认开发运行环境不启用 NeoVoxy，可用于验证无远景模组时的启动。
兼容开发时，先按 `libs/README.md` 准备 NeoVoxy 的前置，再运行：

```powershell
.\gradlew.bat runClient -Penable_voxy_runtime=true
```

构建产物位于 `build/libs/tacz_long_range_sniping-<版本>.jar`。

# Forge 1.20.1 dependencies

- Required TACZ: official [Timeless and Classics Zero](https://modrinth.com/mod/timeless-and-classics-zero), **1.1.8-hotfix**. Gradle resolves this from Modrinth.
- Optional NeoVoxy: put the supplied `neo-voxy-0.3.3-mc1.20.1-forge-client.jar` here. It is a compile-only dependency, never bundled in AoS. The jar's own required client dependency is **Embeddium 0.3.31+**.
- Optional [Oculus](https://modrinth.com/mod/oculus) **1.8.0** is a compile-only Modrinth dependency. It is never bundled; only clients using shaders need to install it.
- Optional Curios **5.14.1+1.20.1**, Lightman's Currency **1.20.1-2.3.0.5**, Collectors Album **2.1.4**, Champions Unofficial **1.20.1-2.1.10.2**, and Touhou Little Maid **1.5.3-forge+mc1.20.1** APIs are resolved as compile-only Modrinth dependencies. They are not mandatory or bundled.
- ParCool and Crawl on Demand use optional reflection bridges and do not need compile-time jars.

Build with Java 17 and the Gradle wrapper: `gradlew build`. Development options: `-Penable_voxy_runtime=true`, `-Penable_oculus_runtime=true`, `-Penable_curios_runtime=true`, `-Penable_maid_compat_runtime=true`, `-Penable_loot_compat_runtime=true`, and `-Penable_prone_compat_runtime=true`. Either rendering option loads Embeddium in development clients. Do not enable these client-only runtimes for a dedicated server.

Previous NeoForge Iris/Sodium sources and binaries remain archived outside source sets. The active Forge implementation uses the Oculus 1.8.0 API behind an optional class-loading boundary. Third-party binaries remain git-ignored and are not redistributed in the release jar.

Champions is pinned to Forge 1.20.1-2.1.10.2 for this Forge baseline. Its eager KubeJS references require KubeJS/Rhino for the optional test run; these are not AoS dependencies and are not bundled.

# wallwarplugins

战墙玩法的 Paper 插件。数据包仓库为 `ShiinaYumeka/wallwar`；本仓库提供独立 Java 插件。

## 1.0.1：队友地图发光的连接生命周期修复

原始异常：

```text
java.util.NoSuchElementException: packet_handler
  at TeamMapGlowManager.injectHandler
```

玩家进服后的延迟任务可能在玩家已断线、管线已移除处理器后继续执行。旧代码还直接在
Bukkit 主线程操作 Netty 管线，并在重写元数据时通过 NMS 二次发送，遗漏原 ChannelPromise。

修复内容：

- 执行进服任务前检查玩家在线状态和插件启用状态。
- 管线注入和移除均在该连接的 Netty event loop 上执行。
- 注入前检查连接存活、处理器存在；准备中的管线最多重试三次，每次间隔 50 ms。
- 支持按 `net.minecraft.network.Connection` 类型寻找已改名的处理器；不猜测编码器名称，
  不在错误位置任意追加处理器。
- 玩家退出、连接关闭、插件停用时取消待执行的重试并清理自身处理器，不移除其他实例。
- 修改包后沿当前 context 转发一次并保留原 promise，避免重入和悬空发送。
- 发光效果的反射或运行时异常只跳过该次效果并转发原包，不吞掉下游实际发送失败。

**这个修复不修改 AntiAll 或 PacketEvents，也不关闭反作弊检测。** AntiAll 的
`InventoryView` / `PacketWrapper` 字段兼容问题需要应用 AntiAll 自身的修复。

## 构建和验证

需要 JDK 21 和 Maven 3.9：

```sh
mvn test
mvn package
mvn -Dnetty.version=4.2.7.Final test
```

输出为 `target/wallwarplugins-1.0.1.jar`。测试使用内存通道和本地 event loop，不启动
生产服务器。测试中的同名 Minecraft Connection 仅为识别处理器类型的测试夹具，
不会打包进插件。

2026-10-05：20 项连接与转发回归检查覆盖原异常复现、断线、延迟处理器、重复注入、
退出取消重试、异线程 event loop、处理器检查后被移除的竞争、名称冲突、promise 成功/失败以及重写异常透传。
在 Netty 4.1.118.Final 和生产日志对应的 4.2.7.Final 上分别验证。

源码修复及构建验证不等于真实客户端验收；发布时应正常停服替换，不使用 `/reload`。
本次不包含生产服务器的插件替换或重启。

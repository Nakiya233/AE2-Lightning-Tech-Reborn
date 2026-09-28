# 1.21.1 alpha 增量同步到 1.20.1-alpha

源：Reborn LT `alpha` 的 `21926da`；目标基线：`ada63130`。
只同步目标缺少的功能与必要修复，不替换本分支加载器适配。TB 全局适配器依赖同步自 `7559206`；运行时需要对应版本同步后的 TB。LTPP 未改动。

## 同步范围

- 相位限额与过载防护：相位最多吸收 1024 点，每点 20,000 FE + 2 EHV；20 tick 最高费用补差额。过载普通伤害固定首档 2 GFE + 1024 EHV，只有独立死亡触发递增，最终上限 20 GFE + 16,384 EHV。保留共享窗口、去重、互斥及旧不死 ID/已安装模块/设置/研究迁移。
- 全局批量提供器转由 TB 管理；时间轮完成结算后再返还矩阵产物，保留资源归属和异常处理。
- 过载工厂借用 AAE 反应配方，处理器压印派生配方与压缩材料匹配；移除重复固定反应配方。
- AE2CS 母岩催化，普通催化器 2048 基础并行、16384 产物容量，Pigmee 保持 64 并行。
- 矿场、过载 IO 端口及筛选/矩阵升级、输出设置和新版贴图；彩虹 Pigmee、染料与半砖、落地潜行及收集器范围修正。已在目标存在的部分保留原实现。

## 验证

- `./gradlew --offline -I scripts/alpha-sync-test.init.gradle runGameTestServer test build`：89 项游戏测试、1258 项单元测试通过。
- `./gradlew --offline -I scripts/latest-alpha-client-test.init.gradle -Palpha_client_probe=io -Penable_emi_runtime=false runClient test build`：IO 实际客户端的 55 槽位、筛选器和 16 矩阵 Shift 点击、吞吐同步、按钮数据包、闪电状态、JEI 配方布局通过并正常退出；同时完成最终单元测试与发行构建。JEI 探针需关闭 EMI 开发运行依赖，EMI 会接管 JEI runtime。
- 原生 Forge Hurt/Damage 回调按同一普通伤害费用去重；旧 NBT 读取迁移以复制方式写回，避免修改原始保存标签。
- 检查发行 JAR：唯一 `ae2lt.refmap.json`；`renderSlot`、RecipeManager `apply`、LivingEntity 伤害方法映射为 SRG 名，RecipeManager 的 `byName` / `byType` 也已重映射。
- AAE 1.20.1 使用其实际 1.3.6 配方 API；流体条件按该版本支持的精确流体匹配。

新增游戏用例包括实际伤害回调、旧存档迁移、窗口计费、实际机器处理/派生配方、IO 收发与升级、矩阵回流及批量执行；测试 fixture 不进入发行资源。
可选模组存在时才运行相关接口用例。未安装的 AE2CS/NeoECO/Useless/EAEP 或 KubeJS 整包组合不计为实机验证；AAE 借用配方和无可选模组时的回退路径已覆盖。

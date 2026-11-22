package com.dzshzx.zeta

/**
 * 标在测试类上（`@Category(FullGateOnly::class)`）：合入门禁不跑它，全量门禁（`ZETA_GATE_FULL=1 bash
 * scripts/check.sh`，每晚的 CI 和发版前跑的就是它）才跑。直接跑 Gradle（`tools/gradle
 * :app:testDebugUnitTest`、`tools/shots`）时照常跑。
 *
 * 现在只有截图测试（继承 `ShotTest` 的）：占单元测试一半的时间，全量门禁所在的 CI 上跑得了。冷启动画不画得出界面由 `AppStartTest` 在合入门禁里查，改界面时用
 * `tools/shots` 自己画。
 *
 * 往这里加别的测试前先看两件事：
 * - 是不是在白等（连接超时、宽限期、等一个不会来的事件）：是就把等待改掉，不标。
 * - 全量门禁所在的 CI 上它是不是真的跑：钉住真 claude 命令行行为的测试（各 ContractTest）在没有 claude 命令的机器上是跳过的，
 *   标了就没有地方跑它了，所以留在合入门禁里。
 */
interface FullGateOnly

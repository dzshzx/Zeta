# shellcheck shell=bash
# 重的常驻进程（Gradle 守护进程连同它派生的测试进程、模拟器）各自单独一个 systemd 分组，不记在启动它的
# agent 会话的分组里。会话分组有自己的内存上限（本机 16G），这些进程却是各会话共用的：记在谁头上，谁撞上限时
# 内核就在谁的分组里回收和查杀，别的会话的构建和共用的模拟器也跟着卡住或被杀。
#
# 只在当前进程位于 agent 会话的分组（agent-cpu-*.scope）之下、用户总线在时才包一层；手机 Debian、本机 CI runner、
# 托管机照旧直接启动。新分组和会话分组同级，挂在 app-agent.slice 下，仍受它的合计内存上限管。
# 设置和会话分组一致：撞上自己的上限时只杀单个进程（OOMPolicy=continue），交换最多 1G，结束后立即回收。
#
# 用法：source 本文件后 own_scope <名字> <内存上限>，再 exec "${OWN_SCOPE[@]}" <命令…>。
# 不包时 OWN_SCOPE 为空，命令原样执行。systemd-run --scope 在本进程里 exec 命令，打开的文件描述符（比如锁）和退出码都原样传下去；
# 关掉它对命令行的 $ 展开，参数（比如 --tests 'Foo$Bar'）原样交给命令。
own_scope() {
  OWN_SCOPE=()
  local bus=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/bus
  grep -q '/agent-cpu-[^/]*\.scope$' /proc/self/cgroup 2>/dev/null || return 0
  [[ -S $bus ]] && command -v systemd-run >/dev/null || return 0
  # shellcheck disable=SC2034 # 由 source 本文件的脚本使用
  OWN_SCOPE=(systemd-run --user --scope --quiet --collect --expand-environment=no --slice=app-agent.slice --unit="$1-$$"
    -p OOMPolicy=continue -p MemoryMax="$2" -p MemorySwapMax=1G --)
}

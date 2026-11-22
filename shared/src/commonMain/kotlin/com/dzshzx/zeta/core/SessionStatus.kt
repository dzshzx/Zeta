package com.dzshzx.zeta.core

/** 会话列表里一个会话眼下的状态；[tasks] 是还在跑的后台任务数。只是历史记录、进程不在的会话没有状态。 */
data class SessionStatus(val activity: Activity, val tasks: Int = 0) {
    enum class Activity {
        /** 等用户批准一次工具调用。 */
        Waiting,
        /** 这一轮正在回答。 */
        Answering,
        /** 这一轮答完了，还有后台任务在跑。 */
        Background,
        /** 不在眼前时答完了一轮，用户还没点开看。 */
        Unread,
        /** 进程还开着，没在干活。 */
        Idle,
    }

    companion object {
        /** 几样同时成立时只报最要紧的一样：等确认、在回答、后台任务、有新回答、闲着。 */
        fun of(
            waiting: Boolean,
            busy: Boolean,
            tasks: Int,
            unread: Boolean,
            alive: Boolean,
        ): SessionStatus? =
            when {
                waiting -> SessionStatus(Activity.Waiting, tasks)
                busy -> SessionStatus(Activity.Answering, tasks)
                tasks > 0 -> SessionStatus(Activity.Background, tasks)
                unread -> SessionStatus(Activity.Unread)
                alive -> SessionStatus(Activity.Idle)
                else -> null
            }
    }
}

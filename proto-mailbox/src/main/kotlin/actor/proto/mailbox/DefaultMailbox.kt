package actor.proto.mailbox

import java.util.concurrent.atomic.AtomicInteger

private val emptyStats = arrayOf<MailboxStatistics>()

class DefaultMailbox(private val systemMessages: MailboxQueue, private val userMailbox: MailboxQueue, private val stats: Array<MailboxStatistics> = emptyStats) : Mailbox {
    private val status: AtomicInteger = AtomicInteger(MailboxStatus.Idle)
    private lateinit var dispatcher: Dispatcher
    private lateinit var invoker: MessageInvoker
    private var suspended: Boolean = false

    override fun postUserMessage(msg: Any) {
        userMailbox.push(msg)
        for (stats in stats) stats.messagePosted(msg)
        schedule()
    }

    override fun postSystemMessage(msg: Any) {
        systemMessages.push(msg)
        for (stats in stats) stats.messagePosted(msg)
        schedule()
    }

    override fun registerHandlers(invoker: MessageInvoker, dispatcher: Dispatcher) {
        this.invoker = invoker
        this.dispatcher = dispatcher
    }

    override fun start() {
        for (stats in stats) stats.mailboxStarted()
    }

    private suspend fun run(){

    }

    private fun schedule() {
        val wasIdle = status.compareAndSet(MailboxStatus.Idle, MailboxStatus.Busy)
        if (wasIdle) {
            dispatcher.schedule {
                var msg: Any? = null
                try {
                    for (i in 0 until dispatcher.throughput) {
                        msg = systemMessages.pop()
                        if (msg != null) {
                            when (msg) {
                                is SuspendMailbox -> suspended = true
                                is ResumeMailbox -> suspended = false
                            }
                            invoker.invokeSystemMessage(msg as SystemMessage)
                            for (stat in stats) stat.messageReceived(msg)
                        }
                        if (!suspended) {
                            msg = userMailbox.pop()
                            if (msg == null) break
                            else {
                                invoker.invokeUserMessage(msg)
                                for (stat in stats) stat.messageReceived(msg)
                            }
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (msg != null) invoker.escalateFailure(e, msg)
                }

                status.set(MailboxStatus.Idle)
                if (systemMessages.hasMessages || (!suspended && userMailbox.hasMessages)) {
                    schedule()
                } else {
                    for (stat in stats) stat.mailboxEmpty()
                }
            }
        }
    }
}



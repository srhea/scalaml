import java.nio.channels._
import java.nio.channels.SelectionKey._
import scala.collection.mutable._
import scala.collection.JavaConversions._

object MainLoop {
    class Timer(val when: Long, val seq: Long, val f: () => Unit)
    extends java.lang.Comparable[Timer] {
        override def compareTo(that: Timer): Int = {
            val c = this.when.compare(that.when)
            if (c == 0) this.seq.compare(that.seq) else c
        }
    }
}

class MainLoop {

    private val selector = Selector.open()
    private val callbacks = new HashMap[SelectableChannel,HashMap[Int,()=>Unit]]
    private val ops = Array(OP_ACCEPT, OP_CONNECT, OP_READ, OP_WRITE)

    // I suspect that most timers are canceled, or they're infrequent.  If they're infrequent,
    // then set time doesn't matter.  If they're canceled, then cancel time does matter.  So
    // I optimize for cancel time by using a sorted set.  (If I were optimizing for set time,
    // I'd use a priority queue.)
    var timers = new java.util.TreeSet[MainLoop.Timer]
    // Use a sequence number to guarantee that timers are fired in the order that they're set.
    var seq = 0L
    val lock: AnyRef = new Object

    def setChannel(ch: SelectableChannel, op: Int, f: () => Unit) {
        var skey = ch.keyFor(selector)
        if (skey == null)
            skey = ch.register(selector, op)
        else if ((skey.interestOps & op) == 0)
            skey.interestOps(skey.interestOps | op)
        callbacks.getOrElseUpdate(ch, new HashMap[Int,()=>Unit])(op) = f
    }

    def getChannel(ch: SelectableChannel, op: Int): () => Unit = {
        var skey = ch.keyFor(selector)
        if (skey != null && (skey.interestOps & op) != 0)
            callbacks(ch)(op)
        else
            null
    }

    def clrChannel(ch: SelectableChannel, op: Int) {
        var skey = ch.keyFor(selector)
        if (skey != null && (skey.interestOps & op) != 0) {
            skey.interestOps(skey.interestOps & ~op)
            if (skey.interestOps == 0)
                callbacks.remove(ch)
            else
                callbacks(ch).remove(op)
        }
    }

    // setTimer and clrTimer are safe to call from any thread.  Once run() has
    // been called, all other functions in this class should only be called
    // from within the callbacks that are called from within run itself.  You
    // can use setTimer with a delay of 0 as a sort of mailbox abstraction to
    // communicate between threads.
    def setTimer(delayMillis: Long, f: () => Unit): MainLoop.Timer = {
        val now = java.lang.System.currentTimeMillis
        var soonest = false
        var timer: MainLoop.Timer = null
        lock.synchronized {
            timer = new MainLoop.Timer(now + delayMillis, seq, f)
            soonest = timers.isEmpty || timer.when < timers.head.when
            timers.add(timer)
            seq += 1
        }
        if (soonest)
            selector.wakeup
        timer
    }

    def clrTimer(timer: MainLoop.Timer) {
        lock.synchronized {
            require(timers.contains(timer))
            timers.remove(timer)
        }
    }

    private def runTimers(now: Long): Long = {
        while (true) {
            var timer: MainLoop.Timer = null
            lock.synchronized {
                if (timers.isEmpty)
                    return if (callbacks.isEmpty) -1 else 0
                else {
                    val i = timers.iterator
                    val head = i.next()
                    if (head.when <= now) {
                        timer = head
                        i.remove()
                    }
                    else {
                        assert(head.when > now)
                        return head.when - now
                    }
                }
            }
            if (timer != null)
                timer.f() // Don't hold lock while calling timer.
        }
        assert(false, "unreachable")
        return -1
    }

    private def runCallbacks() {
        for (skey <- selector.selectedKeys) {
            if (callbacks.contains(skey.channel)) {
                val map = callbacks(skey.channel)
                for (op <- ops) {
                    if (skey.isValid && (skey.readyOps & op) != 0 && map.contains(op))
                        map(op)()
                }
            }
        }
    }

    def run() {
        while (true) {
            val now = java.lang.System.currentTimeMillis
            val timeout = runTimers(now)
            if (timeout < 0)
                return
            selector.select(timeout)
            if (!callbacks.isEmpty)
                runCallbacks()
        }
    }
}

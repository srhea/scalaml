import java.nio.channels._
import java.nio.channels.SelectionKey._
import scala.collection.mutable._
import scala.collection.immutable.TreeSet
import scala.collection.JavaConversions._

class MainLoop {

    private val selector = Selector.open()
    private val callbacks = new HashMap[SelectableChannel,HashMap[Int,()=>Unit]]
    private val ops = Array(OP_ACCEPT, OP_CONNECT, OP_READ, OP_WRITE)

    class Timer(val when: Long, val seq: Long, val f: () => Unit)
    class TimerOrder extends Ordering[Timer] {
        def compare(x: Timer, y: Timer): Int = {
            val c = x.when.compare(y.when)
            if (c == 0) x.seq.compare(y.seq) else c
        }
    }
    // I suspect that most timers are canceled, or they're infrequent.  If they're infrequent,
    // then set time doesn't matter.  If they're canceled, then cancel time does matter.  So
    // I optimize for cancel time by using a sorted set.  (If I were optimizing for set time,
    // I'd use a priority queue.)
    var timers = new TreeSet[Timer]()(new TimerOrder)
    // Use a sequence number to guarantee that timers are fired in the order that they're set.
    var seq = 0L
    val lock: AnyRef = new Object

    def setChannel(ch: SelectableChannel, op: Int, f: () => Unit) {
        var skey = ch.keyFor(selector)
        if (skey == null)
            skey = ch.register(selector, op)
        else {
            require((skey.interestOps & op) == 0)
            skey.interestOps(skey.interestOps | op)
        }
        callbacks.getOrElseUpdate(ch, new HashMap[Int,()=>Unit])(op) = f
    }

    def clrChannel(ch: SelectableChannel, op: Int) {
        var skey = ch.keyFor(selector)
        require(skey != null)
        require((skey.interestOps & op) != 0)
        skey.interestOps(skey.interestOps & ~op)
        if (skey.interestOps == 0)
            callbacks.remove(ch)
        else
            callbacks(ch).remove(op)
    }

    def setTimer(delayMillis: Long, f: () => Unit): Timer = {
        val now = java.lang.System.currentTimeMillis
        var soonest = false
        var timer: Timer = null
        lock.synchronized {
            timer = new Timer(now + delayMillis, seq, f)
            soonest = timers.isEmpty || timer.when < timers.head.when
            timers = timers + timer
            seq += 1
        }
        if (soonest)
            selector.wakeup
        timer
    }

    def clrTimer(timer: Timer) {
        lock.synchronized {
            require(timers.contains(timer))
            timers = timers - timer
        }
    }

    private def runTimers(now: Long): Long = {
        while (true) {
            var timer: Timer = null
            lock.synchronized {
                if (timers.isEmpty)
                    return if (callbacks.isEmpty) -1 else 0
                else if (timers.head.when <= now) {
                    timer = timers.head
                    timers = timers - timer
                }
                else {
                    assert(timers.head.when > now)
                    return timers.head.when - now
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

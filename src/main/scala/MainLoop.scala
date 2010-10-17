import java.nio.channels._
import java.nio.channels.SelectionKey._
import scala.collection.mutable._
import scala.collection.JavaConversions._

class MainLoop {

    private val selector = Selector.open()
    private val callbacks = new HashMap[SelectableChannel,HashMap[Int,()=>Unit]]
    private val ops = Array(OP_ACCEPT, OP_CONNECT, OP_READ, OP_WRITE)

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

    def setTimer(delayMillis: Long, f: () => Unit): AnyRef = {
        return new Object
        // TODO
    }

    def clrTimer(token: AnyRef) {
        // TODO
    }

    def runOnce() {
        selector.select()
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
}

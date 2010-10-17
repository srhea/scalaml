import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.nio.channels._
import java.nio.channels.SelectionKey._
import java.net._
import java.util.Random

class MainLoopSpec extends FlatSpec with ShouldMatchers {
    val mainloop = new MainLoop
    val ssch = ServerSocketChannel.open()
    val ssock = ssch.socket
    var port = 0

    "a channel that hasn't been set" should "throw an exception on clr" in {
        val ch = SocketChannel.open
        evaluating { mainloop.clrChannel(ch, OP_READ) } should produce [IllegalArgumentException]
    }

    "a channel that's already been set" should "throw an exception on a second set" in {
        val ch = SocketChannel.open
        ch.configureBlocking(false)
        mainloop.setChannel(ch, OP_READ, () => ())
        evaluating {
            mainloop.setChannel(ch, OP_READ, () => ())
        } should produce [IllegalArgumentException]
    }

    "a server socket" should "be able to set accept" in {
        while (port == 0) {
            port = 10000 + (new Random).nextInt(10000)
            try { ssock.bind(new InetSocketAddress(port)) } catch { case _ => port = 0 }
        }
        ssch.configureBlocking(false)
        mainloop.setChannel(ssch, OP_ACCEPT, { () =>
            val ch = ssch.accept()
            if (ch != null)
                ch.close()
            mainloop.clrChannel(ssch, OP_ACCEPT)
        })
    }

    "a client socket" should "be able to connect" in {
        val ch = SocketChannel.open
        ch.configureBlocking(false)
        ch.connect(new InetSocketAddress("127.0.0.1", port))
        var connected = false
        mainloop.setChannel(ch, OP_CONNECT, { () =>
            connected = true
            mainloop.clrChannel(ch, OP_CONNECT)
            ch.close()
        })
        mainloop.runOnce()
        connected should equal (true)
    }
}


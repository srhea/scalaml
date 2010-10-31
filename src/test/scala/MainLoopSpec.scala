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

    "a channel that hasn't been set" should "not throw an exception on clr" in {
        val ch = SocketChannel.open
        mainloop.clrChannel(ch, OP_READ)
        mainloop.getChannel(ch, OP_READ) should equal (null)
    }

    "a channel that's already been set twice" should "use the newer callback" in {
        val ch = SocketChannel.open
        val f = () => println("foo")
        val g = () => println("bar")
        ch.configureBlocking(false)
        mainloop.setChannel(ch, OP_READ, f)
        mainloop.setChannel(ch, OP_READ, g)
        mainloop.getChannel(ch, OP_READ) should equal (g)
        mainloop.clrChannel(ch, OP_READ)
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
        mainloop.run()
        connected should equal (true)
    }

    "a cleared timer" should "not get fired" in {
        var fired = false
        val timer = mainloop.setTimer(0, { () => fired = true })
        mainloop.clrTimer(timer)
        mainloop.run()
        fired should equal (false)
    }

    "a cleared timer" should "not be cleared again"  in {
        val timer = mainloop.setTimer(0, { () => () })
        mainloop.clrTimer(timer)
        evaluating { mainloop.clrTimer(timer) } should produce [IllegalArgumentException]
    }

    "set timers" should "get fired in order" in {
        var order: List[Int] = Nil
        mainloop.setTimer(0, { () => order = 1 :: order })
        mainloop.setTimer(0, { () => order = 2 :: order })
        mainloop.run()
        order should equal (2 :: 1 :: Nil)
    }

    "a firing timer" should "not deadlock if it sets another timer" in {
        mainloop.setTimer(0, { () => mainloop.setTimer(10, () => ()) })
        mainloop.run()
    }

    "a new timer" should "interrupt a blocking select" in {
        val mainloop2 = new MainLoop
        val ch = SocketChannel.open
        val ch2 = SocketChannel.open
        ch.configureBlocking(false)
        ch2.configureBlocking(false)
        mainloop.setChannel(ch, OP_READ, () => ())
        mainloop2.setChannel(ch2, OP_READ, () => ())
        val t = new Thread { override def run() { mainloop.run() } }
        t.start()
        mainloop.setTimer(30, { () =>
            mainloop.clrChannel(ch, OP_READ)
            mainloop2.setTimer(30, { () =>
                mainloop2.clrChannel(ch2, OP_READ)
            })
        })
        mainloop2.run
        t.join()
    }
}


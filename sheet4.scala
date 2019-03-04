import io.threadcso._
import scala.language.postfixOps
import scala.collection.mutable._

object SyncMonitor {

	/*
	Doesn't work as it is supposed to, the pairing is wrong. I think the issue is passing the identity
	from a process that is waiting to a process that has just arrived uniquely, but I don't know how to
	fix it.
	*/

	val n = 10

	class Server {

		private val monitor = new Monitor

		private val menSignal = monitor.newCondition
		private val womenSignal = monitor.newCondition

		private var menWaiting = 0
		private var womenWaiting = 0

		private var mTemp = ""
		private var wTemp = ""

		def manSync(me: String): String = monitor.withLock{
			if(womenWaiting != 0) {
				val res = wTemp
				mTemp = me
				womenSignal.signal()
				res
			}
			else {
				menWaiting += 1
				mTemp = me
				menSignal.await()
				menWaiting -= 1
				val res = wTemp
				res
			}
		}

		def womanSync(me: String): String = monitor.withLock{
			if(menWaiting != 0) {
				val res = mTemp
				wTemp = me
				menSignal.signal()
				res
			}
			else {
				womenWaiting += 1
				wTemp = me
				womenSignal.await()
				womenWaiting -= 1
				val res = mTemp
				res
			}
		}
	}

	def man(me: String, server: Server) = proc("man") {
		val paired = server.manSync(me)
		println(me+" paired with "+paired)
	}

	def woman(me: String, server: Server) = proc("woman") {
		val paired = server.womanSync(me)
		println(me+" paired with "+paired)
	}

	def main(args: Array[String]) = {
		val server = new Server
		val men = || (for (i <- 0 until n) yield man("m" ++ i.toString(), server))
		val women = || (for (i <- 0 until n) yield woman("w" ++ i.toString(), server))
		run(men || women)
	}

}

object Trees  {
	
	abstract class IntTree
	case class Leaf(n: Int) extends IntTree
	case class Branch(l: IntTree, r: IntTree) extends IntTree


	def worker(tree: IntTree, parentChan: Chan[Int]): PROC = proc("worker") {
		tree match { 
			case l: Leaf => parentChan!(l.n);
		    case b: Branch => {
				val leftChan = OneOne[Int]
				val rightChan = OneOne[Int]
				val leftWorker = worker(b.l, leftChan)
				val rightWorker = worker(b.r, rightChan)
				(leftWorker || rightWorker).fork
				var leftResult = leftChan?;
				var rightResult = rightChan?;
				parentChan!(leftResult + rightResult)
			}
		}
	}

	def main(args: Array[String]) = {
		val returnChan = OneOne[Int]
		val tree = Branch(Branch(Leaf(1),Leaf(2)),Branch(Leaf(3),Leaf(4)))
		val start = worker(tree, returnChan)
		start.fork
		println(returnChan?)
	}	

}

object SemaphoreResources {

	val n = 10

	def work = Thread.sleep(scala.util.Random.nextInt(2000))
	def rest = Thread.sleep(scala.util.Random.nextInt(2000))

	class Server {

		var value = 0
		val mutex = MutexSemaphore()
		val free = MutexSemaphore()

		def enter(id: Int) {
			free.down
			mutex.down
			println("Process "+id.toString+" started working when total was "+(value).toString+".")
			value += id
			if(value % 3 == 0) free.up
			mutex.up
		}

		def leave(id: Int) {
			mutex.down
			println("Process "+id.toString+" finished working.")
			value -= id
			if(value % 3 == 0) free.up
			mutex.up
		}

	}

	def worker(me: Int, server: Server) = proc("worker") {
		repeat {
			server.enter(me)
			work
			server.leave(me)
			rest
			/*
			The print statements are in the server object, as if they were in the worker process
			the order of them could be wrong because of interleaving.
			*/
		}
	}

	def main(args: Array[String]) = {
		val server = new Server
		run(|| (for (i <- 0 until n) yield worker(i, server)))
	}

}

object BoundedBuffer {

	val n = 5

	class BoundedBuffer(n: Int) {

		val queue = new Queue[Int]()
		val empty = CountingSemaphore(0)
		val full = CountingSemaphore(n)
		val mutex = MutexSemaphore()

		def put(n:Int) = {
			full.down
			mutex.down
			empty.up
			queue.enqueue(n)
			println("Item put, lenght: "+queue.length.toString)
			mutex.up
		}

		def get: Int = {
			empty.down
			mutex.down
			full.up
			var n = queue.dequeue()
			mutex.up
			n
		}

	}

	def putter(x: Int, buff: BoundedBuffer) = proc("putter"){
		buff.put(x)
	}

	def getter(buff: BoundedBuffer) = proc("getter") {
		println(buff.get)
	}

	def main(args: Array[String]) {
		val buff = new BoundedBuffer(n)
		(|| (for (i <- 0 until 2*n) yield putter(i, buff))).fork
		Thread.sleep(2000)
		(|| (for (i <- 0 until 2*n) yield getter(buff))).fork
	}

}

object SemaphoreSync {

	val n = 30

	class Server {

		private val menQueue = new Queue[String]()
		private val womenQueue = new Queue[String]()

		private val mMutex = MutexSemaphore()
		private val wMutex = MutexSemaphore()
		private val menAvailable = SignallingSemaphore()
		private val womenAvailable = SignallingSemaphore()

		private var wTemp = ""
		private var mTemp = ""

		def manSync(me: String): String = {
			mMutex.down
			mTemp = me
			menAvailable.up
			womenAvailable.down
			val res = wTemp
			wMutex.up
			res
		}

		def womanSync(me: String): String = {
			wMutex.down
			wTemp = me
			womenAvailable.up
			menAvailable.down
			val res = mTemp
			mMutex.up
			res
		}

	}

	def man(me: String, server: Server) = proc("man") {
		val paired = server.manSync(me)
		println(me+" paired with "+paired)
	}

	def woman(me: String, server: Server) = proc("woman") {
		val paired = server.womanSync(me)
		println(me+" paired with "+paired)
	}

	def main(args: Array[String]) = {
		val server = new Server
		val men = || (for (i <- 0 until n) yield man("m" ++ i.toString(), server))
		val women = || (for (i <- 0 until n) yield woman("w" ++ i.toString(), server))
		run(men || women)
	}

}

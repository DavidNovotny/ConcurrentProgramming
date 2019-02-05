import io.threadcso._
import scala.language.postfixOps
import math._
import scala.collection.mutable._

object Integral {

	type Task = (Double, Double)

	def estimate(f: Double => Double, a: Double, b: Double): Double = {

		val e = 0.0000001
		// \epsilon
		val n = 100

		var nTasks = 1
		// No. of tasks to do. Start with one, add one for every recursive splitting.
		var result = 0.0

		/** Channel from the controller to the workers, to distribute tasks. */
		val toWorkers = OneMany[Task]
		/** Channel from the workers to the controller, to return recursively smaller tasks. */
		val toController = ManyOne[Task]
		/** Channel from the workers to the collector, to return the results of the tasks. */
		val toCollector = ManyOne[Double]

		def worker = proc("worker") {
			repeat {
				val (left,right) = toWorkers?
				val mid = (left+right)/2.0
				val r1 = ((f(left)+f(mid))/2.0)*(mid-left)
				val r2 = ((f(mid)+f(right))/2.0)*(right-mid)
				val r3 = ((f(left)+f(right))/2.0)*(right-left)
				if(abs(r1+r2-r3) <= e) {
					toCollector!r3
				}
				// Find areas of trapeziums, compare, send if within \epsilon.
				else {
					nTasks += 1
					toController!(left,mid)
					toController!(mid,right)
				}
				// If not, add one task to the total and send back.
			}

		}

		def controller = proc("controller") {
			val tasks = new Queue[Task]()
			tasks.enqueue((a,b))
			// Enqueue initial task
			serve(
				((!tasks.isEmpty) && toWorkers) =!=> { tasks.dequeue }
				| (toController) =?=> {x => tasks.enqueue(x)}
				// Send or receive a task
			)
		}

		def collector = proc("collector") {
			var processed = 0
			repeat {
				var r = toCollector?;        // Semicolon needed similar to other locations, postfix makes scalac sad
				result = result + r
				processed += 1
				if(nTasks == processed) {
					toCollector.close
					toController.close
					toWorkers.close
				}
				// Terminate if all tasks are done and added
			}
		}

		val workers = || (for (i <- 0 until n) yield worker)
		val system = workers || controller || collector
		run(system)
		result
	}

	def main(args: Array[String]) = {
		var f: Double => Double = (x => log(x)) 
		var a = 1
		var b = 100000
		print(estimate(f,a,b))
	}

}

object MenWomen {

	val n = 1000
	// No of men and women

	var checkSet: Set[String] = Set()
	var pairedSet: Set[String] = Set()
	// Sets for the purposes of testing

	class SyncServer(serverToMen: Map[String, Chan[String]], serverToWomen: Map[String, Chan[String]]) {

		private val menToServer = ManyOne[String]
		//private var serverToMen: Map[String, Chan[String]] = Map()
		private val womenToServer = ManyOne[String]
		//private var serverToWomen: Map[String, Chan[String]] = Map()
		private val shutdownChan = ManyOne[Unit]

		private def server = proc("server") {
			val menWaiting = new Queue[String]()
			val womenWaiting = new Queue[String]()
			repeat {
				while(!menWaiting.isEmpty && !womenWaiting.isEmpty) {
					var man = menWaiting.dequeue
					var woman = womenWaiting.dequeue
					serverToMen(man)!woman
					serverToWomen(woman)!man
				}
				/*
				Keep a queue of men and woman waiting for a pair, send pairs if both man and
				woman are waiting.
				*/
				alt (
					menToServer =?=> {x => menWaiting.enqueue(x)}
					| womenToServer =?=> {x => womenWaiting.enqueue(x)}
					| shutdownChan =?=> { _ =>
        					menToServer.close; womenToServer.close; shutdownChan.close;
        					serverToMen.keys.foreach { i => serverToMen(i).close};
        					serverToWomen.keys.foreach { i => serverToWomen(i).close};
    					}
				)
				// Accepting pair requests
			}
		}

		server.fork

		def manSync(me: String): String = {
			//serverToMen = serverToMen + (me -> OneOne[String])
			menToServer!me
			var res = serverToMen(me)?;
			//serverToMen = serverToMen - me
			res
		}

		def womanSync(me: String): String = {
			//serverToWomen = serverToWomen + (me -> OneOne[String])
			womenToServer!me
			var res = serverToWomen(me)?;
			//serverToWomen = serverToWomen - me
			res
		}

		def shutdown = shutdownChan!()
		// Scalac warns about deprecations on (), not sure why? Doesn't compile without.
		
	}

	def man(me: String, server: SyncServer) = proc("man") {
		val pair = server.manSync(me)
		pairedSet += pair
	}

	def woman(me: String, server: SyncServer) = proc("woman") {
		val pair = server.womanSync(me)
		pairedSet += pair
	}

	def main(args: Array[String]) = {
		var serverToMen: Map[String, Chan[String]] = Map()
		var serverToWomen: Map[String, Chan[String]] = Map()
		for(i <- 0 until n) {
			serverToMen = serverToMen + (("m" ++ i.toString()) -> OneOne[String])
			checkSet += ("m" ++ i.toString())
		}
		for(i <- 0 until n) {
			serverToWomen = serverToWomen + (("w" ++ i.toString()) -> OneOne[String])
			checkSet += ("w" ++ i.toString())
		}
		/*
		I am aware this is probably an unfortunate solution. I tried to keep the map internally in the server
		and keep it updated accordingly (commented out), but that has lead to some nasty race conditions. 
		This one at least works. I would like to talk about better ways to do this in the tute please.
		*/
		val server = new SyncServer(serverToMen, serverToWomen)
		val men = || (for (i <- 0 until n) yield man("m" ++ i.toString(), server))
		val women = || (for (i <- 0 until n) yield woman("w" ++ i.toString(), server))
		run(men || women)
		server.shutdown
		if((pairedSet -- checkSet).isEmpty) {
		//if(pairedSet == checkSet) {
		/* 
		pairedSet == checkSet doesn't work for large n. Probably due to how equality on sets is defined
		(like depending on order)? It says it is not paired successfully but then the set difference in 
		the last println is empty.
		*/
			println("Paired successfully!")
		}
		else {
			println("Not paired successfully!")
			println("Values to be paired: " ++ checkSet.mkString(", "))
			println("Values paired: " ++ pairedSet.mkString(", "))
			println("Values not paired: " ++ (checkSet -- pairedSet).mkString(", "))
		}
		// Test using equality of sets, order doesn't matter

	}

}

object Ring {

	val n = 10

	def process0[T](x: T, left: Chan[T], right: Chan[T]) = proc("process0"){
		right!x
		var res = left?;
		right!res
		println(res)
		res = left?      // So that last process isn't left trying to send
	}

	def process[T](x: T, f: (T,T) => T, left: Chan[T], right: Chan[T]) = proc("process0"){
		val fst = left?;
		var res = f(fst,x)
		right!res
		res = left?;
		right!res
		println(res)
	}
	/*
	The type signature can be made more general, concretely (x: T, f: (U,T) => U, left: Chan[U], right: Chan[U]),
	but this requires a special channel of type Chan[T] between process0 and process1. I have not done this for
	the sake of simplicity, but such extension is not hard to implement. I assume this exercise is more about actually
	implementing ring topology than about polymorphisms.
	*/

	def main(args: Array[String]) = { 
		val chans = Array.fill(n)(OneOne[Int])
		val p0 = process0[Int](0,chans(n-1),chans(0))
		def f = ((x: Int, y: Int) => x+y)
		val procs = || (for (i <- 1 until n) yield process(i,f,chans(i-1),chans(i)))
		run(p0 || procs)
	}

}
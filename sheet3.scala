import io.threadcso._
import scala.language.postfixOps
import math._

object PrefixSums {

	val n = 100
	val barrier = Barrier(n)
	var a = new Array[Int](n)
	
	def summer(me: Int) = proc("summer") {
		// Each summer writes to one of the entries
		var r = 1
		var s = 0
		while(r < n) {
			if(r <= me) {
				s = a(me-r)
			}
			else {
				s = 0
			}
			// Sync before and after writing
			barrier.sync()
			a(me) += s
			barrier.sync()
			r = r*2
		}
	}

	def main(args: Array[String]) = {
		for(i <- 0 until n) {
			a(i) = i
		}
		/*
		Note that this rewrites the original array. If we were to implement this in a function,
		we should create a new array for this and copy all the elements.
		*/
		run(|| (for (i <- 0 until n) yield summer(i)))
		println(a.mkString(", "))
	}
}

object Toroid {

	/*
	THROWS RUNTIME ERROR
	*/
	
	val n = 20
	val barrier = Barrier(n*n)

	def worker(i: Int, j: Int, x: Int, readUp: ?[Int], writeUp: ![Int], readRight: ?[Int], writeRight: ![Int]) = proc {
		var curri = i
		var currj = j
		var receivedx = 0
		var currx = x
		val results = new Array[Array[Int]](n)

		for(k <- 0 until n) {
			results(k) = new Array[Int](n)
		}

		for(k <- 0 until n) {
			for(l <- 0 until n) {
				/*
				Go all the way around the torus.
				*/
				alt(
					writeRight =!=> { currx }
					| readRight =?=> { y => receivedx = y }
				)
				barrier.sync
				alt(
					writeRight =!=> { currx }
					| readRight =?=> { y => receivedx = y }
				)
				barrier.sync
				currx = receivedx
				currj = (currj + 1) % n
				results(curri)(currj) = currx
			}
			/*
			And then move everything one tile up.
			*/
			curri = (curri + 1) % n
			alt(
				writeUp =!=> { currx }
				| readUp =?=> { y => receivedx = y }
			)
			barrier.sync
			alt(
				writeUp =!=> { currx }
				| readUp =?=> { y => receivedx = y }
			)
			barrier.sync
		}

		if(i == 0 && j == 0) {
		/*
		Let one (arbitrarily the 0,0 one) process print the result.
		*/
			for(k <- 0 until n) {
				println(results(k).mkString(", "))
			}
		}
	}

	def main(args: Array[String]) = {
		// Initiate values
		val initials = new Array[Array[Int]](n)
		for(i <- 0 until n) {
			initials(i) = new Array[Int](n)
			for(j <- 0 until n) {
				initials(i)(j) = i+j
			}
		}
		// Initiate upward channels
		val chansUp = new Array[Array[Chan[Int]]](n)	
		for(i <- 0 until n) {
			chansUp(i) = new Array[Chan[Int]](n)
			for(j <- 0 until n) {
				chansUp(i)(j) = OneOne[Int]
			}
		}
		// Initiate right channels
		val chansRight = new Array[Array[Chan[Int]]](n)	
		for(i <- 0 until n) {
			chansRight(i) = new Array[Chan[Int]](n)
			for(j <- 0 until n) {
				chansRight(i)(j) = OneOne[Int]
			}
		}

		// Initiate workers, all the divs and modulos are confusing but it should work
		val workers = || (for (i <- 0 until n*n) yield worker(i/n, i%n, initials(i/n)(i%n), chansUp(i/n)(i%n), 
																chansUp(((i+1)/n)%n)(i%n), chansRight(i/n)(i%n), chansRight(i/n)((i+1)%n) ))
		run(workers)
		/*
		Throws runtime error, chans can't participate in more than one alt. Is it because both receiving and sending process have it in
		an alt? Don't know how to do it otherwise.
		*/
	}

}

object Sync {

	val n = 11

	def work = Thread.sleep(scala.util.Random.nextInt(2000))
	def rest = Thread.sleep(scala.util.Random.nextInt(2000))

	def worker(me: Int, server: Server) = proc("worker") {
		repeat {
			server.request(me)
			work
			server.leave(me)
			rest
			/*
			The print statements are in the server object, as if they were in the worker process
			the order of them could be wrong because of interleaving.
			*/
		}
	}

	class Server {
		var total = 0

		def request(id: Int) = synchronized{
			while(total % 3 != 0) wait()
			total += id
			println("Process "+id.toString+" started working when total was "+(total-id).toString+".")
		}

		def leave(id: Int) = synchronized{
			total -= id
			println("Process "+id.toString+" finished working.")
			notifyAll()
		}
	}

	def main(args: Array[String]) = {
		val server = new Server
		run(|| (for (i <- 0 until 10) yield worker(i, server)))
	}	


}
import io.threadcso._
import scala.language.postfixOps

object Sorter {

	/*
	The time needed in terms of sequentially ordered messages is O(n). This is only true
	however, if we have at least as many threads available as we need workers.
	*/

	def sorter(l: List[Int]): List[Int] = {
		// Conversion for ease of access
		val arr = l.toArray
		var n = l.length
		// Result will be stored here
		val sorted = new Array[Int](n)
		// Channel for reporting max found back to the server
		val back = ManyOne[Int]

		// Server/collector process
		def server(l: List[Int], right: Chan[Int]) = proc("server") {
			var arr = l.toArray
			// Send out numbers
			for (i <- 0 until n) right!arr(i)
			right.close
			// Listen to resonse
			for (i <- 0 until n) sorted(n-i-1) = back?()
		}

		def worker(left: Chan[Int], right: Chan[Int]) = proc("worker") {
			//Listen to initial value
			var current = left?()
			var max = current
			repeat {
				// Listen and compare
				current = left?()
				if(current > max) {
					right!max
					max = current
				}
				else {
					right!current
				}
			}
			// When left is closed, report result and close right
			back!max
			right.close
		}

		val chans = new Array[Chan[Int]](n+1)
		// Channels for communication, between workers. Last one won't be listened to,
		// but also nothing will be ever sent through it.
		for (i <- 0 to n) {
			chans(i) = OneOne[Int]
		}
		val workers = || (for (i <- 0 until n) yield worker(chans(i),chans(i+1)))
		run(server(l,chans(0)) || workers)
		// Convert back from array to the expected list
		sorted.toList
	}

	/*
	Main function works as a testing rig for an arbitrary function that sorts a list.
	The function generates a random list of numbers from -100 to 100 inclusive and then
	runs the supplied sorting function on different permutations, comparing the result
	to the result of a library function. In case of a failure an exceptions is raised.
	*/
	def main(args: Array[String]) = {
		val n = 30
		val runs = 100
		val nums_ = new Array[Int](n)
		val r = scala.util.Random
		for (i <- 0 until n) nums_(i) = r.nextInt(200)-100
		val nums = nums_.toList.sorted
		// var nums = args.toList().map(_.toInt).sorted; // To use input from command line
		for(i <- 1 to runs) {
			// Shuffle needs a list to work
			var permuted = scala.util.Random.shuffle(nums)
			var sorted = sorter(permuted)
			if (sorted != nums) {
				println("Test FAILED, "+sorted.toString+" != " +nums.toString)
				throw new Exception("Test failed")
			}
		}
		println("Test PASSED")
	}
}

object MatMult {

	/*
	One dot product seems like a reasonable choice of one task.
	*/

	// Form of (index i,index j, column vector i, row vector j)
	type Task = (Int, Int, Array[Int], Array[Int])
	// Form of (index i, index j, value)
	type Result = (Int, Int, Int)

	val toWorkers = OneMany[Task]
	val toCollector = ManyOne[Result]

	// Initialisation, actual value set by main.
	var n = 0

	// Distributes the tasks to workers in a bag-of-tasks fashion.
	def distributor(m1: Array[Array[Int]], _m2: Array[Array[Int]]) = proc("distributor") {
		val m2 = _m2.transpose
		for (i <- 0 until n) {
			for (j <- 0 until n) {
				toWorkers!(i,j,m1(i),m2(j))
			}
		}
		toWorkers.close
	}

	// Keeps on listening to the tasks channel and sends result to the collector, closes when the channel is closed.
	def worker = proc("worker") {
		repeat {
			val (i,j,r1,r2) = toWorkers?()
			var result = 0
			for (i <- 0 until n) {
				result += r1(i)*r2(i)
			}
			toCollector!(i,j,result)
		}
	}

	def mult(m1: Array[Array[Int]],m2: Array[Array[Int]]): Array[Array[Int]] = {
		val result = new Array[Array[Int]](n)
		for (i <- 0 until n) {
			result(i) = new Array[Int](n)
		}

		// Listens to the workers and puts the results in correct place in the matrix.
		// Defined in mult for access to the result array
		def collector = proc("collector") {
			for (k <- 0 until (n*n)) {
				val (i,j,res) = toCollector?()
				result(i)(j) = res
			}
			toCollector.close
		}

		val workers = || (for (i <- 0 until n) yield worker)
		run(distributor(m1, m2) || collector || workers)
		result
	}

	def main(args: Array[String]) = {
		val matrix1 = Array(Array(1,2,3),Array(4,5,6),Array(7,8,9))
		val matrix2 = Array(Array(9,8,7),Array(6,5,4),Array(3,2,1))
		n = matrix1.size
		val matrix3 = mult(matrix1,matrix2)
		print(matrix3.map(_.mkString(",")).mkString("\n"))
	}
}
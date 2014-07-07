package com.kalmanb

import java.io.{ FileOutputStream, PrintWriter }
import scala.concurrent._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.Try

import akka.actor.ActorSystem
import akka.actor._
import akka.stream.actor.ActorConsumer._
import akka.stream.actor._
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import org.reactivestreams.api.Producer

import com.kalmanb.test.TestSpec

class PrimesTest extends TestSpec {

  describe("primes") {
    implicit val system = ActorSystem("Sys")
    val materializer = FlowMaterializer(MaterializerSettings())

    // generate random numbers
    val producer: Producer[Int] =
      Flow(getMoreData)

        // filter prime numbers
        .filter(rnd ⇒ isPrime(rnd))

        // and neighbor +2 is also prime
        .filter(prime ⇒ isPrime(prime + 2))

        // materialize as a producer
        .toProducer(materializer)

    ignore("test primes") {
      // Connect two consumer flows to the producer  
      // Slow
      Flow(producer).foreach { prime: Int ⇒
        println(s"slow: $prime")
        // simulate slow consumer
        Thread.sleep(1000)
      }.consume(materializer)

      // Fast
      Flow(producer).foreach(prime ⇒
        println(s"fast: $prime")).
        consume(materializer)

      // Infinite stream so we have to kill sbt
    }

    it("basic actors") {
      val basic = ActorConsumer[Int](system.actorOf(Props(new Basic())))

      producer.produceTo(basic)

      // Infinite stream so we have to kill sbt
    }

    ignore("with actors") {
      val conn1 = ActorConsumer[Int](system.actorOf(Props(new InFlight("fast", 10))))
      val conn2 = ActorConsumer[Int](system.actorOf(Props(new InFlight("slow", 200))))

      producer.produceTo(conn1)
      producer.produceTo(conn2)

      // Infinite stream so we have to kill sbt
    }
  }

  def isPrime(n: Int): Boolean = {
    if (n <= 1) false
    else if (n == 2) true
    else !(2 to (n - 1)).exists(x ⇒ n % x == 0)
  }

  def getMoreData = () ⇒ ThreadLocalRandom.current().nextInt(1000000)
}

class Basic() extends ActorConsumer {
  override protected def requestStrategy = WatermarkRequestStrategy(10)
  override def receive = {
    case OnNext(msg: Int) ⇒
      println(s"$msg")
  }
}

class InFlight(name: String, delay: Int) extends ActorConsumer {
  implicit val ec = context.dispatcher
  private var inFlight = 0

  override protected def requestStrategy = new MaxInFlightRequestStrategy(10) {
    override def inFlightInternally = inFlight
  }
  override def receive = {
    case OnNext(msg: Int) ⇒
      inFlight += 1
      println(s"$name $msg : $inFlight")

      // Now we do some work - in another actor / future
      Future {
        // take a copy of the sender
        val from = self

        // Simulate some work
        Thread sleep delay

        // Once done release
        from ! 'Done
      }

    case 'Done ⇒
      println(s"$name : done")
      inFlight -= 1
  }
}

package com.kalmanb

import scala.concurrent._
import scala.concurrent.forkjoin.ThreadLocalRandom

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

    val fastProducer: Producer[Int] =
      Flow(getMoreData)
        // materialize as a producer
        .toProducer(materializer)

    // =================================================================
    class Basic() extends ActorConsumer {
      override protected def requestStrategy = WatermarkRequestStrategy(10)
      override def receive = {
        case OnNext(msg: Int) ⇒
          Thread sleep 1000
          println(s"$msg")
      }
    }

    it("EXAMPLE ONE") {
      val conn = ActorConsumer[Int](system.actorOf(Props(new Basic())))

      fastProducer.produceTo(conn)

      // Infinite stream so we have to kill sbt
    }

    // =================================================================
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
          // Ok the future is done - release inFlight
          println(s"$name : done")
          inFlight -= 1
      }
    }

    ignore("EXAMPLE TWO") {
      val conn = ActorConsumer[Int](system.actorOf(Props(new InFlight("conncurrent", 3000))))

      fastProducer.produceTo(conn)

      // Infinite stream so we have to kill sbt
    }

    // generate random numbers
    val slowProducer: Producer[Int] =
      Flow(getMoreData)

        // filter prime numbers
        .filter(rnd ⇒ isPrime(rnd))

        // and neighbor +2 is also prime
        .filter(prime ⇒ isPrime(prime + 2))

        // materialize as a producer
        .toProducer(materializer)

    ignore("EXAMPLE THREE") {
      val conn1 = ActorConsumer[Int](system.actorOf(Props(new InFlight("fast", 10))))
      val conn2 = ActorConsumer[Int](system.actorOf(Props(new InFlight("slow", 200))))

      slowProducer.produceTo(conn1)
      slowProducer.produceTo(conn2)

      // Infinite stream so we have to kill sbt
    }

    ignore("EXAMPLE FOUR") {
      // Connect two consumer flows to the producer  
      // Slow
      Flow(slowProducer).foreach { prime: Int ⇒
        println(s"slow: $prime")
        // simulate slow consumer
        Thread.sleep(1000)
      }.consume(materializer)

      // Fast
      Flow(slowProducer).foreach(prime ⇒
        println(s"fast: $prime")).
        consume(materializer)

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


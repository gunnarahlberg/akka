/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.lang.Thread.sleep
import org.scalatest.BeforeAndAfterAll
import akka.testkit.TestEvent._
import akka.testkit.EventFilter
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import org.multiverse.api.latches.StandardLatch
import akka.testkit.AkkaSpec

class RestartStrategySpec extends AkkaSpec with BeforeAndAfterAll {

  override def beforeAll() {
    app.eventHandler.notify(Mute(EventFilter[Exception]("Crashing...")))
  }

  override def afterAll() {
    app.eventHandler.notify(UnMuteAll)
  }

  object Ping
  object Crash

  "A RestartStrategy" must {

    "ensure that slave stays dead after max restarts within time range" in {
      val boss = actorOf(Props(new Actor {
        protected def receive = { case _ ⇒ () }
      }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 2, 1000)))

      val restartLatch = new StandardLatch
      val secondRestartLatch = new StandardLatch
      val countDownLatch = new CountDownLatch(3)
      val stopLatch = new StandardLatch

      val slave = actorOf(Props(new Actor {

        protected def receive = {
          case Ping  ⇒ countDownLatch.countDown()
          case Crash ⇒ throw new Exception("Crashing...")
        }

        override def postRestart(reason: Throwable) = {
          if (!restartLatch.isOpen)
            restartLatch.open
          else
            secondRestartLatch.open
        }

        override def postStop() = {
          stopLatch.open
        }
      }).withSupervisor(boss))

      slave ! Ping
      slave ! Crash
      slave ! Ping

      // test restart and post restart ping
      assert(restartLatch.tryAwait(10, TimeUnit.SECONDS))

      // now crash again... should not restart
      slave ! Crash
      slave ! Ping

      assert(secondRestartLatch.tryAwait(10, TimeUnit.SECONDS))
      assert(countDownLatch.await(10, TimeUnit.SECONDS))

      slave ! Crash
      assert(stopLatch.tryAwait(10, TimeUnit.SECONDS))
    }

    "ensure that slave is immortal without max restarts and time range" in {
      val boss = actorOf(Props(new Actor {
        def receive = { case _ ⇒ () }
      }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), None, None)))

      val countDownLatch = new CountDownLatch(100)

      val slave = actorOf(Props(new Actor {

        protected def receive = {
          case Crash ⇒ throw new Exception("Crashing...")
        }

        override def postRestart(reason: Throwable) = {
          countDownLatch.countDown()
        }
      }).withSupervisor(boss))

      (1 to 100) foreach { _ ⇒ slave ! Crash }
      assert(countDownLatch.await(120, TimeUnit.SECONDS))
      assert(!slave.isShutdown)
    }

    "ensure that slave restarts after number of crashes not within time range" in {
      val boss = actorOf(Props(new Actor {
        def receive = { case _ ⇒ () }
      }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 2, 500)))

      val restartLatch = new StandardLatch
      val secondRestartLatch = new StandardLatch
      val thirdRestartLatch = new StandardLatch
      val pingLatch = new StandardLatch
      val secondPingLatch = new StandardLatch

      val slave = actorOf(Props(new Actor {

        protected def receive = {
          case Ping ⇒
            if (!pingLatch.isOpen) pingLatch.open else secondPingLatch.open
          case Crash ⇒ throw new Exception("Crashing...")
        }
        override def postRestart(reason: Throwable) = {
          if (!restartLatch.isOpen)
            restartLatch.open
          else if (!secondRestartLatch.isOpen)
            secondRestartLatch.open
          else
            thirdRestartLatch.open
        }

        override def postStop() = {
          if (restartLatch.isOpen) {
            secondRestartLatch.open
          }
        }
      }).withSupervisor(boss))

      slave ! Ping
      slave ! Crash

      assert(restartLatch.tryAwait(10, TimeUnit.SECONDS))
      assert(pingLatch.tryAwait(10, TimeUnit.SECONDS))

      slave ! Ping
      slave ! Crash

      assert(secondRestartLatch.tryAwait(10, TimeUnit.SECONDS))
      assert(secondPingLatch.tryAwait(10, TimeUnit.SECONDS))

      // sleep to go out of the restart strategy's time range
      sleep(700L)

      // now crash again... should and post restart ping
      slave ! Crash
      slave ! Ping

      assert(thirdRestartLatch.tryAwait(1, TimeUnit.SECONDS))

      assert(!slave.isShutdown)
    }

    "ensure that slave is not restarted after max retries" in {
      val boss = actorOf(Props(new Actor {
        def receive = { case _ ⇒ () }
      }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), Some(2), None)))

      val restartLatch = new StandardLatch
      val secondRestartLatch = new StandardLatch
      val countDownLatch = new CountDownLatch(3)
      val stopLatch = new StandardLatch

      val slave = actorOf(Props(new Actor {

        protected def receive = {
          case Ping  ⇒ countDownLatch.countDown()
          case Crash ⇒ throw new Exception("Crashing...")
        }
        override def postRestart(reason: Throwable) = {
          if (!restartLatch.isOpen)
            restartLatch.open
          else
            secondRestartLatch.open
        }

        override def postStop() = {
          stopLatch.open
        }
      }).withSupervisor(boss))

      slave ! Ping
      slave ! Crash
      slave ! Ping

      // test restart and post restart ping
      assert(restartLatch.tryAwait(10, TimeUnit.SECONDS))

      assert(!slave.isShutdown)

      // now crash again... should not restart
      slave ! Crash
      slave ! Ping

      assert(secondRestartLatch.tryAwait(10, TimeUnit.SECONDS))
      assert(countDownLatch.await(10, TimeUnit.SECONDS))

      sleep(700L)

      slave ! Crash
      assert(stopLatch.tryAwait(10, TimeUnit.SECONDS))
      sleep(500L)
      assert(slave.isShutdown)
    }

    "ensure that slave is not restarted within time range" in {
      val restartLatch, stopLatch, maxNoOfRestartsLatch = new StandardLatch
      val countDownLatch = new CountDownLatch(2)

      val boss = actorOf(Props(new Actor {
        def receive = { case t: Terminated ⇒ maxNoOfRestartsLatch.open }
      }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), None, Some(1000))))

      val slave = actorOf(Props(new Actor {

        protected def receive = {
          case Ping  ⇒ countDownLatch.countDown()
          case Crash ⇒ throw new Exception("Crashing...")
        }

        override def postRestart(reason: Throwable) = {
          restartLatch.open
        }

        override def postStop() = {
          stopLatch.open
        }
      }).withSupervisor(boss))

      boss startsMonitoring slave

      slave ! Ping
      slave ! Crash
      slave ! Ping

      // test restart and post restart ping
      assert(restartLatch.tryAwait(10, TimeUnit.SECONDS))

      assert(!slave.isShutdown)

      // now crash again... should not restart
      slave ! Crash

      // may not be running
      slave ! Ping
      assert(countDownLatch.await(10, TimeUnit.SECONDS))

      // may not be running
      slave ! Crash

      assert(stopLatch.tryAwait(10, TimeUnit.SECONDS))

      assert(maxNoOfRestartsLatch.tryAwait(10, TimeUnit.SECONDS))
      sleep(500L)
      assert(slave.isShutdown)
    }
  }
}


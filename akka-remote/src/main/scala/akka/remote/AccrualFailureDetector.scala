/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Map
import scala.annotation.tailrec

/**
 * Implementation of 'The Phi Accrual Failure Detector' by Hayashibara et al. as defined in their paper:
 * [http://ddg.jaist.ac.jp/pub/HDY+04.pdf]
 * <p/>
 * A low threshold is prone to generate many wrong suspicions but ensures a quick detection in the event
 * of a real crash. Conversely, a high threshold generates fewer mistakes but needs more time to detect
 * actual crashes
 * <p/>
 * For example a threshold of:
 *   - 1 => 10% error rate
 *   - 2 => 1% error rate
 *   - 3 => 0.1% error rate -
 * <p/>
 * This means that for example a threshold of 3 => no heartbeat for > 6 seconds => node marked as dead/not available.
 * <p/>
 * Default threshold is 8 (taken from Cassandra defaults), but can be configured in the Akka config.
 */
class AccrualFailureDetector(
  val threshold: Int = 8, // FIXME make these configurable
  val maxSampleSize: Int = 1000) extends FailureDetector {

  final val PhiFactor = 1.0 / math.log(10.0)

  private case class FailureStats(mean: Double = 0.0D, variance: Double = 0.0D, deviation: Double = 0.0D)

  // Implement using optimistic lockless concurrency, all state is represented
  // by this immutable case class and managed by an AtomicReference
  private case class State(
    version: Long = 0L,
    failureStats: Map[InetSocketAddress, FailureStats] = Map.empty[InetSocketAddress, FailureStats],
    intervalHistory: Map[InetSocketAddress, Vector[Long]] = Map.empty[InetSocketAddress, Vector[Long]],
    timestamps: Map[InetSocketAddress, Long] = Map.empty[InetSocketAddress, Long])

  private val state = new AtomicReference[State](State())

  /**
   * Returns true if the connection is considered to be up and healthy
   * and returns false otherwise.
   */
  def isAvailable(connection: InetSocketAddress): Boolean = phi(connection) < threshold

  /**
   * Records a heartbeat for a connection.
   */
  @tailrec
  final def heartbeat(connection: InetSocketAddress) {
    val oldState = state.get

    val latestTimestamp = oldState.timestamps.get(connection)
    if (latestTimestamp.isEmpty) {

      // this is heartbeat from a new connection
      // add starter records for this new connection
      val failureStats = oldState.failureStats + (connection -> FailureStats())
      val intervalHistory = oldState.intervalHistory + (connection -> Vector.empty[Long])
      val timestamps = oldState.timestamps + (connection -> newTimestamp)

      val newState = oldState copy (version = oldState.version + 1,
        failureStats = failureStats,
        intervalHistory = intervalHistory,
        timestamps = timestamps)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) heartbeat(connection) // recur

    } else {
      // this is a known connection
      val timestamp = newTimestamp
      val interval = timestamp - latestTimestamp.get

      val timestamps = oldState.timestamps + (connection -> timestamp) // record new timestamp

      var newIntervalsForConnection =
        oldState.intervalHistory.get(connection).getOrElse(Vector.empty[Long]) :+ interval // append the new interval to history

      if (newIntervalsForConnection.size > maxSampleSize) {
        // reached max history, drop first interval
        newIntervalsForConnection = newIntervalsForConnection drop 0
      }

      val failureStats =
        if (newIntervalsForConnection.size > 1) {

          val mean: Double = newIntervalsForConnection.sum / newIntervalsForConnection.size.toDouble

          val oldFailureStats = oldState.failureStats.get(connection).getOrElse(FailureStats())

          val deviationSum =
            newIntervalsForConnection
              .map(_.toDouble)
              .foldLeft(0.0D)((x, y) ⇒ x + (y - mean))

          val variance: Double = deviationSum / newIntervalsForConnection.size.toDouble
          val deviation: Double = math.sqrt(variance)

          val newFailureStats = oldFailureStats copy (mean = mean,
            deviation = deviation,
            variance = variance)

          oldState.failureStats + (connection -> newFailureStats)
        } else {
          oldState.failureStats
        }

      val intervalHistory = oldState.intervalHistory + (connection -> newIntervalsForConnection)

      val newState = oldState copy (version = oldState.version + 1,
        failureStats = failureStats,
        intervalHistory = intervalHistory,
        timestamps = timestamps)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) heartbeat(connection) // recur
    }
  }

  /**
   * Calculates how likely it is that the connection has failed.
   * <p/>
   * If a connection does not have any records in failure detector then it is
   * considered dead. This is true either if the heartbeat have not started
   * yet or the connection have been explicitly removed.
   * <p/>
   * Implementations of 'Cumulative Distribution Function' for Exponential Distribution.
   * For a discussion on the math read [https://issues.apache.org/jira/browse/CASSANDRA-2597].
   */
  def phi(connection: InetSocketAddress): Double = {
    val oldState = state.get
    val oldTimestamp = oldState.timestamps.get(connection)
    if (oldTimestamp.isEmpty) Double.MaxValue // treat unmanaged connections, e.g. with zero heartbeats, as dead connections
    else {
      PhiFactor * (newTimestamp - oldTimestamp.get) / oldState.failureStats.get(connection).getOrElse(FailureStats()).mean
    }
  }

  /**
   * Removes the heartbeat management for a connection.
   */
  @tailrec
  final def remove(connection: InetSocketAddress) {
    val oldState = state.get

    if (oldState.failureStats.contains(connection)) {
      val failureStats = oldState.failureStats - connection
      val intervalHistory = oldState.intervalHistory - connection
      val timestamps = oldState.timestamps - connection

      val newState = oldState copy (version = oldState.version + 1,
        failureStats = failureStats,
        intervalHistory = intervalHistory,
        timestamps = timestamps)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) remove(connection) // recur
    }
  }

  def recordSuccess(connection: InetSocketAddress, timestamp: Long) {}
  def recordFailure(connection: InetSocketAddress, timestamp: Long) {}
  def notify(event: RemoteLifeCycleEvent) {}
}

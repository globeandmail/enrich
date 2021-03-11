/*
 * Copyright (c) 2013-2019 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow
package enrich
package stream
package sinks

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

import cats.Id
import cats.syntax.either._
import com.snowplowanalytics.snowplow.enrich.stream.model._
import com.snowplowanalytics.snowplow.scalatracker.Tracker
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/** KinesisSink companion object with factory method */
object KinesisSink {
  def validate(client: KinesisAsyncClient, streamName: String): Either[String, Unit] =
    for {
      _ <- streamExists(client, streamName)
             .leftMap(_.getMessage)
             .ensure(s"Kinesis stream $streamName doesn't exist")(_ == true)
    } yield ()

  /**
   * Check whether a Kinesis stream exists
   * @param name Name of the stream
   * @return Whether the stream exists
   */
  private def streamExists(client: KinesisAsyncClient, name: String): Either[Throwable, Boolean] =
    Either.catchNonFatal {
      val describeStreamResult = client.describeStream(DescribeStreamRequest.builder().streamName(name).build()).get()
      val status = describeStreamResult.streamDescription.streamStatus
      status == StreamStatus.ACTIVE || status == StreamStatus.UPDATING
    }
}

/** Kinesis Sink for Scala enrichment */
class KinesisSink(
  client: KinesisAsyncClient,
  backoffPolicy: KinesisBackoffPolicyConfig,
  buffer: BufferConfig,
  streamName: String,
  tracker: Option[Tracker[Id]]
) extends Sink {

  require(KinesisSink.validate(client, streamName).isRight)

  /** Kinesis records must not exceed 1MB */
  private val MaxBytes = 1000000L

  private val maxBackoff = backoffPolicy.maxBackoff
  private val minBackoff = backoffPolicy.minBackoff
  private val randomGenerator = new java.util.Random()

  val ByteThreshold = buffer.byteLimit
  val RecordThreshold = buffer.recordLimit
  val TimeThreshold = buffer.timeLimit
  var nextRequestTime = 0L

  /**
   * Object to store events while waiting for the ByteThreshold, RecordThreshold, or TimeThreshold to be reached
   */
  object EventStorage {
    // Each complete batch is the contents of a single PutRecords API call
    var completeBatches = List[List[(ByteBuffer, String)]]()
    // The batch currently under construction
    var currentBatch = List[(ByteBuffer, String)]()
    // Length of the current batch
    var eventCount = 0
    // Size in bytes of the current batch
    var byteCount = 0

    /**
     * Finish work on the current batch and create a new one.
     */
    def sealBatch(): Unit = {
      completeBatches = currentBatch :: completeBatches
      eventCount = 0
      byteCount = 0
      currentBatch = Nil
    }

    /**
     * Add a new event to the current batch.
     * If this would take the current batch above ByteThreshold bytes,
     * first seal the current batch.
     * If this takes the current batch up to RecordThreshold records,
     * seal the current batch and make a new batch.
     *
     * @param event New event
     */
    def addEvent(event: (ByteBuffer, String)): Unit = {
      val newBytes = event._1.capacity

      if (newBytes >= MaxBytes) {
        val original = new String(event._1.array, UTF_8)
        log.error(s"Dropping record with size $newBytes bytes: [$original]")
      } else {

        if (byteCount + newBytes >= ByteThreshold)
          sealBatch()

        byteCount += newBytes

        eventCount += 1
        currentBatch = event :: currentBatch

        if (eventCount == RecordThreshold)
          sealBatch()
      }
    }

    /**
     * Reset everything.
     */
    def clear(): Unit = {
      completeBatches = Nil
      currentBatch = Nil
      eventCount = 0
      byteCount = 0
    }
  }

  /**
   * Side-effecting function to store the EnrichedEvent
   * to the given output stream.
   *
   * EnrichedEvent takes the form of a tab-delimited
   * String until such time as https://github.com/snowplow/snowplow/issues/211
   * is implemented.
   *
   * This method blocks until the request has finished.
   *
   * @param events List of events together with their partition keys
   * @return whether to send the stored events to Kinesis
   */
  override def storeEnrichedEvents(events: List[(String, String)]): Boolean = {
    val wrappedEvents = events.map(e => ByteBuffer.wrap(e._1.getBytes(UTF_8)) -> e._2)
    wrappedEvents.foreach(EventStorage.addEvent(_))
    if (EventStorage.currentBatch.nonEmpty && System.currentTimeMillis() > nextRequestTime) {
      nextRequestTime = System.currentTimeMillis() + TimeThreshold
      true
    } else
      EventStorage.completeBatches.nonEmpty
  }

  /**
   * Blocking method to send all stored records to Kinesis
   * Splits the stored records into smaller batches (by byte size or record number) if necessary
   */
  override def flush(): Unit = {
    EventStorage.sealBatch()
    // Send events in the order they were received
    EventStorage.completeBatches.reverse.foreach(b => sendBatch(b.reverse))
    EventStorage.clear()
  }

  /**
   * Send a single batch of events in one blocking PutRecords API call
   * Loop until all records have been sent successfully
   * Cannot be made tail recursive (http://stackoverflow.com/questions/8233089/why-wont-scala-optimize-tail-call-with-try-catch)
   *
   * @param batch Events to send
   */
  def sendBatch(batch: List[(ByteBuffer, String)]): Unit =
    if (!batch.isEmpty) {
      log.info(s"Writing ${batch.size} records to Kinesis stream $streamName")
      var unsentRecords = batch
      var backoffTime = minBackoff
      var sentBatchSuccessfully = false
      var attemptNumber = 0
      while (!sentBatchSuccessfully) {
        attemptNumber += 1

        try {
          //TODO: Make the 10 second blocking connection async.
          val results = multiPut(streamName, unsentRecords).records().asScala.toList
          val failurePairs = unsentRecords zip results filter { _._2.errorMessage != null }
          log.info(
            s"Successfully wrote ${unsentRecords.size - failurePairs.size} out of ${unsentRecords.size} records"
          )
          if (failurePairs.nonEmpty) {
            val (failedRecords, failedResults) = failurePairs.unzip
            unsentRecords = failedRecords
            logErrorsSummary(getErrorsSummary(failedResults))
            backoffTime = getNextBackoff(backoffTime)
            log.error(s"Retrying all failed records in $backoffTime milliseconds...")

            val err = s"Failed to send ${failurePairs.size} events"
            val putSize: Long = unsentRecords.foldLeft(0L)((a, b) => a + b._1.capacity)

            tracker match {
              case Some(t) =>
                SnowplowTracking.sendFailureEvent(
                  t,
                  "PUT Failure",
                  err,
                  streamName,
                  "snowplow-stream-enrich",
                  attemptNumber.toLong,
                  putSize
                )
              case _ => None
            }

            Thread.sleep(backoffTime)
          } else
            sentBatchSuccessfully = true
        } catch {
          case NonFatal(f) =>
            backoffTime = getNextBackoff(backoffTime)
            log.error(s"Writing failed.", f)
            log.error(s"  + Retrying in $backoffTime milliseconds...")

            val putSize: Long = unsentRecords.foldLeft(0L)((a, b) => a + b._1.capacity)

            tracker match {
              case Some(t) =>
                SnowplowTracking.sendFailureEvent(
                  t,
                  "PUT Failure",
                  f.toString,
                  streamName,
                  "snowplow-stream-enrich",
                  attemptNumber.toLong,
                  putSize
                )
              case _ => None
            }

            Thread.sleep(backoffTime)
        }
      }
    }

  private def multiPut(name: String, batch: List[(ByteBuffer, String)]): PutRecordsResponse = {
    val putRecordsRequest = {
      val putRequestBuilder = PutRecordsRequest.builder().streamName(name)
      val putRecordsRequestEntryList = batch.map {
        case (b, s) => PutRecordsRequestEntry.builder().partitionKey(s).data(SdkBytes.fromByteArray(b.array())).build()
      }
      putRequestBuilder.records(putRecordsRequestEntryList.asJava)
      putRequestBuilder.build()
    }
    client.putRecords(putRecordsRequest)
  }.get(10, TimeUnit.SECONDS)

  private[sinks] def getErrorsSummary(badResponses: List[PutRecordsResultEntry]): Map[String, (Long, String)] =
    badResponses.foldLeft(Map[String, (Long, String)]())((counts, r) =>
      if (counts.contains(r.errorCode))
        counts + (r.errorCode -> (counts(r.errorCode)._1 + 1 -> r.errorMessage))
      else
        counts + (r.errorCode -> ((1, r.errorMessage)))
    )

  private[sinks] def logErrorsSummary(errorsSummary: Map[String, (Long, String)]): Unit =
    for ((errorCode, (count, sampleMessage)) <- errorsSummary)
      log.error(
        s"$count records failed with error code ${errorCode}. Example error message: ${sampleMessage}"
      )

  /**
   * How long to wait before sending the next request
   *
   * @param lastBackoff The previous backoff time
   * @return Minimum of maxBackoff and a random number between minBackoff and three times lastBackoff
   */
  private def getNextBackoff(lastBackoff: Long): Long = {
    val offset: Long = (randomGenerator.nextDouble() * (lastBackoff * 3 - minBackoff)).toLong
    val sum: Long = minBackoff + offset
    sum min maxBackoff
  }
}

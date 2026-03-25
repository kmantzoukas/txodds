package com.producer.consumer

import com.producer.consumer.api.WebLinkExtractor
import java.util.concurrent.Executors
import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}

object TxOddsInterview extends App {

  private val mapOfArgs: Map[String, String] =
    args.sliding(2, 2).collect {
      case Array(flag, value) if flag.startsWith("--") =>
        flag.drop(2) -> value
    }.toMap

  private val inputFile = mapOfArgs.getOrElse("input", "input/urls.txt")
  private val outputFile = mapOfArgs.getOrElse("output", "output/result.txt")
  private val numProducers = mapOfArgs.get("producers").map(_.toInt).getOrElse(10)
  private val numConsumers = mapOfArgs.get("consumers").map(_.toInt).getOrElse(5)
  private val queueSize = mapOfArgs.get("queue").map(_.toInt).getOrElse(100)
  private val threadPoolSize = mapOfArgs.get("threads").map(_.toInt).getOrElse(16)

  println(
    s"""
       |Configuration
       |-------------
       |Input file:        $inputFile
       |Output file:       $outputFile
       |Queue size:        $queueSize
       |Num of Producers:  $numProducers
       |Num of Consumers:  $numConsumers
       |Thread pool:       $threadPoolSize
       |""".stripMargin
  )

  private val executor = Executors.newFixedThreadPool(threadPoolSize)
  implicit private val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  try {
    val extractor =
      new WebLinkExtractor(
        queueSize = queueSize,
        numOfProducers = numProducers,
        numOfConsumers = numConsumers,
        inputFile = inputFile,
        outputFile = outputFile
      )

    val producers: Seq[Future[Unit]] = extractor.mkProducers
    val consumers: Seq[Future[Unit]] = extractor.mkConsumers

    Await.result(
      for {
        _ <- Future.sequence(producers)
        _ <- Future.sequence(consumers)
        _ <- extractor.cleanUp()
      } yield (),
      Duration.Inf
    )
  } finally {
    executor.shutdown()
    executor.awaitTermination(20, SECONDS)
  }
}
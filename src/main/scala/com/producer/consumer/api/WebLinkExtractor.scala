package com.producer.consumer.api

import com.producer.consumer.model.{Partition, WebExtract}
import com.producer.consumer.utils.TxOddsUtils.writeLinesToFile
import org.jsoup.nodes.Document

import java.io.{File, PrintWriter}
import java.util.UUID
import java.util.concurrent.{BlockingQueue, CountDownLatch, LinkedBlockingQueue}
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.{Try, Using}

object WebLinkExtractor {
}

class WebLinkExtractor(
                        inputFile: String,
                        outputFile: String,
                        queueSize: Int,
                        numOfProducers: Int,
                        numOfConsumers: Int
                      )(implicit ec: ExecutionContext) {

  require(queueSize > 0, "Queue size must be greater than 0")
  require(numOfProducers > 0, "Number of producers must be greater than 0")
  require(numOfConsumers > 0, "Number of consumers must be greater than 0")

  private val queue: BlockingQueue[WebExtract] = new LinkedBlockingQueue(queueSize)
  private val urls: List[String] = Using(Source.fromFile(inputFile))(source => source.getLines().toList).get
  private val uuid = UUID.randomUUID().toString
  private val outputFolderFile: File = new File(Option(new File(outputFile).getParent).getOrElse("."))
  outputFolderFile.mkdirs()
  private val outputFolder: String = outputFolderFile.getPath
  private val numOfUrlsPerThread: Int = math.max(1, (urls.size + numOfProducers - 1) / numOfProducers)
  private val partitions: Seq[Partition] = urls.grouped(numOfUrlsPerThread).zipWithIndex.toSeq.map {
    case (data, partitionNumber) => Partition(data, partitionNumber)
  }
  private val producerLatch: CountDownLatch = new CountDownLatch(partitions.size)

  def mkProducers: Seq[Future[Unit]] = {
    def producer(id: Int, urls: Seq[String]): Future[Unit] = Future {
      try {
        urls.foreach {
          url =>
            println(s"Producer with id $id is fetching markup for URL: $url")
            val doc: Either[Throwable, Document] = Try(org.jsoup.Jsoup.connect(url).get()).toEither
            while (!queue.offer(WebExtract(url, doc))) {
              val dropped = queue.poll()
              println(s"Producer with id $id failed to add WebExtract to the queue because it is full. Dropping " +
                s"url ${dropped.url} which is the oldest element and trying again.")
            }
        }
      } finally {
        producerLatch.countDown()
      }
    }

    partitions.map {
      case Partition(data, partitionNum) => producer(partitionNum, data)
    }
  }

  def mkConsumers: Seq[Future[Unit]] = {
    def consumer(id: Int): Future[Unit] = Future {
      var keepConsuming = true
      while (keepConsuming) {
        try {
          val outputFileFullPath = new File(outputFolder, s"$uuid-$id").getPath
          // Wrap the poll in an Option to handle the case where it returns null (when the timeout expires)
          // because this is Java
          Option(queue.poll(100, MILLISECONDS)).foreach {

            case WebExtract(url, Right(doc)) =>
              println(s"Consumer with id $id is processing URL: $url")
              val hrefList = doc.select("a[href]").eachAttr("abs:href").asScala.toSeq
              writeLinesToFile(outputFileFullPath, Iterator(s"$url"))
              hrefList.foreach(href => writeLinesToFile(outputFileFullPath, Iterator(s" - $href")))

            case WebExtract(url, Left(error)) =>
              writeLinesToFile(outputFileFullPath, Iterator(s"$url"))
              writeLinesToFile(outputFileFullPath, Iterator(s" - Unable to fetch document for url $url: ${error.getMessage}")
              )
          }
        } catch {
          case e: Exception =>
            println(s"Consumer with id $id failed: ${e.getCause}")
        }
        if (producerLatch.getCount == 0 && queue.isEmpty) keepConsuming = false
      }
    }

    (0 until numOfConsumers).map(consumer)
  }

  def cleanUp(): Future[String] = Future {
    try {
      val files: Array[File] =
        Option(new File(outputFolder).listFiles())
          .getOrElse(Array.empty)
          .filter(_.getName.startsWith(uuid))

      Using.resource(new PrintWriter(new File(outputFile))) { writer =>
        files.foreach { file =>
          println(s"Writing contents of temporary file ${file.getName} to output file $outputFile.")
          Using.resource(Source.fromFile(file)) { source =>
            source.getLines().foreach(writer.println)
          }
        }
      }

      println("All producers and consumers have completed their tasks. Shutting everything down.")
      outputFile
    } finally {
      println("Cleaning up temporary partition files.")
      Option(new File(outputFolder).listFiles())
        .getOrElse(Array.empty)
        .filter(_.getName.startsWith(uuid))
        .foreach(_.delete())
    }
  }

}

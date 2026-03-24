import com.txodds.api.WebLinkExtractor

import java.util.concurrent.Executors
import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}

object TxOddsInterview extends App {

  private val executor = Executors.newFixedThreadPool(16)
  implicit private val ec: ExecutionContext =
    ExecutionContext.fromExecutor(executor)

  try {
    val extractor =
      new WebLinkExtractor(
        queueSize = 100,
        numOfProducers = 10,
        numOfConsumers = 5,
        inputFile = "input/urls.txt",
        outputFile = "output/hrefs-per-url.txt"
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
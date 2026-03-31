package com.producer.consumer

import cats.effect.std.Dequeue
import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import fs2.io.file.{Files, Path}
import org.jsoup.Jsoup._
import org.jsoup.nodes.Document

import scala.jdk.CollectionConverters.CollectionHasAsScala

object MainFS2Streams extends IOApp {

  private case class Result(url: String, hrefs: List[String])

  private case class WebExtract(url: String, maybeDoc: Either[Throwable, Document])

  private def format(result: Result): String = s"${result.url}\n${result.hrefs.distinct.map(s => s" - $s").mkString("\n")}\n"

  private def fetchWebExtract(url: String): IO[WebExtract] = for {
    _ <-
      IO.println(s"Fetching document for url $url")
    maybeDocument <-
      IO.blocking(connect(url).get()).attempt
  } yield WebExtract(url, maybeDocument)

  private def webExtractToHrefs(webExtract: WebExtract): Result = webExtract match {
    case WebExtract(url, Right(doc)) =>
      Result(
        url,
        doc.select("a[href]").eachAttr("abs:href").asScala.toList.distinct
      )
    case WebExtract(url, Left(error)) =>
      Result(url, List(error.getCause.toString))
  }

  private def first(inputFile: String, outputFile: String, levelOfParallelism: Int): IO[Unit] = for {
    _ <- Files[IO].createDirectories(Path(outputFile).parent.getOrElse(Path(".")))
    _ <- Files[IO]
      .readUtf8Lines(Path(inputFile))
      .map(_.trim)
      .filter(_.nonEmpty)
      .parEvalMapUnordered(levelOfParallelism)(fetchWebExtract)
      .map(webExtractToHrefs)
      .debug {
        result =>
          result match {
            case Result(url, hrefs) => s"Found ${hrefs.length} hrefs under url $url"
          }
      }
      .map(format)
      .through(fs2.text.utf8.encode)
      .through(Files[IO].writeAll(Path(outputFile)))
      .compile
      .drain
  } yield ()

  private def second(inputFile: String, outputFile: String, levelOfParallelism: Int): IO[Unit] =
    Dequeue
      .bounded[IO, Option[WebExtract]](100).flatMap {
        q =>

          val producer =
            Files[IO]
              .readUtf8Lines(Path(inputFile))
              .parEvalMapUnordered(levelOfParallelism)(fetchWebExtract)
              .evalMap { we =>
                q.tryOffer(Some(we)).flatMap {
                  if (_)
                    IO.unit
                  else
                    q.tryTake >> q.tryOffer(Some(we))
                }
              }
              .compile
              .drain >> q.offer(None)

          val consumer =
            Stream
              .repeatEval(q.take)
              .unNoneTerminate
              .map(webExtractToHrefs)
              .map(format)
              .through(fs2.text.utf8.encode)
              .through(Files[IO].writeAll(Path(outputFile)))
              .compile
              .drain


          Files[IO].createDirectories(Path(outputFile).parent.getOrElse(Path("."))) >> producer.both(consumer).void
      }

  override def run(args: List[String]): IO[ExitCode] =
    args.sliding(3, 3).toList match {
      case List(List(inputFile, outputFile, levelOfParallelism)) =>
        levelOfParallelism.toIntOption match {
          case Some(parallelism) if parallelism > 0 =>
            second(inputFile, outputFile, parallelism).as(ExitCode.Success)
          case _ =>
            IO.println(s"Invalid parallelism: $levelOfParallelism").as(ExitCode.Error)
        }
    }

}

package com.producer.consumer

import com.producer.consumer.api.WebLinkExtractor
import okhttp3.mockwebserver.{MockResponse, MockWebServer}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Using

class WebLinkExtractorSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = _
  private var server: MockWebServer = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = Files.createTempDirectory("weblinkextractor-spec")
    server = new MockWebServer()
    server.start()
  }

  override protected def afterEach(): Unit = {
    try {
      if (server != null) {
        server.shutdown()
        server = null
      }
    } finally {
      deleteRecursively(tempDir.toFile)
      super.afterEach()
    }
  }

  test("constructor rejects non-positive queue size") {
    val input = createInputFile("urls.txt", Seq("http://www.blahblahblah.com"))
    val output = tempDir.resolve("out.txt").toString

    val ex = intercept[IllegalArgumentException] {
      implicit val ec: ExecutionContext =
        ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

      new WebLinkExtractor(
        inputFile = input,
        outputFile = output,
        queueSize = 0,
        numOfProducers = 2,
        numOfConsumers = 1
      )
    }

    ex.getMessage should include("Queue size must be greater than 0")
  }

  test("constructor rejects non-positive producer count") {
    val input = createInputFile("urls.txt", Seq("http://www.blahblahblah.com"))
    val output = tempDir.resolve("out.txt").toString

    val ex = intercept[IllegalArgumentException] {
      implicit val ec: ExecutionContext =
        ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

      new WebLinkExtractor(
        inputFile = input,
        outputFile = output,
        numOfProducers = 0,
        numOfConsumers = 1,
        queueSize = 10
      )
    }

    ex.getMessage should include("Number of producers must be greater than 0")
  }

  test("constructor rejects non-positive consumer count") {
    val input = createInputFile("urls.txt", Seq("http://www.blahblahblah.com"))
    val output = tempDir.resolve("out.txt").toString

    val ex = intercept[IllegalArgumentException] {
      implicit val ec: ExecutionContext =
        ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

      new WebLinkExtractor(
        inputFile = input,
        outputFile = output,
        numOfConsumers = 0,
        numOfProducers = 1,
        queueSize = 10
      )
    }

    ex.getMessage should include("Number of consumers must be greater than 0")
  }

  test("extracts links from a single valid URL") {
    enqueueHtml(
      """
        |<html>
        |  <body>
        |    <a href="/blah">blah</a>
        |    <a href="https://www.blahblahblah.co.uk/blah">blah</a>
        |  </body>
        |</html>
        |""".stripMargin
    )

    val url = server.url("/blah").toString
    val input = createInputFile("urls.txt", Seq(url))
    val output = tempDir.resolve("hrefs-per-url.txt").toString

    runWebLinkExtractor(input, output, queueSize = 10, numOfProducers = 1, numOfConsumers = 1)

    val lines = readLines(output)
    lines should contain(url)
    lines should contain(s" - ${server.url("/blah")}")
    lines should contain(" - https://www.blahblahblah.co.uk/blah")
  }

  test("writes an error entry for an unreachable URL") {
    val badUrl = "http://localhost:1/not-there"
    val input = createInputFile("urls.txt", Seq(badUrl))
    val output = tempDir.resolve("hrefs-per-url.txt").toString

    runWebLinkExtractor(input, output, queueSize = 10, numOfProducers = 1, numOfConsumers = 1)

    val contents = readLines(output).mkString("\n")
    contents should include(badUrl)
    contents should include("Unable to fetch document for url")
  }

  test("processes multiple URLs and merges all temp files into the final output") {
    enqueueHtml("""<html><body><a href="/sky-1">sky-1</a><a href="/sky-2">sky-2</a></body></html>""")
    enqueueHtml("""<html><body><a href="/guardian-1">guardian-1</a></body></html>""")
    enqueueHtml("""<html><body><a href="/bbc-1">bbc-1</a></body></html>""")

    val urls = Seq(
      server.url("/sky").toString,
      server.url("/guardian").toString,
      server.url("/bbc").toString
    )
    val input = createInputFile("urls.txt", urls)
    val output = tempDir.resolve("hrefs-per-url.txt").toString

    runWebLinkExtractor(input, output, queueSize = 100, numOfProducers = 3, numOfConsumers = 2)

    val contents = readLines(output).mkString("\n")
    contents should include(server.url("/sky").toString)
    contents should include(server.url("/guardian").toString)
    contents should include(server.url("/bbc").toString)
    contents should include(s" - ${server.url("/sky-1")}")
    contents should include(s" - ${server.url("/sky-2")}")
    contents should include(s" - ${server.url("/guardian-1")}")
    contents should include(s" - ${server.url("/bbc-1")}")
  }

  test("cleans up temporary partition files after completion") {
    enqueueHtml("""<html><body><a href="/child">child</a></body></html>""")

    val url = server.url("/page").toString
    val input = createInputFile("urls.txt", Seq(url))
    val output = tempDir.resolve("hrefs-per-url.txt").toString

    runWebLinkExtractor(input, output, queueSize = 10, numOfProducers = 1, numOfConsumers = 2)

    val tempFiles =
      Option(tempDir.toFile.listFiles()).getOrElse(Array.empty)
        .filter(f => f.getName != "urls.txt" && f.getName != "hrefs-per-url.txt")

    tempFiles shouldBe empty
  }

  test("supports output file with no parent by defaulting temp files to current directory") {
    enqueueHtml("""<html><body><a href="/x">x</a></body></html>""")

    val url = server.url("/page").toString
    val input = createInputFile("urls.txt", Seq(url))
    val output = "result.txt"

    runWebLinkExtractor(input, output, queueSize = 10, numOfProducers = 1, numOfConsumers = 1)

    val resultFile = new File("result.txt")
    resultFile.exists() shouldBe true

    val contents = Using.resource(Source.fromFile(resultFile))(_.getLines().mkString("\n"))
    contents should include(url)
    contents should include(server.url("/x").toString)
  }

  test("creates an empty output file when the input file is empty") {
    val input = createInputFile("urls.txt", Seq.empty)
    val output = tempDir.resolve("hrefs-per-url.txt").toString

    runWebLinkExtractor(
      input,
      output,
      queueSize = 10,
      numOfProducers = 5,
      numOfConsumers = 2
    )

    val outputFile = new File(output)
    outputFile.exists() shouldBe true

    val lines = readLines(output)
    lines shouldBe empty
  }

  test("final output file is created even when all URLs fail") {
    val urls = Seq(
      "http://localhost:1/fail-1",
      "http://localhost:1/fail-2"
    )
    val input = createInputFile("urls.txt", urls)
    val output = tempDir.resolve("hrefs-per-url.txt").toString

    runWebLinkExtractor(input, output, queueSize = 10, numOfProducers = 2, numOfConsumers = 2)

    val outFile = new File(output)
    outFile.exists() shouldBe true

    val contents = readLines(output).mkString("\n")
    contents should include("fail-1")
    contents should include("fail-2")
    contents should include("Unable to fetch document for url")
  }

  private def runWebLinkExtractor(
                                   inputFile: String,
                                   outputFile: String,
                                   queueSize: Int,
                                   numOfProducers: Int,
                                   numOfConsumers: Int
                                 ): Unit = {
    val executor = Executors.newFixedThreadPool(8)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    try {
      val extractor = new WebLinkExtractor(
        inputFile = inputFile,
        outputFile = outputFile,
        queueSize = queueSize,
        numOfProducers = numOfProducers,
        numOfConsumers = numOfConsumers
      )

      val program =
        for {
          _ <- Future.sequence(extractor.mkProducers)
          _ <- Future.sequence(extractor.mkConsumers)
          _ <- extractor.cleanUp()
        } yield ()

      Await.result(program, 30.seconds)
    } finally {
      executor.shutdown()
      executor.awaitTermination(20, TimeUnit.SECONDS)
    }
  }

  private def enqueueHtml(body: String): Unit =
    server.enqueue(
      new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)
    )

  private def createInputFile(fileName: String, urls: Seq[String]): String = {
    val path = tempDir.resolve(fileName)
    Files.write(path, urls.asJava, StandardCharsets.UTF_8)
    path.toString
  }

  private def readLines(path: String): Seq[String] =
    Using.resource(Source.fromFile(path))(_.getLines().toSeq)

  private def deleteRecursively(file: File): Unit = {
    if (file == null || !file.exists()) return
    if (file.isDirectory) {
      Option(file.listFiles()).getOrElse(Array.empty).foreach(deleteRecursively)
    }
    file.delete()
    ()
  }
}
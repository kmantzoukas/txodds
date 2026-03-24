package com.txodds.utils

import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.util.Using

object TxOddsUtils {


  /**
   * Writes a sequence of lines to a file. If the file does not exist, it will be created. If the file already exists,
   * the lines will be appended to the end of the file. By default, a new line will be appended after each line,
   * but this can be disabled by setting the appendNewLine parameter to false. Also, passing a list of lines allows
   * for more efficient writing to the file, as it minimizes the number of I/O operations.
   *
   * @param filename          File to write to
   * @param lines             Lines to write
   * @param appendNewLine     Whether to append a new line after each line (default: true)
   * @param charset           Charset to use when writing to the file (default: UTF-8)
   * @param createIfNotExists Whether to create the file if it does not exist (default: CREATE)
   * @param appendContent     Whether to append to the file if it already exists (default: APPEND)
   */
  def writeLinesToFile(
                        filename: String,
                        lines: Iterator[String],
                        charset: Charset = StandardCharsets.UTF_8,
                        createIfNotExists: StandardOpenOption = StandardOpenOption.CREATE,
                        appendContent: StandardOpenOption = StandardOpenOption.APPEND,
                        appendNewLine: Boolean = true): Unit = {
    Using.resource(
      Files.newBufferedWriter(
        Paths.get(filename),
        charset,
        createIfNotExists,
        appendContent
      )
    ) {
      writer =>
        lines.foreach {
          line =>
            writer.write(line)
            if (appendNewLine) writer.newLine()
        }
    }
  }
}

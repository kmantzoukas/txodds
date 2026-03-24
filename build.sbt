scalaVersion := "2.13.18"
name := "txodds"
version := "1.0"
libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0"
libraryDependencies += "org.jsoup" % "jsoup" % "1.22.1"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
libraryDependencies += "com.squareup.okhttp3" % "mockwebserver" % "4.12.0" % Test
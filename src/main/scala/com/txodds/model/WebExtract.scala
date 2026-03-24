package com.txodds.model

import org.jsoup.nodes.Document

case class WebExtract(url: String, doc: Either[Throwable, Document])

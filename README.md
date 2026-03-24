# TxOdds Interview – Web Link Extractor

## Overview

This project is a **concurrent web link extraction tool** written in **Scala**.
It reads a list of URLs from an input file, downloads the corresponding web pages, extracts hyperlinks (`href` values),
and writes the results to an output file.

## How It Works

The application follows a **producer–consumer architecture**:

1. **Producers**

    * Read URLs from an input file.
    * Place them into a shared queue.

2. **Consumers**

    * Retrieve URLs from the queue.
    * Download the webpage content.
    * Parse the HTML and extract hyperlinks.
    * Write the results to the output file.

This design allows multiple URLs to be processed **in parallel**, improving performance when handling larger URL lists.

## Dependencies

Main libraries used in the project:

* **jsoup** – HTML parsing and link extraction
* **scala-parser-combinators** – parsing utilities
* **ScalaTest** – testing framework
* **MockWebServer** – HTTP mocking for tests

All dependencies are defined in `build.sbt`.

## Running the Application

### 1. Clone the repository

```bash
git clone https://github.com/kmantzoukas/txodds.git
cd txodds
```

### 2. Add URLs to process

Edit:

```
input/urls.txt
```

Example:

```
https://www.wikipedia.org
https://www.bbc.com/news
https://www.nasa.gov
https://www.mozilla.org
https://www.reddit.com
https://www.stackoverflow.com
https://www.nationalgeographic.com
https://www.blahblahblah.com
https://www.theguardian.com
https://www.coursera.org
https://www.khanacademy.org
https://www.techcrunch.com
https://www.github.com
https://www.nytimes.com
https://www.w3.org
https://www.cloudflare.com
https://www.nationalarchives.gov.uk
```

**Note:** In the list avove, `https://www.blahblahblah.com` is an intentionally invalid URL to demonstrate error
handling.

### 3. Run the application

```bash
sbt "run --input input/urls.txt --output output/result.txt --producers 20 --consumers 10 --queue 200"
```

## Output

After execution, the results will be written to:

```
output/result.txt
```

Example output format:

```
https://example.com
  - https://example.com/about
  - https://example.com/contact

https://scala-lang.org
  - https://docs.scala-lang.org
  - https://scala-lang.org/download
```

## Running Tests

To run the test suite:

```bash
sbt test
```

## Argument Configuration

The application parameters (such as queue size, producer count, and consumer count) are configured in the main
application entry point and can be adjusted via command-line arguments when running the application. The argument
parsing logic is implemented in the `TxOddsInterview` object, which serves as the main entry point for the application.:

```
TxOddsInterview.scala
```

Typical configuration includes:

* Queue size
* Number of producers
* Number of consumers
* Input file path
* Output file path

## Possible Improvements

Some potential enhancements:

* Retry mechanism for failed URLs
* Structured logging
* Use **IO** or **ZIO** for better resource management and better control of side effects
# t3as-pdf

## Introduction

This project augments [itext](http://itextpdf.com/) with enhanced text extraction and new
redaction capability.

The enhanced text extraction includes:
- more accurate text placement using floating point comparisons with a specified tolerance rather than truncation to int and exact comparison; this corrects the placement of list bullet marks;
- monitoring font size changes and vertical white space to generate a blank line after headings and between paragraphs; this helps with human readability and may help with NLP analysis of the text.

The redaction capabilty provides:
- removal of text specified as character offsets into the extracted text
- removal of XMP metadata
- removal of PDF annotations, which can store URLs of linked documents and email addresses
- replacement of native PDF metadata with Producer = "iText® 5.5.2 ©2000-2014 iText Group NV (AGPL-version)" (this is a requirement of itext's AGPL license) and Creator = "Redact v0.1 ©2014 NICTA (AGPL)"

See also: [t3as-redact](https://github.com/NICTA/t3as-redact) which depends on this project.

## Install Tools

To run the code install:

- a JRE e.g. from openjdk-7 (prefered for Scala 2.11) or openjdk-8 (will be required for Scala 2.12);
- the build tool [sbt](http://www.scala-sbt.org/).

To develop [Scala](http://scala-lang.org/) code install:

- the above items (or the full JDK instead of just the JRE, but the JRE should be sufficient);
- [Eclipse IDE](https://www.eclipse.org/downloads/); and
- the Scala plugin for Eclipse [scala-ide](http://scala-ide.org/download/current.html).

## Build
 
Automatic builds are provided at: <https://social-watch.dev.etd.nicta.com.au/>.

The command:

	sbt clean test package oneJar publishLocal dumpLicenseReport

from the project's top level directory:

- cleans out previous build products,
- runs all tests,
- creates a jar file (project code), 
- creates a [one-jar](http://one-jar.sourceforge.net/) file (project code with all 3rd party dependencies), 
- publishes build products to the Ivy repository at ~/.ivy2/ (allowing other projects to depend on this one) and
- generates a license report.

## Develop With Eclipse

The command:

	sbt update-classifiers eclipse

downloads source code for 3rd part dependencies and uses the [sbteclipse](https://github.com/typesafehub/sbteclipse/wiki/Using-sbteclipse) plugin to create the .project and .classpath files required by Eclipse.

### Unit Tests in Eclipse

This section describes further configuration required to run unit tests within Eclipse. This is not necessary for running tests with sbt (as shown in the Build section above).

Our unit tests rely on picking up properties files preferentially from
`src/test/resources` ahead of `src/main/resources`,
but unfortunately sbteclipse does not place
`target/scala-2.11/test-classes` (the destination for src/test/resources) ahead of
`target/scala-2.11/classes` (the destination for src/main/resources) in the classpath.
This has to be corrected in Eclipse's `Build Path` for unit tests to pass (move all `src/test` items ahead of all `src/main` items).

## Release

The command:

	sbt release

uses the [sbt-release](https://github.com/sbt/sbt-release) plugin to perform the fairly long (and otherwise error prone) sequence of steps
required to properly release a version of the software.

## Run

To run the CLI from sbt:

    sbt
    > run --help
    
To run the CLI from the one-jar:

    java -jar target/scala-2.11/t3as-pdf_{scala-version}-{project-version}-one-jar.jar --help

## Software Description

In the text below:
 - **itext Java** means we're talking about Java code provided by itext
 - **new Java/Scala** means we're talking about Java/Scala code provided by this project 

### StreamProcessor
**itext Java**: [`com.itextpdf.text.pdf.parser.PdfContentStreamProcessor`](http://api.itextpdf.com/itext/com/itextpdf/text/pdf/parser/PdfContentStreamProcessor.html)
<br>tokenizes a binary PDF content stream, calling a listener for each parsed PDF operator

**new Java**: [`com.itextpdf.text.pdf.parser.MyPdfContentStreamProcessor`](https://github.com/NICTA/t3as-pdf/blob/master/src/main/java/com/itextpdf/text/pdf/parser/MyPdfContentStreamProcessor.java)
(needs package access)
<br>This is a copy not a subclass. It's a big source file and I've made minimal changes - some private fields changed to protected and protected getFontResourceName/push/pop methods added to support …

**new Scala**: [`org.t3as.pdf.RedactionStreamProcessor`](https://github.com/NICTA/t3as-pdf/blob/master/src/main/scala/org/t3as/pdf/RedactionStreamProcessor.scala) extends MyPdfContentStreamProcessor
<br>provides a way for the listener to obtain the start and end offsets into the binary stream that correspond to the PDF operator being processed

### TextExtractionStrategy (a listener for a StreamProcessor)
**itext Java**: [`com.itextpdf.text.pdf.parser.TextExtractionStrategy`](http://api.itextpdf.com/itext/com/itextpdf/text/pdf/parser/TextExtractionStrategy.html)
<br>tracks coordinate transformations to calculate where text appears on the page
 - text chunks with close to the same baseline are assumed to be on the same line
 - a gap from the end of previous text is assumed to be a space

**new Scala**: [`org.t3as.pdf.MyExtractionStrategy`](https://github.com/NICTA/t3as-pdf/blob/master/src/main/scala/org/t3as/pdf/MyExtractionStrategy.scala) extends TextExtractionStrategy
 - improves text placement (float rather than int coords improves same line detection  - handles bullet point placement
 - better gap detection
 - added blank line detection

During parsing, for each text chunk it saves:

    ExtendedChunk(text: String, startLocation: Vector, endLocation: Vector, charSpaceWidth: Float, fontHeight: Float, streamOffset: StreamOffset(start: Long, end: Long))

Chunks can occur in any order in the stream, only when parsing is complete can we figure out their order on the page and then how to map between text offsets and stream offsets. This is done by the `result` method.

### Copy/Redact
**itext Java**: [`com.itextpdf.text.pdf.PdfCopy`](http://api.itextpdf.com/itext/com/itextpdf/text/pdf/PdfCopy.html)
<br>copies pages from input files to output file

**new Scala**: [`org.t3as.pdf.PdfCopyRedact`](https://github.com/NICTA/t3as-pdf/blob/master/src/main/scala/org/t3as/pdf/PdfCopyRedact.scala) extends PdfCopy
<br>overrides
 - `getImportedPage`: uses `RedactionStreamProcessor` and `MyExtractionStrategy`.
   * input is text offsets: `(page: Int, start: Int, end: Int)`
   * output is: `MyResult` which provides methods to convert from text offsets to binary content stream offsets
 -  `copyDictionary` to avoid copying dictionaries of type ANNOT (contains sticky notes, link URI's to email addresses, in/external links etc.)
 - `copyStream` if stream is the main content stream a redacted copy is stored, otherwise it uses the input stream unmodified

### Stream
**itext Java**: [`com.itextpdf.text.pdf.PRStream`](http://api.itextpdf.com/itext/com/itextpdf/text/pdf/PRStream.html)
<br>copies the content stream to the output PDF

**new Scala**: [`com.itextpdf.text.pdf.MyPRStream`](https://github.com/NICTA/t3as-pdf/blob/master/src/main/scala/com/itextpdf/text/pdf/MyPRStream.scala) extends PRStream
(needs package access)
<br>if a redacted copy of the stream has been stored write that instead of the input content stream

## Legal

This software is released under the terms of the [AGPL](http://www.gnu.org/licenses/agpl-3.0.en.html). Source code for all transitive dependencies is available at [t3as-legal](https://github.com/NICTA/t3as-legal).


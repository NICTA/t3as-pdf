/*
 * Copyright (c) 2014 NICTA
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details. You should have
 * received a copy of the GNU Affero General Public License along with this
 * program; if not, see http://www.gnu.org/licenses or write to the Free
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA, 02110-1301 USA.
 * 
 * The interactive user interfaces in modified source and object code
 * versions of this program must display Appropriate Legal Notices, as
 * required under Section 5 of the GNU Affero General Public License.
 */
package org.t3as.pdf

import java.io.{File, FileOutputStream}
import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps
import org.slf4j.LoggerFactory
import com.itextpdf.text.{Document, Paragraph}
import com.itextpdf.text.io.RandomAccessSourceFactory
import com.itextpdf.text.pdf.{PRStream, PRTokeniser, PdfName, PdfReader, PdfWriter, RandomAccessFileOrArray}
import com.itextpdf.text.pdf.parser.PdfReaderContentParser
import PdfCopyRedact.RedactItem
import resource.managed
import scopt.Read
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy

/**
 * CLI for PDF operations.
 * 
 * The `create`, `dump` and `parse` operations are just experiments with simple examples from the iText book,
 * probably only useful for getting an understanding of iText & PDF.
 * 
 * The `extract` and `redact` operations allows experimentation with the improved text extraction and redaction features
 * which this project adds to iText.
 * 
 * For GUI tool to inspect the structure of a PDF try http://itextpdf.com/product/itext_rups.
 */
object Pdf {
  val log = LoggerFactory.getLogger(getClass)

  val redactItemRe = """\( *([0-9]+) *, *([0-9]+) *, *([0-9]+) *\)""".r
  def toRedactItem(s: String) = {
    val redactItemRe(page, str, end) = s
    RedactItem(page.toInt, str.toInt, end.toInt)
  }
  implicit val reductItemRead: Read[RedactItem] = Read.reads(toRedactItem)

  case class Config(
    input: Option[File] = None,
    create: Option[File] = None,
    dump: Boolean = false,
    parse: Boolean = false,
    extract: Boolean = false,
    pdfcopy: Option[File] = None,
    redact: Seq[RedactItem] = Seq()
    )

  val parser = new scopt.OptionParser[Config]("Pdf") {
    head("Pdf", "0.x")
    val defValue = Config()

    opt[File]("input") valueName("<input file>") action { (x, c) =>
      c.copy(input = Some(x))
    } text(s"input PDF file, default ${defValue.input}")

    opt[File]("create") valueName("<output file>") action { (x, c) =>
      c.copy(create = Some(x))
    } text(s"create `Hello World!` PDF file, default ${defValue.create}")

    opt[Unit]("dump") action { (_, c) =>
      c.copy(dump = true)
    } text(s"dump input to logger, default ${defValue.dump}")

    opt[Unit]("parse") action { (_, c) =>
      c.copy(parse = true)
    } text(s"parse input to logger, default ${defValue.parse}")

    opt[Unit]("extract") action { (_, c) =>
      c.copy(extract = true)
    } text(s"extract plain text from input to logger, default ${defValue.extract}")

    opt[File]("copy") valueName("<output file>") action { (x, c) =>
      c.copy(pdfcopy = Some(x))
    } text(s"copy input to this file, default ${defValue.pdfcopy}")

    opt[RedactItem]("redact") unbounded() valueName("(pageNum, startOffset, endOffset)") action { (x, c) =>
      c.copy(redact = c.redact :+ x)
    } text(s"section to redact as start and end offsets into extracted text, default ${defValue.redact}")

    help("help") text("prints this usage text")
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, Config()) foreach { c =>
      c.create foreach (create)
      c.input foreach { src =>
        if (c.dump) dump(src)
        if (c.parse) parse(src)
        if (c.extract) println(extract(src))
        c.pdfcopy foreach (redact(src, _, c.redact))
      }
    }
  }

  def create(out: File) = {
    log.debug(s"create: out = $out")
    val d = new Document
    PdfWriter.getInstance(d, new FileOutputStream(out))
    d.open
    d.add(new Paragraph("Hello World!"))
    d.close
  }

  /** Dump stream instances from Xref table
    */
  def dump(src: File) {
    for {
      r <- managed(new PdfReader(src.getPath))
      i <- 0 until r.getXrefSize
      s <- Option(r.getPdfObject(i)).filter(_.isStream).map(_.asInstanceOf[PRStream]) if s.get(PdfName.TYPE) == null
    } {
      log.debug(s"dump: i = $i, s = $s")
      for (k <- s.getKeys) {
        log.debug(s"dump: k = $k, v = ${s.get(k)}")
      }
    }
  }

  /** Use PRTokeniser to extract the content from each page.
    * See itext book listing 15.20
    */
  def parse(src: File) = {
    for {
      r <- managed(new PdfReader(src.getPath))
      i <- (1 to r.getNumberOfPages).iterator if { log.debug(s"parse: page $i"); true }
      t = new PRTokeniser(new RandomAccessFileOrArray(new RandomAccessSourceFactory().createSource(r.getPageContent(i))))
      _ <- Iterator.continually(t.nextToken).takeWhile(true ==)
    } {
      log.debug(s"parse: ${t.getTokenType}: ${t.getStringValue}")
    }
  }

  def extract(src: File): List[String] = {
    // See itext book listing 15.26
    val pages = new ListBuffer[String]
    for {
      r <- managed(new PdfReader(src.getPath))
      p = new PdfReaderContentParser(r)
      i <- 1 to r.getNumberOfPages
    } {
      log.debug(s"extract: page $i")
      // pages += p.processContent(i, new LocationTextExtractionStrategy).getResultantText()
      pages += p.processContent(i, new MyExtractionStrategy).getResultantText
    }
    pages.toList
  }

  def redact(src: File, dst: File, redact: Seq[RedactItem]) {
    val doc = new Document
    val pdfCopy = new PdfCopyRedact(doc, new FileOutputStream(dst), redact) // must be before doc.open; stream closed by doc.close
    doc.open
    for {
      _ <- managed(doc)
      r <- managed(new PdfReader(src.getPath))
      i <- 1 to r.getNumberOfPages
    } {
      log.debug(s"redact: page $i")
      val page = pdfCopy.getImportedPage(r, i)
      pdfCopy.addPage(page)
    }
  }

}


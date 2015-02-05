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

import java.io.OutputStream
import java.lang.Math.{ max, min }
import java.nio.charset.Charset
import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import org.slf4j.LoggerFactory
import com.itextpdf.text.Document
import com.itextpdf.text.pdf.{ BaseFont, MyPRStream, PRStream, PdfCopy, PdfDictionary, PdfImportedPage, PdfName, PdfReader }
import com.itextpdf.text.pdf.parser.ContentByteUtils
import com.itextpdf.text.pdf.parser.Vector.{I1, I2}
import scala.collection.mutable.ListBuffer
import Math.{ min, max, abs }

class PdfCopyRedact(doc: Document, out: OutputStream, redactItems: Seq[RedactItem]) extends PdfCopy(doc, out) {
  private val log = LoggerFactory.getLogger(getClass)

  private var origRawContent: Option[Array[Byte]] = None
  private var result: Option[MyResult] = None
  private var pageNum = 0
  private var fontName: PdfName = null
  private var baseFont: BaseFont = null

  pdf.addCreator("Redact v0.1 ©2014 NICTA (AGPL)")

  override def getImportedPage(r: PdfReader, pageNum: Int) = {
    this.pageNum = pageNum
    origRawContent = Util.getContentBytesRawForPage(r, pageNum).filter(!_.isEmpty)

    val l = new MyExtractionStrategy
    val resDic = r.getPageN(pageNum).getAsDict(PdfName.RESOURCES)
    new RedactionStreamProcessor(l).processContent(ContentByteUtils.getContentBytesForPage(r, pageNum), resDic)
    result = Some(l.result)
    log.debug(s"getImportedPage: page $pageNum, MyExtractionStrategy.result = ${result.get.chunks.map(_.text).mkString("|")}")
    // log.debug(s"getImportedPage: page $pageNum, MyExtractionStrategy.result = ${result.get.chunks.map(c => (c.parserContext.streamStart, c.textStart, c.text)).mkString("\n")}")
    super.getImportedPage(r, pageNum)
  }

  def addPage(iPage: PdfImportedPage, fontName: PdfName, baseFont: BaseFont) = {
    this.fontName = fontName
    this.baseFont = baseFont
    super.addPage(iPage)
  }

  //  override protected def copyIndirect(in: PRIndirectReference, keepStruct: Boolean, directRootKids: Boolean): PdfIndirectReference = {
  //    log.debug(s"copyIndirect: start: in $in")
  //    val x = super.copyIndirect(in, keepStruct, directRootKids)
  //    log.debug(s"copyIndirect: end:   in $in")
  //    x
  //  }

  /** Copy the dictionary, but if type is ANNOT (contains sticky notes, link URI's to email addresses, in/external links etc.)
    * then replace it with an empty dictionary.
    */
  override protected def copyDictionary(in: PdfDictionary, keepStruct: Boolean, directRootKids: Boolean): PdfDictionary = {
    val typ = PdfReader.getPdfObjectRelease(in.get(PdfName.TYPE))
    if (PdfName.ANNOT == typ) {
      log.debug(s"copyDictionary: skipping: typ = $typ, in = $in")
      new PdfDictionary()
    } else {
      log.debug(s"copyDictionary: start: typ = $typ, in $in")
      val d = super.copyDictionary(in, keepStruct, directRootKids)
      log.debug(s"copyDictionary: end:   typ = $typ, in $in")
      d
    }
  }

  //  override protected def copyArray(in: PdfArray, keepStruct: Boolean, directRootKids: Boolean): PdfArray = {
  //    log.debug(s"copyArray: start: in $in")
  //    val x = super.copyArray(in, keepStruct, directRootKids)
  //    log.debug(s"copyArray: end:   in $in")
  //    x
  //  }

  /** Copy the stream, performing redaction only if it's the page's content stream */
  override protected def copyStream(in: PRStream): PRStream = {
    //      log.debug(s"MyPdfCopy.copyStream: start - in = $in")
    val s = new MyPRStream(in)

    val raw = PdfReader.getStreamBytesRaw(in)
    if (origRawContent.map(_.sameElements(raw)).getOrElse(false) && result.isDefined) {
      log.debug("MyPdfCopy.copyStream: redaction of page content stream...")
      val tbytes = Try(PdfReader.decodeBytes(raw, in)) // decodes according to filters such as FlateDecode
      // Try might catch: com.itextpdf.text.exceptions.UnsupportedPdfException: The filter /DCTDecode is not supported.
      // This is an image filter and the surrounding if should limit this code to the main content stream for the page (containing text), so it shouldn't happen.
      tbytes foreach { bytes => // success case
        s.setData(redact(bytes, result.get, redactItems.filter(_.page == pageNum)), true) // deflates and sets `bytes` for MyPRStream.toPdf to use
      }
      if (tbytes.isFailure) tbytes.failed.foreach(e => log.warn("Can't decode stream", e))
      // Try.transform is more compact, but we don't want the same handling for exceptions from decodeBytes() and redact() 
    } else {
      log.debug("MyPdfCopy.copyStream: doesn't match origRawContent so leaving it alone")
    }

    for (k <- in.getKeys) {
      val v = in.get(k)
      //        log.debug(s"MyPdfCopy.copyStream: copy key = $k, value = $v")
      parentObjects.put(v, in)
      val v2 = copyObject(v)
      if (v2 != null) s.put(k, v2)
    }

    // log.debug(s"MyPdfCopy.copyStream:   end - in = $in")
    s
  }

  def redact(stream: Array[Byte], rslt: MyResult, rItems: Seq[RedactItem]) = {
    import PdfBuilder._

    val bldr = new PdfBuilder

    // Consider redacting a block of text (the X's below) over multiple lines:
    //   Some text XXXXXXXXXXXXXXX
    //   XXXXXXXXXXXXXXXXXXXXXXXXX
    //   XXX and more.
    // To draw a polygon over the redacted text we need the (x, y, height) of the left edge of the first redacted chunk (assuming the rest of the line has the same font height)
    // and likewise for the right edge of the last redacted chunk; as well as the min left margin and max right margin for the intervening lines.
    // We'll use a Rect for the (x, y, height), not using Rect.width. Mutable since we'll be doing this a lot.
    class RedactBox {
      var start: Option[Rect] = None   // left edge of the first redacted chunk
      var end: Option[Rect] = None     // right edge of the last redacted chunk
      var left: Float = Float.MaxValue   // min left margin
      var right: Float = Float.MinValue  // max right margin
      override def toString = s"RedactBox($start, $end, $left, $right)" 
    }
    val boxes = new scala.collection.mutable.HashMap[RedactItem, RedactBox]() {
      override def default(ri: RedactItem) = {
        val b = new RedactBox
        put(ri, b) // auto insert of new item on 1st get
        b
      }
    }
    // save geometry of redacted region
    def setBox(ri: RedactItem, xStart: Float, xEnd: Float, y: Float, height: Float) = {
      if (xEnd - xStart > 0.1f) { // only if region is redacted
        val b = boxes(ri)
        if (b.start.isEmpty) b.start = Some(Rect(xStart, y, 0.0f, height))
        if (xStart < b.left) b.left = xStart
        b.end = Some(Rect(xEnd, y, 0.0f, height))
        if (xEnd > b.right) b.right = xEnd
        log.debug(s"redact.setBox: b = $b, inputs: ri = $ri, xStart = $xStart, xEnd = $xEnd, y = $y, height = $height")
      }
    }
    // get polygon vertices
    def getPoly(b: RedactBox): Option[Seq[PdfBuilder.Point]] = {
      for {
        s <- b.start
        e <- b.end
        sBot = Point(s.x, s.y)
        sTop = Point(s.x, s.y + s.height)
        eBot = Point(e.x, e.y)
        eTop = Point(e.x, e.y + e.height)
      } yield
        if (abs(s.y - e.y) < 0.1f) Seq(sBot, sTop, eTop, eBot) // one line so rectangle
        else Seq(sBot, sTop, Point(b.right, sTop.y), Point(b.right, eTop.y), eTop, eBot, Point(b.left, eBot.y), Point(b.left, sBot.y)) // else octagon
    }
    def getReasonPosition(b: RedactBox, width: Float): Option[Point] = for {
      s <- b.start
      e <- b.end
    } yield
      if (abs(s.y - e.y) < 0.1f) Point(s.x + 2.0f, s.y + 2.0f) // one line
      else if ((s.y - e.y) > e.height * 1.8f) Point(b.left + 2.0f, (s.y + e.y)/2.0f) // > 2 lines so start at left of middle line (which is full width)
      else if (b.right - s.x - 2.0f > width || b.right - s.x > e.x - b.left) Point(s.x + 2.0f, s.y + 2.0f) // text fits on first line or space on first line is wider so use first line
      else Point(b.left + 2.0f, e.y + 2.0f) // use second (last) line

    val mauve = RGB(191f / 255f, 170f / 255f, 255f / 255f)
    val darkMauve = mauve.darker(0.5f)
    val grey = RGB(0.3f, 0.3f, 0.3f)
    // val black = RGB(0.0f, 0.0f, 0.0f)
    
    /**
     * Redact a sequence of text chunks all from a single PDF command (e.g. a TJ, so all chunks are on the same line).
     */
    def redactChunks(chunks: Seq[ResultChunk], ris: Seq[RedactItem]) = {
      // TODO: Bug: Redacting "Chris Richardson" from "by Chris Richardson, predicts" (mostly have a chunk for each individual character, but the "so" in "Richardson" is one chunk) 
      // redactChunks: c = ResultChunk(,,1864,false,346.29547,398.1,1.0 ...
      //                            text            x         y     z
      // the "," after the redacted portion is at x = 346.29547
      // moveText: width = 75.704956 (width of "Chris Richardson")
      // showText: text = ','
      // moveText: width = 2.6879883 (width of ",")
      // showText: text = ' '
      // moveText: width = 2.604004 (width of " ")
      // showText: text = 'p'
      // So far this all looks good and is consistent with what iText RUPS shows for the output binary content; however when viewed in Evince (Linux doc viewer),
      // the comma is not displayed and subsequent text is one comma width too far to the left (probably overlaying and hiding the comma)!
      // Increasing the PDF output precision in PdfBuilder from %.1f to %.3f decreased the positioning error to less than a comma width, but the comma is still not visible. 
    
      val parserContext = chunks.head.parserContext // same for all chunks from the same PDF command
     
      var textOffset = chunks.head.startLocation.get(I1) // position of first chunk relative to left edge of page
      var move = 0.0f
      def moveText(offset: Float) = move = offset // keep the last move request and execute it when we have some subsequent text
      def showText(s: String) = if (!s.isEmpty) {
        val delta = move - textOffset
        if (delta > 0.1f) {
          bldr.moveText(delta)
          textOffset = move
        }
        bldr.showText(s, parserContext.font)
      }
    
      // width in points of string in current font (may be in trouble if 1 user space unit != 1 point)
      def widthPoint(s: String) = {
        val w = parserContext.font.getWidthPoint(s, parserContext.fontSize)
        log.debug(s"redact.widthPoint: s = $s, w = $w")
        w
      }
            
      for (c <- chunks) {        
        // convert RedactItem text offsets from relative to start of page to relative to start of text in the chunk
        val strEndRi = ris flatMap { ri => 
          val str = max(ri.start - c.textStart, 0)
          val end = min(ri.end - c.textStart, c.text.length)
          if (str < end) Some((str, end, ri)) else None
        }
        log.debug(s"redactChunks: c = $c, strEndRi = $strEndRi")
        if (strEndRi.isEmpty) {
          // this chunk not redacted
          moveText(c.startLocation.get(I1))
          showText(c.text)
        } else {
          
          // copy chunk text up to first portion to be redacted
          if (strEndRi.head._1 > 0) {
            moveText(c.startLocation.get(I1))
            showText(c.text.substring(0, strEndRi.head._1))
          }
          
          // for each portion to be redacted except the last
          for (slide2 <- strEndRi.sliding(2)) slide2 match {
            case Seq((str1, end1, ri1), (str2, _, ri2)) =>            
              // skip redacted portion
              val xEnd = c.startLocation.get(I1) + widthPoint(c.text.substring(0, str2))
              moveText(xEnd)
              // copy chunk text from end of redacted portion to start of next redacted portion
              showText(c.text.substring(end1, str2))
              
              val xStart = c.startLocation.get(I1) + widthPoint(c.text.substring(0, str1))
              setBox(ri1, xStart, xEnd, c.fontY, c.fontHeight) // c.fontY takes font descent into account whereas c.startLocation.get(I2) does not
  
            case Seq((str1, end1, ri1)) => // only one portion to be redacted
          }
  
          val lst = strEndRi.last
          // skip last redacted portion
          val xEnd = c.startLocation.get(I1) + widthPoint(c.text.substring(0, lst._2))
          moveText(xEnd)
          // copy chunk text after last bit to be redacted
          showText(c.text.substring(lst._2))
          
          val xStart = c.startLocation.get(I1) + widthPoint(c.text.substring(0, lst._1))
          setBox(lst._3, xStart, xEnd, c.fontY, c.fontHeight)
        }
      }
    }
    
    val chunksToRedact = rslt.chunksToRedact(rItems)

    // copy stream up to first chunk to be redacted
    bldr.w(stream.slice(0, chunksToRedact.head._1.head.parserContext.streamStart.toInt)).newLine // copied portion of stream doesn't always end with this and no harm done by an extra one
    
    // for each chunk other than the last
    chunksToRedact.sliding(2).foreach {
      case Seq((chunks1, ris1), (chunks2, _)) =>
        redactChunks(chunks1, ris1)
        // copy stream from end of chunks1 to start of chunks2
        bldr.w(stream.slice(chunks1.head.parserContext.streamEnd.toInt, chunks2.head.parserContext.streamStart.toInt)).newLine
      case Seq((chunks1, ris1)) => // only one chunk
    }

    val lst = chunksToRedact.last
    redactChunks(lst._1, lst._2)
    // copy stream from end of last chunk to be redacted to end
    bldr.w(stream.slice(lst._1.head.parserContext.streamEnd.toInt, stream.length)).newLine
    
    // working: --redact '(1,1848,1864,reason)' --redact '(1,1878,1888,reason2)'
    // working: --redact '(1,241,267,reason1)' --redact '(1,1848,1864,reason2)' 
    // not working: --redact '(1,2618,2631,reason2)'
    // not working: --redact '(1,2936,2946,reason2)'
    log.debug(s"redact: boxes = $boxes")
    for {
      (ri, b) <- boxes
      vertices <- getPoly(b)
      fontSize = (vertices(1).y - vertices.head.y) * 3.0f / 4.0f
      rPos <- getReasonPosition(b, baseFont.getWidthPoint(ri.reason, fontSize))
    } {
      log.debug(s"redact: vertices = $vertices")
      bldr.save
      .fillColour(mauve).strokeColour(darkMauve).poly(vertices).clip
      .fillColour(darkMauve).beginText.fontSize(fontName, fontSize).moveText(rPos.x, rPos.y).showText(ri.reason, baseFont).endText
      .restore
    }
    
    bldr.bytes
  }
}

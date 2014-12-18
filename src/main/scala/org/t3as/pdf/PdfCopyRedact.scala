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
import java.nio.charset.Charset
import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal
import org.slf4j.LoggerFactory
import com.itextpdf.text.Document
import com.itextpdf.text.pdf.{ MyPRStream, PRIndirectReference, PRStream, PdfArray, PdfCopy, PdfDictionary, PdfIndirectReference, PdfName, PdfReader }
import com.itextpdf.text.pdf.parser.ContentByteUtils
import com.itextpdf.text.pdf.PdfDocument
import com.itextpdf.text.pdf.PdfDocument.PdfInfo
import com.itextpdf.text.pdf.PdfAnnotation
import scala.util.Try
import com.itextpdf.text.Font
import com.itextpdf.text.pdf.BaseFont
import com.itextpdf.text.pdf.parser.Vector, Vector.{I1, I2}

class PdfCopyRedact(doc: Document, out: OutputStream, redactItems: Seq[RedactItem]) extends PdfCopy(doc, out) {
  private val log = LoggerFactory.getLogger(getClass)

  private var origRawContent: Option[Array[Byte]] = None
  private var result: Option[MyResult] = None
  private var pageNum = 0
  
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

//  override protected def copyIndirect(in: PRIndirectReference, keepStruct: Boolean, directRootKids: Boolean): PdfIndirectReference = {
//    log.debug(s"copyIndirect: start: in $in")
//    val x = super.copyIndirect(in, keepStruct, directRootKids)
//    log.debug(s"copyIndirect: end:   in $in")
//    x
//  }

  /** Copy the dictionary, but if type is ANNOT (contains stick notes, link URI's to email addresses, in/external links etc.)
    * then replace it with an empty dictionary.
    */
  override protected def copyDictionary(in: PdfDictionary, keepStruct: Boolean, directRootKids: Boolean): PdfDictionary = {
    val typ = PdfReader.getPdfObjectRelease(in.get(PdfName.TYPE))
    if (PdfName.ANNOT == typ) {
      log.debug(s"copyDictionary: skipping: in $in")
      new PdfDictionary()
    } else {
      log.debug(s"copyDictionary: start: in $in")
      val x = super.copyDictionary(in, keepStruct, directRootKids)
      log.debug(s"copyDictionary: end:   in $in")
      x
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
        s.setData(redact(bytes, redactItems.filter(_.page == pageNum)), true) // deflates and sets `bytes` for MyPRStream.toPdf to use
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

  def redact(stream: Array[Byte], rItems: Seq[RedactItem]) = {
    import Math.{min, max}

    val buf = new ArrayBuffer[Byte]

    // PDF commands
    
    val utf8 = Charset.forName("UTF-8")
    def u(s: String) = s.getBytes(utf8)
    def w(b: Array[Byte]) = buf ++= b
    
    val uSave = u("q\n")
    def save = w(uSave)
    
    val uRestore = u("Q\n")
    def restore = w(uRestore)
    
    def moveText(width: Float) = {
      log.debug(s"redact.moveText: width = $width")
      w(u(f"$width%.1f 0.0 Td\n"))
    }
      
    val uSTStr = u("(")
    val uSTEnd = u(")Tj\n")
    def showText(s: String, f: BaseFont) = {
      log.debug(s"redact.showText: s = '$s'")
      w(uSTStr)
      w(f.convertToBytes(s))
      w(uSTEnd)
    }
    
    case class RGB(red: Float, green: Float, blue: Float)
    def rgb(c: RGB) = w(u(f"${c.red}%.5f ${c.green}%.5f ${c.blue}%.5f rg\n"))
    
    case class Rect(x: Float, y: Float, width: Float, height: Float)
    def rect(r: Rect) = w(u(f"${r.x}%.2f ${r.y}%.2f ${r.width}%.2f ${r.height}%.2f re\n"))
    
    val uFill = u("f\n")
    def fill = w(uFill)
    
    def rectFill(r: Rect, c: RGB) = {
      save
      rgb(c)
      rect(r)
      fill
      restore
    }
    
    // width in points of string in current font (may be in trouble if 1 user space unit != 1 point)
    def widthPoint(t: String, pc: ParserContext) = {
      val w = pc.font.getWidthPoint(t, pc.fontSize)
      log.debug(s"redact.widthPoint: t = $t, w = $w")
      w
    }

    val black = RGB(0.0f, 0.0f, 0.0f)
    // just to test - drawing rectangle first puts it behind the text written afterwards
    // rectFill(Rect(290f, 728.3f, 30f,  10f), black)
    
    var streamOffset = 0
    val redactedSoFar = new StringBuilder
    var needMoveText = false
    var rectX: Float = 0.0f
    var textXOrigin: Float = 0.0f
    var sumDelta: Float = 0.0f

    def moveShowText(s: String, c: ResultChunk) = {
      if (needMoveText) {
        // draw rect
        val rectWidth = widthPoint(redactedSoFar.toString, c.parserContext)
        val r = Rect(rectX, c.fontY, rectWidth, c.fontHeight)
        log.debug(s"redact.moveShowText: r = $r")
        rectFill(r, black)
        val delta = rectX + rectWidth - textXOrigin
        moveText(delta)
        sumDelta += delta
        textXOrigin += delta
        redactedSoFar.clear
        needMoveText = false
      }
      if (!s.isEmpty) showText(s, c.parserContext.font)
    }
      
    // for each redacted chunk
    var prevChunk: ResultChunk = null
    result.get.chunksToRedact(rItems).foreach { case (c, redactItems) =>
      log.debug(s"redact: c = $c")
      val pc = c.parserContext
      
      // For PDF command [ ... ]TJ we get a chunk for every array element, but they all have the same ParserContext (that of the whole TJ),
      // so we only want to copy the content preceding the TJ once!
      if (pc.streamStart.toInt >= streamOffset) {
        if (needMoveText) moveShowText("", prevChunk) // before start of new line draw rectangle for redacted text at the end of the previous line
        if (sumDelta > 0.0001f) moveText(-sumDelta)
        
        log.debug(s"redact: copy stream up to start of chunk to be redacted from $streamOffset to ${pc.streamStart.toInt}, continue from ${pc.streamEnd.toInt}")
        buf ++= stream.slice(streamOffset, pc.streamStart.toInt)
        buf += '\n' // copied portion of stream doesn't always end with this, no harm done by an extra one
        streamOffset = pc.streamEnd.toInt

        redactedSoFar.clear
        needMoveText = false
        textXOrigin = c.startLocation.get(I1)
        sumDelta = 0.0f
      }
      
      var textOffset = 0
      // for each RedactItem  
      redactItems.sortBy(_.start).map { ri =>
        // convert segment text offsets from relative to start of page to relative to start of text in the chunk
        if (ri.start == ri.end) ((c.text.length, c.text.length)) // no redaction for this chunk, copy it all
        else (max(ri.start - c.textStart, 0), min(ri.end - c.textStart, c.text.length))
      }.foreach { case (redactStart, redactEnd) =>
        log.debug(s"redact: c.text = ${c.text}, textOffset = $textOffset, redactStart = $redactStart, redactEnd = $redactEnd, redactedSoFar = $redactedSoFar")
        if (redactStart > textOffset) {
          val s = c.text.substring(textOffset, redactStart)
          log.debug(s"redact: write text: '$s'")
          moveShowText(s, c)
        }
        if (redactStart < c.text.length) {
          if (!needMoveText) {
            rectX = c.startLocation.get(I1) + widthPoint(c.text.substring(0, redactStart), c.parserContext)
            needMoveText = true
          }
          redactedSoFar ++= c.text.substring(redactStart, redactEnd)
        }
        textOffset = redactEnd
      }
      if (textOffset < c.text.length) {
        val s = c.text.substring(textOffset)
        log.debug(s"redact: write remaining text: '$s'")
        moveShowText(s, c)
      }
      // if s is empty we may still need to draw a rectangle representing redacted text at the end of the line
      // case I'm looking at is short chunks, mostly one per char, so for each chunk we have one RedactItem(0, 1)
      // and we're redacting multiple char's at the end of the line
      // Without the if above, we're writing a separate rectangle for each char here.
      // Can we delay a bit and write one rectangle for the sequence of redacted chars?
      // That was the idea behind redactedSoFar.
      prevChunk = c
    }
    if (sumDelta > 0.0001f) moveText(-sumDelta)
    log.debug(s"redact: copy remainder of stream from $streamOffset")
    buf ++= stream.slice(streamOffset, stream.length)
    buf.toArray
  }

}

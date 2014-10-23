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
import PdfCopyRedact.RedactItem
import com.itextpdf.text.pdf.PdfDocument
import com.itextpdf.text.pdf.PdfDocument.PdfInfo
import com.itextpdf.text.pdf.PdfAnnotation

object PdfCopyRedact {
  /** page and text offsets with page of item to be redacted */
  case class RedactItem(page: Int, start: Int, end: Int)
}

class PdfCopyRedact(doc: Document, out: OutputStream, redactItems: Seq[RedactItem]) extends PdfCopy(doc, out) {
  private val log = LoggerFactory.getLogger(getClass)

  private var origRawContent: Option[Array[Byte]] = None
  private var result: Option[MyResult] = None
  private var pageNum = 0
  val utf8 = Charset.forName("UTF-8")
  
  pdf.addCreator("Redact v0.1 ©2014 NICTA (AGPL)")

  override def getImportedPage(r: PdfReader, pageNum: Int) = {
    this.pageNum = pageNum
    origRawContent = Util.getContentBytesRawForPage(r, pageNum).filter(!_.isEmpty)

    val l = new MyExtractionStrategy
    val resDic = r.getPageN(pageNum).getAsDict(PdfName.RESOURCES)
    new RedactionStreamProcessor(l).processContent(ContentByteUtils.getContentBytesForPage(r, pageNum), resDic)
    result = Some(l.result)
    //      log.debug(s"getImportedPage: page $pageNum, text = ${result.get.text}")

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
      try {
        val b = PdfReader.decodeBytes(raw, in) // decodes according to filters such as FlateDecode
        // can throw: com.itextpdf.text.exceptions.UnsupportedPdfException: The filter /DCTDecode is not supported.
        // This is an image filter and the surrounding if should limit this code to the main content stream for the page (containing text), so it shouldn't happen.
        s.setData(redact(b, redactItems.filter(_.page == pageNum).map(r => (r.start, r.end))), true) // deflates and sets `bytes` for MyPRStream.toPdf to use
      } catch {
        case NonFatal(e) => log.warn("Can't decode stream", e)
      }
    } else {
      log.debug(s"MyPdfCopy.copyStream: doesn't match origRawContent so leaving it alone")
    }

    for (k <- in.getKeys) {
      val v = in.get(k)
      //        log.debug(s"MyPdfCopy.copyStream: copy key = $k, value = $v")
      parentObjects.put(v, in)
      val v2 = copyObject(v)
      if (v2 != null) s.put(k, v2)
    }

    //      log.debug(s"MyPdfCopy.copyStream:   end - in = $in")
    s
  }

  val tj = "([REDACTED])Tj".getBytes(utf8)

  def redact(b: Array[Byte], redactTextOffsets: Seq[(Int, Int)]) = {
    val buf = new ArrayBuffer[Byte]
    val chunkstoRedact = result.get.redact(redactTextOffsets)
    var offset = 0
    chunkstoRedact.foreach { c =>
      log.debug(s"MyPdfCopy.redact: c = $c, deleted = '${new String(b.slice(c.start.toInt, c.end.toInt), utf8)}'")
      buf ++= b.slice(offset, c.start.toInt)
      buf ++= tj
      offset = c.end.toInt
    }
    buf ++= b.slice(offset, b.length)

    //      log.debug(s"MyPdfCopy.redact: in  = '${new String(b, utf8)}'")
    //      log.debug(s"MyPdfCopy.redact: out = '${new String(buf.toArray, utf8)}'")
    buf.toArray
  }

}

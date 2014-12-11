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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}

import org.scalatest.{FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import com.itextpdf.text.Document
import com.itextpdf.text.pdf.PdfReader

import resource.managed

object RedactTest {
  
  /**
   * Redact PDF and close is.
   * @param is PDF input
   * @param redact text offsets to redact
   * @return PDF output
   */
  def redact(is: InputStream, redact: Seq[RedactItem]): Array[Byte] = {
    val doc = new Document
    val os = new ByteArrayOutputStream
    val pdf = new PdfCopyRedact(doc, os, redact) // must be before doc.open; stream closed by doc.close
    doc.open
    for {
      _ <- managed(doc)
      in <- managed(is)
      r <- managed(new PdfReader(in))
      i <- 1 to r.getNumberOfPages
    } {
      val page = pdf.getImportedPage(r, i)
      pdf.addPage(page)
    }
    os.toByteArray
  }
}

class RedactTest extends FlatSpec with Matchers {
  import ExtractTest._
  import RedactTest._
  
  val log = LoggerFactory.getLogger(getClass)
  def z(s: String) = s.replace('\n', ' ')
  
//  "f string interpolation" should "work" in {
//    f"before ${5.0f + 0.6f}%3.1f after" should be("before 5.6 after")
//  }
//  
//  "Redact" should "redact text from created PDF" in {
//    val find = "Tony Abbott "
//    val idx = Pdf.text.indexOf(find)
//    idx > 5 should be(true)
//    val ri = RedactItem(1, idx, idx + find.length)
//    val redactedPdf = redact(new ByteArrayInputStream(create), Seq(ri))
//    val redactedText = extract(new ByteArrayInputStream(redactedPdf))(0)
//    val expected = Pdf.text.substring(0, ri.start) + Pdf.text.substring(ri.end)
//    z(redactedText) should be(z(expected))
//  }
  
  "Redact" should "redact text from SampleDoc.pdf" in {
    val text = extract(getClass.getClassLoader.getResourceAsStream("SampleDoc.pdf"))(0)
    val find = "Uphill" // "rk Ken" // "struggle ahead" // the whole chunk is "Mark Kenny", but we want to test keeping the start and end of it
    val idx = text.indexOf(find)
    idx > 40 should be(true)
    val ri = RedactItem(1, idx, idx + find.length)
    log.debug(s"ri = $ri")
    val redactedPdf = redact(getClass.getClassLoader.getResourceAsStream("SampleDoc.pdf"), Seq(ri))
    val redactedText = extract(new ByteArrayInputStream(redactedPdf))(0)
    val expected = text.substring(0, ri.start) + " " + text.substring(ri.end)
    z(redactedText) should be(z(expected))
  }

}
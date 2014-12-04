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
import org.t3as.pdf.PdfCopyRedact.RedactItem

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
  import ExtractTest.extract
  import RedactTest.redact
  
  val log = LoggerFactory.getLogger(getClass)
  
  "Redact" should "redact text from PDF" in {
    val text = extract(getClass.getClassLoader.getResourceAsStream("SampleDoc.pdf"))(0)
    val find = "Mark Kenny"
    val idx = text.indexOf(find)
    idx > 40 should be(true)
    log.debug("before redaction: " + text.substring(idx - 40, idx + 40))

    val redactedPdf = redact(getClass.getClassLoader.getResourceAsStream("SampleDoc.pdf"), Seq(RedactItem(1, idx, idx + find.length)))
    val redactedText = extract(new ByteArrayInputStream(redactedPdf))(0)
    log.debug("after redaction: " + redactedText.substring(idx - 40, idx + 40))
    redactedText.indexOf(find) should be(-1)
  }

}
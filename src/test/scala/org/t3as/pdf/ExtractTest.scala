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

import scala.collection.mutable.ListBuffer

import org.scalatest.{Finders, FlatSpec, Matchers}

import com.itextpdf.text.{Document, Paragraph}
import com.itextpdf.text.pdf.{PdfReader, PdfWriter}
import com.itextpdf.text.pdf.parser.PdfReaderContentParser

import resource.managed

object ExtractTest {
  
  /**
   * Create a small PDF document
   * @return PDF
   */
  def create: Array[Byte] = {
    val b = new ByteArrayOutputStream
    val d = new Document
    PdfWriter.getInstance(d, b)
    d.open
    d.add(new Paragraph(Pdf.text))
    d.close
    b.toByteArray
  }

  /**
   * Extract text and close is.
   * @param is PDF content
   * @return extracted text
   * @see itext book listing 15.26
   */
  def extract(is: InputStream): List[String] = {
    val pages = new ListBuffer[String]
    for {
      in <- managed(is)
      r <- managed(new PdfReader(in))
      p = new PdfReaderContentParser(r)
      i <- 1 to r.getNumberOfPages
    } pages += p.processContent(i, new MyExtractionStrategy).getResultantText // or use itext's LocationTextExtractionStrategy
    pages.toList
  }  
}

class ExtractTest extends FlatSpec with Matchers {
  import ExtractTest._

  "MyExtractionStrategy" should "extract text from created PDF" in {
    val pages = extract(new ByteArrayInputStream(create))
    pages(0).replace('\n', ' ') should be(Pdf.text.replace('\n', ' '))
  }
  
  it should "extract text from SampleDoc.pdf" in {
    val pages = extract(getClass.getClassLoader.getResourceAsStream("SampleDoc.pdf"))
    pages(0).contains("Mark Kenny Chief Political Correspondent") should be(true)
  }

}
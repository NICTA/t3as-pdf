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

import java.io.InputStream

import scala.collection.mutable.ListBuffer

import org.scalatest.{FlatSpec, Matchers}

import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfReaderContentParser

import resource.managed

object ExtractTest {
  
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
  import ExtractTest.extract
  
  // val log = LoggerFactory.getLogger(getClass)

  "MyExtractionStrategy" should "extract text" in {
    val pages = extract(getClass.getClassLoader.getResourceAsStream("SampleDoc.pdf"))
    pages(0).contains("Mark Kenny Chief Political Correspondent") should be(true)
  }

}
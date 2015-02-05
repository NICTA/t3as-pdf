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

import java.util.ArrayList

import org.slf4j.LoggerFactory

import com.itextpdf.text.io.RandomAccessSourceFactory
import com.itextpdf.text.pdf.{PRTokeniser, PdfContentParser, PdfDictionary, PdfLiteral, PdfName, PdfObject, RandomAccessFileOrArray}
import com.itextpdf.text.pdf.parser.{InlineImageUtils, MyPdfContentStreamProcessor, RenderListener}

/** Processes content of a page using the specified listener.
  * Provides access by the listener to the stream offsets of parsed content, required for redaction.
  *
  * Based on com.itextpdf.text.pdf.parser.PdfReaderContentParser
  */
class RedactionStreamProcessor(l: RenderListener with HasParserContext) extends MyPdfContentStreamProcessor(l) {
  private val log = LoggerFactory.getLogger(getClass)

  // provide StreamOffset
  private var startOffset = 0L
  private var endOffset = 0L
  l.parserContext = () => ParserContext(startOffset, endOffset, gs.getFont, gs.getFontSize)

  /** Processes PDF syntax.
    * <b>Note:</b> If you re-use a given {@link PdfContentStreamProcessor}, you must call {@link PdfContentStreamProcessor#reset()}
    * @param contentBytes  the bytes of a content stream
    * @param resources     the resources that come with the content stream
    */
  override def processContent(contentBytes: Array[Byte], resDic: PdfDictionary) = {
    val src = new RandomAccessSourceFactory().createSource(contentBytes)
    def getBytes(start: Long, end: Long) = {
      val b = new Array[Byte]((end - start).toInt)
      val n = src.get(start, b, 0, b.length)
      if (n != b.length) { // the last item for each page is one byte short
        log.warn(s"Partial read: n = $n")
        b.iterator.take(n)
      } else
        b.iterator
    }

    val tokeniser = new PRTokeniser(new RandomAccessFileOrArray(src))
    processTokens(tokeniser, resDic, getBytes)
  }

  def processTokens(tokeniser: PRTokeniser, resDic: PdfDictionary, getBytes: (Long, Long) => Iterator[Byte]) = {
    push(resDic)

    val ps = new PdfContentParser(tokeniser)
    val operands = new ArrayList[PdfObject]
    startOffset = tokeniser.getFilePointer
    while (ps.parse(operands).size() > 0) {
      val operator = operands.get(operands.size() - 1).asInstanceOf[PdfLiteral]
      endOffset = tokeniser.getFilePointer
      if ("BI".equals(operator.toString)) {
        // we don't call invokeOperator for embedded images - this is one area of the PDF spec that is particularly nasty and inconsistent
        val colorSpaceDic = if (resDic != null) resDic.getAsDict(PdfName.COLORSPACE) else null
        handleInlineImage(InlineImageUtils.parseInlineImage(ps, colorSpaceDic), colorSpaceDic)
      } else {
        invokeOperator(operator, operands)
      }
      startOffset = endOffset
    }

    pop
  }
}

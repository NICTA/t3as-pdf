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

import scala.collection.mutable.ListBuffer
import com.itextpdf.text.pdf.parser.{Matrix, TextExtractionStrategy, TextRenderInfo, Vector}
import com.itextpdf.text.pdf.parser.ImageRenderInfo
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy.TextChunk
import org.slf4j.LoggerFactory
import com.itextpdf.text.pdf.BaseFont

/** Provide access to byte offsets into the content stream being parsed and current font. */
case class ParserContext(streamStart: Long, streamEnd: Long, font: BaseFont, fontSize: Float)

/** page and text offsets for item to be redacted */
case class RedactItem(page: Int, start: Int, end: Int, reason: String)


trait HasParserContext {
  var parserContext = () => ParserContext(0L, 0L, BaseFont.createFont, 16.0f) // var set by parser
}

/** Representation of text chunks during parsing of the page, before we sort them by position on the page to figure out their ordering.
  * Modified from itext's LocationTextExtractionStrategy.TextChunk.  
  */
case class ExtendedChunk(text: String, startLocation: Vector, endLocation: Vector, charSpaceWidth: Float, fontY: Float, fontHeight: Float, parserContext: ParserContext) {
  import Vector._
  import ExtendedChunk._
  
  private val log = LoggerFactory.getLogger(getClass)
  
  // copied from TextChunk, which has too many things we need as private
  val (distPerpendicular, orientationMagnitude, distParallelStart, distParallelEnd) = {
    val orientationVector = {
      val o = endLocation.subtract(startLocation)
      // log.debug(s"o = $o, length = ${o.length}, lengthZero = ${sameF(o.length, 0.0f)}")
      if (sameF(o.length, 0.0f)) new Vector(1, 0, 0) else o.normalize // itext's TextChunk uses `o.length == 0` which in practice is always false
    }
    val perp = (startLocation.subtract(origin)).cross(orientationVector).get(I3)
    val mag = Math.atan2(orientationVector.get(I2), orientationVector.get(I1)) * 1000
    (perp, mag, orientationVector.dot(startLocation), orientationVector.dot(endLocation))
  }
  // log.debug(s"this = $this, distPerpendicular = $distPerpendicular, orientationMagnitude = $orientationMagnitude, distParallelStart = $distParallelStart, distParallelEnd = $distParallelEnd")

  // itext's TextChunk uses int == here, but int truncation won't always make near floats the same
  def sameLine(as: ExtendedChunk) =
    sameD(orientationMagnitude, as.orientationMagnitude) && sameF(distPerpendicular, as.distPerpendicular)
}

object ExtendedChunk {
  import Math.{abs, max}

  val origin = new Vector(0,0,1)
  
  /** @return true iff x is approximately equal to y, within given precision. */
  def sameF(x: Float, y: Float, precision: Float = 2.0f): Boolean =
    x == y || abs(x - y) < precision
  
  def sameD(x: Double, y: Double, precision: Double = 2.0d): Boolean =
    x == y || abs(x - y) < precision
  
  // itext's TextChunk uses int == here, but int truncation won't always make near floats the same
  // in particular, I was getting bullets between the first and second line of the bullet's text
  def lt(l: ExtendedChunk, r: ExtendedChunk): Boolean = {
    if (!sameD(l.orientationMagnitude, r.orientationMagnitude)) {
      l.orientationMagnitude < r.orientationMagnitude
    } else if (!sameF(l.distPerpendicular, r.distPerpendicular)) {
      l.distPerpendicular < r.distPerpendicular
    } else l.distParallelStart < r.distParallelStart
  }
}

/** Representation of text chunks after we know their their order on the page.
 *  
 *  For text extraction we need ResultChunks for extra spaces and new lines inferred from gaps between text segments,
 *  but when regenerating PDF commands for redacted content we don't want to generate content from this inferred white space,
 *  hence the `inferredWhiteSpace` flag.
 *  
 *  TODO: also store horizontal offsets in PDF units - we could use this to more accurately place the text around redacted sections.
 */
case class ResultChunk(text: String, textStart: Int, inferredWhiteSpace: Boolean, startLocation: Vector, endLocation: Vector, fontY: Float, fontHeight: Float, parserContext: ParserContext) {
  def textEnd =  textStart + text.length
  def intersects(r: RedactItem) = r.start < textEnd && r.end > textStart
  def sameStreamStart(c: ResultChunk) = c.parserContext.streamStart == parserContext.streamStart
}
 
/** Wrap an ordered sequence of ResultChunk, providing methods to support text extraction and redaction. */
case class MyResult(chunks: Seq[ResultChunk]) {
  private val log = LoggerFactory.getLogger(getClass)

  /** Concatenate text from all chunks to get the document text. */
  val text = chunks.iterator.map(_.text).mkString("")

  /** Find chunks for redaction.
   * 
   * The PDF operator '[ ... ]TJ' results in a separate chunk for each array element.
   * Redaction replaces all the content for an operator, so if we redact one of these chunks we need to reproduce all the others.
   * To facilitate this we group all the chunks from the same TJ (same parserContext.streamStart).
   * So we return a sequence of:
   *  - sequence of chunks all with same streamStart
   *  - sequence of RedactItems that intersect any of these chunks
   * where both sequences are non-empty.
   * 
   * Redaction uses this to:
   * - copy bytes in the stream not related to redacted content (offsets not in any parserContext.streamStart..parserContext.streamEnd)
   * - generate new redacted content to replace the bytes omitted above
   */
  def chunksToRedact(offsets: Seq[RedactItem]) = {
    val r = (for {
      chunksWithSameStreamStart <- chunks.filter(!_.inferredWhiteSpace).groupBy(_.parserContext.streamStart).map(_._2)
      ris = offsets.filter { o => chunksWithSameStreamStart.exists { c => c.intersects(o) } } if !ris.isEmpty
    } yield (chunksWithSameStreamStart.sortBy(_.textStart), ris.sortBy(_.start))
    ).toSeq.sortBy(_._1.head.textStart)
    log.debug(s"chunksToRedact: r = ${r.flatMap(_._1.map(_.text)).mkString("|")}")
    // log.debug(s"chunksToRedact: r = $r")
    r
  }
}

/** Modified from: com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy */
class MyExtractionStrategy extends TextExtractionStrategy with HasParserContext {
  private val log = LoggerFactory.getLogger(getClass)

  override def beginTextBlock = {}
  override def endTextBlock = {}

  val chunks = new ListBuffer[ExtendedChunk]

  /**
   * Render text into `chunks` data struct
   * 
   * TODO: Leif's pdf generated on OS X has spurious
   *   /TT4 1 Tf
   *   (!) Tj
   * which generates "!" at line ends.
   * The font used, in this case /TT4, has no /Encoding attribute, for which itext getEncoding returns "".
   * Also: /FirstChar = /LastChar = 33 ('!' as decimal)
   * Can't just skip text in a font with blank encoding though, because other PDFs (generated by me on linux) have blank font encoding for all their text.
   * Encoding needs to be specified for Type1 (orig Adobe) fonts and Type3 fonts (outlines defined using PDF graphics primitives), but not for TrueType or Type0 (composite) fonts,
   * so its normal that encoding wasn't specified here.
   * So I'm not sure if/how we should filter this spurious text. Could look at size, stroke, fill attributes to see why it is invisible.
   * Maybe getTextRenderMode() == 3 for Invisible?
   * 
   * When the PDF contains '[ ... ]TJ' this is an array of text chunks each with their own horizontal offset
   * and we get called for each array element with the same parserContext (which guarantees these chunks on the same line).  
   */
  override def renderText(r: TextRenderInfo) = {
    import Vector.I2
    val fontY = r.getDescentLine.getStartPoint.get(I2)
    val fontHeight = r.getAscentLine.getStartPoint.get(I2) - fontY
    // remove the rise from the baseline - we do this because the text from a super/subscript render operation should probably be considered as part of the baseline of the text 
    val b = {
      val b = r.getBaseline
      if (r.getRise != 0) b else b.transformBy(new Matrix(0, -r.getRise))
    }
    chunks += ExtendedChunk(r.getText, b.getStartPoint, b.getEndPoint, r.getSingleSpaceWidth, fontY, fontHeight, parserContext())
  }

  /** no-op, not interested in image events */
  override def renderImage(r: ImageRenderInfo) = {}

  /** Determines whether a space character should be inserted between a chunk and the previous chunk.
    * True if:
    * - chunk doesn't start with space and previous chunk doesn't end with space; and one of:
    * - there is a gap of more than half the font space character width between the end of the previous chunk and the beginning of the current chunk; or
    * - the current chunk starts before the end of the previous chunk (i.e. overlapping text)
    * @param c the chunk
    * @param prev the previous chunk that appeared immediately before the current chunk
    * @return true if space needs to be added before c
    */
  def needSpace(c: ExtendedChunk, prev: ExtendedChunk) = {
    val d = c.distParallelStart - prev.distParallelEnd
    !c.text.startsWith(" ") && !prev.text.endsWith(" ") && (d > c.charSpaceWidth / 2.0f || d < -c.charSpaceWidth)
  }
  
  /** Determines whether vertical whitespace (2 new lines instead of one) is required.
    * This improves the readability of text and may help NLP, but itext doesn't do it. 
    */
  def needBlankLine(c: ExtendedChunk, prev: ExtendedChunk) = {
    val offset = c.distPerpendicular - prev.distPerpendicular
    // log.debug(s"needBlankLine: prev chunk text = ${prev.text} height = ${prev.fontHeight}, new chunk text = ${c.text} height = ${c.fontHeight}, perp dist = $offset")
    !ExtendedChunk.sameF(c.fontHeight, prev.fontHeight, 0.3f) || offset > 2 * prev.fontHeight
  }

  /** Modification of: LocationTextExtractionStrategy.getResultantText() to preserve the chunks with their positions and add text offsets. */
  def result = {
    val buf = new ListBuffer[ResultChunk]
    var prevChunk: ExtendedChunk = null
    var textOffset: Int = 0
    for (c <- chunks.sortWith(ExtendedChunk.lt)) {
      if (prevChunk != null) {
        if (c.sameLine(prevChunk)) {
          // we only insert a blank space if the trailing character of the previous string wasn't a space, and the leading character of the current string isn't a space
          if (needSpace(c, prevChunk)) {
            buf += ResultChunk(" ", textOffset, true, c.startLocation, c.endLocation, c.fontY, c.fontHeight, c.parserContext)
            textOffset += 1
          }
        } else {
          val vertSpace = if (needBlankLine(c, prevChunk)) "\n\n" else "\n"
          buf += ResultChunk(vertSpace, textOffset, true, c.startLocation, c.endLocation, c.fontY, c.fontHeight, c.parserContext)
          textOffset += vertSpace.length
        }
      }
      buf += ResultChunk(c.text, textOffset, false, c.startLocation, c.endLocation, c.fontY, c.fontHeight, c.parserContext)
      textOffset += c.text.length
      prevChunk = c
    }
    MyResult(buf.toSeq)
  }

  override def getResultantText = result.text
}

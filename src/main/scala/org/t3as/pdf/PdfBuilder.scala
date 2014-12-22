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

import java.nio.charset.Charset
import com.itextpdf.text.pdf.PdfName
import scala.collection.mutable.ArrayBuffer
import com.itextpdf.text.pdf.BaseFont
import PdfBuilder._
import org.slf4j.LoggerFactory

object PdfBuilder {
  val utf8 = Charset.forName("UTF-8")
  case class RGB(red: Float, green: Float, blue: Float) // 0.0f .. 1.0f
  case class Rect(x: Float, y: Float, width: Float, height: Float)
}

/**
 * Byte level creation of PDF content stream.
 * See iText book chap 14. 
 */
class PdfBuilder {
  private val log = LoggerFactory.getLogger(getClass)

  private val buf = new ArrayBuffer[Byte]

  def bytes = buf.toArray

  def u(s: String) = s.getBytes(utf8) // commands are mostly UTF-8
  def w(b: Array[Byte]) = {
    buf ++= b                         // with some binary content
    this
  }

  // PDF commands

  val uNewLine = u("\n")
  def newLine = w(uNewLine) // command separator

  val uSave = u("q\n")
  def save = w(uSave) // push copy of current graphics state onto stack 

  val uRestore = u("Q\n")
  def restore = w(uRestore) // pop graphics state stack 

  val uBeginText = u("BT\n")
  def beginText = w(uBeginText)

  val uEndText = u("ET\n")
  def endText = w(uEndText)

  def fontSize(name: PdfName, size: Float) = {
    w(name.getBytes)
    w(u(f" $size%.1f Tf\n"))
  }

  def moveText(width: Float, height: Float = 0.0f) = {
    log.debug(s"moveText: width = $width")
    w(u(f"$width%.1f $height%.1f Td\n"))
  }

  val uSTStr = u("(")
  val uSTEnd = u(")Tj\n")
  def showText(text: String, f: BaseFont) = {
    log.debug(s"showText: text = '$text'")
    w(uSTStr)
    w(f.convertToBytes(text))
    w(uSTEnd)
  }

  def rgb(c: RGB) = w(u(f"${c.red}%.5f ${c.green}%.5f ${c.blue}%.5f rg\n"))

  def rect(r: Rect) = {
    log.debug(s"rect: r = $r")
    w(u(f"${r.x}%.2f ${r.y}%.2f ${r.width}%.2f ${r.height}%.2f re\n"))
  }

  val uFill = u("f\n")
  def fill = w(uFill)

  val uClip = u("W\n") // Modifies the current clipping path by intersecting it with the current path, using the nonzero winding rule
  def clip = w(uClip)

}

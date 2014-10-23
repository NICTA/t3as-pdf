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

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.mutable.ArrayBuffer

import org.slf4j.LoggerFactory

import com.itextpdf.text.pdf.{PRStream, PdfArray, PdfName, PdfObject, PdfReader}

object Util {
  private val log = LoggerFactory.getLogger(getClass)

  /** Get raw content for page.
    *
    * TODO: Our purpose is to identify streams for text content filtering.
    * To handle arrays we'd need to return a Seq[Array[Byte]] (or a surrogate such as a checksum).
    *
    * Modified from com.itextpdf.text.pdf.parser.ContentByteUtils (which gets the decoded content).
    */
  def getContentBytesRawForPage(r: PdfReader, pageNum: Int): Option[Array[Byte]] = {
    val pageDic = r.getPageN(pageNum)
    Option(pageDic.get(PdfName.CONTENTS)).flatMap(getContentBytesRawFromContentObject(_))
  }

  /** Get raw content from a content object, which may be a reference, a stream or an array.
    * Array content eventually has to come from streams.
    *
    * Modified from com.itextpdf.text.pdf.parser.ContentByteUtils (which gets the decoded content).
    * @param obj the object to read bytes from
    * @return the raw content bytes
    * @throws IOException
    */
  def getContentBytesRawFromContentObject(obj: PdfObject): Option[Array[Byte]] = {
    obj.`type` match {
      case PdfObject.INDIRECT =>
        val direct = PdfReader.getPdfObjectRelease(obj)
        getContentBytesRawFromContentObject(direct)
      case PdfObject.STREAM =>
        val stream = PdfReader.getPdfObjectRelease(obj).asInstanceOf[PRStream]
        Option(PdfReader.getStreamBytesRaw(stream))
      case PdfObject.ARRAY =>
        log.warn("Util.getContentBytesRawFromContentObject: Got PdfArray, which won't be properly handled. See TODO.")
        val buf = new ArrayBuffer[Byte]
        for {
          ele <- obj.asInstanceOf[PdfArray].listIterator
          raw <- getContentBytesRawFromContentObject(ele)
        } {
          buf ++= raw
          buf += ' '
        }
        Some(buf.toArray)
      case _ => throw new IllegalStateException("Unable to handle Content of type " + obj.getClass)
    }
  }

//  def toStr(b: Byte): String = {
//    f"0x$b%02x" // if (0x20 <= b && b <= 0x7e) f"0x$b%02x (${b.toChar}%c)" else  f"0x$b%02x"
//  }
//
//  def toStr(b: Array[Byte]): String = {
//    b.iterator.map(toStr).mkString("[", " ", "]")
//  }

}

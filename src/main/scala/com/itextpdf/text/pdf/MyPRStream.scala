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
package com.itextpdf.text.pdf
// in this package to get access to package protected items in PRStream and PdfWriter

import java.io.OutputStream
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

class MyPRStream(in: PRStream) extends PRStream(in, null) {
  val log = LoggerFactory.getLogger(getClass)

  /** This is where the stream from the input doc is copied into the output doc, so this is where any editing needs to happen.
    * If bytes is non-null it contains the edited content to be copied instead of the original content stream from the reader.
    */
  override def toPdf(writer: PdfWriter, os: OutputStream): Unit = {
    //    log.debug(s"MyPRStream.toPdf: reader = $reader, offset = $offset, length = $length")
    val b = if (bytes != null) bytes else PdfReader.getStreamBytesRaw(this)
    val crypto = if (writer != null) writer.getEncryption else null
    val objLen = get(PdfName.LENGTH)
    val nn = if (crypto != null) crypto.calculateStreamSize(b.length) else b.length
    put(PdfName.LENGTH, new PdfNumber(nn))
    superToPdf(writer, os)
    put(PdfName.LENGTH, objLen)
    os.write(PdfStream.STARTSTREAM)
    if (length > 0) {
      os.write(if (crypto != null && !crypto.isEmbeddedFilesOnly) crypto.encryptByteArray(b) else b)
    }
    os.write(PdfStream.ENDSTREAM);
  }

}



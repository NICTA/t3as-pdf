package com.itextpdf.text.pdf

/** Provides access to PdfContentByte.escapeString outside the com.itextpdf.text.pdf package */
object EscapeString {
 def escapeString(b: Array[Byte]): Array[Byte] = PdfContentByte.escapeString(b)
}
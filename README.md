# t3as

## Pdf

### Introduction

This project augments [itext](http://itextpdf.com/) with enhanced text extraction and new
redaction capability.

The enhanced text extraction includes:
- recognition and skipping of spurious text in a font with no encoding (OS X generates a spurious "!" at line endings in such a font);
- more accurate text placement using floating point comparisons with a specified tolerance rather than truncation to int and exact comparison; this corrects the placement of list bullet marks;
- monitoring font size changes and vertical white space to generate a blank line after headings and between paragraphs; this helps with human readability and may help with NLP analysis of the text.

The redaction capabilty provides:
- removal of text specified as character offsets into the extracted text
- removal of XMP metadata
- removal of PDF annotations, which can store URLs of linked documents and email addresses
- replacement of native PDF metadata with Producer = "iText® 5.5.2 ©2000-2014 iText Group NV (AGPL-version)" (this is a requirement of itext's AGPL license) and Creator = "Redact v0.1 ©2014 NICTA (AGPL)"

See also: [t3as-redact](https://github.com/NICTA/t3as-redact) which depends on this project.

### License

This software is released under the terms of the [AGPL](http://www.gnu.org/licenses/agpl-3.0.en.html).

### Build
 
 Build and publish to your local Ivy repository (so other projects can use it):
 
    sbt publishLocal

Build a [one-jar](http://one-jar.sourceforge.net/) - jar including all dependencies:

    sbt one-jar

###Run

To run the CLI from sbt:

    sbt
    > run --help
    
To run the CLI from a one-jar:

    java -jar target/scala-2.11/pdf_2.11-0.1-one-jar.jar --help


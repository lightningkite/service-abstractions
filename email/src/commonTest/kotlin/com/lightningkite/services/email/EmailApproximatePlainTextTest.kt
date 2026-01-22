// by Claude
package com.lightningkite.services.email

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EmailApproximatePlainTextTest {

    @Test
    fun basicHtmlTagsAreStripped() {
        val html = "<html><body><p>Hello World</p></body></html>"
        val result = html.emailApproximatePlainText()
        assertFalse(result.contains("<"), "Result should not contain < character from tags")
        assertFalse(result.contains(">"), "Result should not contain > character from tags")
    }

    @Test
    fun brTagsConvertToNewlines() {
        val html = "Line1<br>Line2<br/>Line3<br />Line4"
        val result = html.emailApproximatePlainText()
        // All br variants should become newlines, then collapsed to single newline between content
        assertEquals("Line1\nLine2\nLine3\nLine4", result)
    }

    @Test
    fun pTagsConvertToNewlines() {
        val html = "<p>Paragraph 1</p><p>Paragraph 2</p>"
        val result = html.emailApproximatePlainText()
        assertEquals("\nParagraph 1\nParagraph 2", result)
    }

    @Test
    fun divTagsConvertToNewlines() {
        val html = "<div>Block 1</div><div>Block 2</div>"
        val result = html.emailApproximatePlainText()
        assertEquals("\nBlock 1\nBlock 2", result)
    }

    @Test
    fun liTagsConvertToBulletPoints() {
        val html = "<ul><li>Item 1</li><li>Item 2</li></ul>"
        val result = html.emailApproximatePlainText()
        assertEquals("\n- Item 1\n- Item 2", result)
    }

    @Test
    fun htmlEntitiesAreDecoded() {
        val html = "Less &lt; Greater &gt; Amp &amp; Space&nbsp;here"
        val result = html.emailApproximatePlainText()
        assertEquals("Less < Greater > Amp & Space here", result)
    }

    @Test
    fun multipleSpacesCollapsedToSingle() {
        val html = "Word1     Word2          Word3"
        val result = html.emailApproximatePlainText()
        assertEquals("Word1 Word2 Word3", result)
    }

    @Test
    fun multipleNewlinesCollapsedToSingle() {
        val html = "Line1<br><br><br><br>Line2"
        val result = html.emailApproximatePlainText()
        assertEquals("Line1\nLine2", result)
    }

    @Test
    fun noExcessiveWhitespaceFromNestedDivs() {
        val html = """
            <div>
                <div>
                    <div>
                        Deeply nested content
                    </div>
                </div>
            </div>
        """.trimIndent()
        val result = html.emailApproximatePlainText()
        // Should not have excessive whitespace - multiple newlines collapse to one
        assertFalse(result.contains("\n\n"), "Result should not contain consecutive newlines: '$result'")
        assertFalse(result.contains("  "), "Result should not contain consecutive spaces: '$result'")
    }

    @Test
    fun noExcessiveWhitespaceFromTableStructure() {
        val html = """
            <table>
                <tr>
                    <td>Cell 1</td>
                    <td>Cell 2</td>
                </tr>
                <tr>
                    <td>Cell 3</td>
                    <td>Cell 4</td>
                </tr>
            </table>
        """.trimIndent()
        val result = html.emailApproximatePlainText()
        assertFalse(result.contains("\n\n"), "Result should not contain consecutive newlines: '$result'")
        assertFalse(result.contains("  "), "Result should not contain consecutive spaces: '$result'")
    }

    @Test
    fun noExcessiveWhitespaceFromComplexEmail() {
        // Simulate a typical HTML email structure
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Email</title>
            </head>
            <body>
                <div style="max-width: 600px; margin: 0 auto;">
                    <div class="header">
                        <h1>Welcome!</h1>
                    </div>
                    <div class="content">
                        <p>Hello there,</p>
                        <p>This is an important message.</p>
                        <ul>
                            <li>First point</li>
                            <li>Second point</li>
                        </ul>
                    </div>
                    <div class="footer">
                        <p>Thanks!</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
        val result = html.emailApproximatePlainText()
        assertFalse(result.contains("\n\n"), "Result should not contain consecutive newlines")
        assertFalse(result.contains("  "), "Result should not contain consecutive spaces")
        // Content should still be present
        assert(result.contains("Welcome!"))
        assert(result.contains("Hello there,"))
        assert(result.contains("- First point"))
        assert(result.contains("- Second point"))
        assert(result.contains("Thanks!"))
    }

    @Test
    fun spacesFromStrippedTagsCollapse() {
        // When tags with spaces around them are stripped, spaces should collapse
        val html = "<span>word1</span>  <span>word2</span>   <span>word3</span>"
        val result = html.emailApproximatePlainText()
        assertEquals("word1 word2 word3", result)
    }

    @Test
    fun emptyDivsDoNotCreateExcessiveNewlines() {
        val html = "<div></div><div></div><div></div><div>Content</div>"
        val result = html.emailApproximatePlainText()
        // Empty divs shouldn't create blank lines - newlines should collapse
        assertFalse(result.contains("\n\n"), "Empty divs should not create excessive newlines: '$result'")
    }

    @Test
    fun preservesTextContentCorrectly() {
        val html = "<p>The quick brown fox jumps over the lazy dog.</p>"
        val result = html.emailApproximatePlainText()
        assert(result.contains("The quick brown fox jumps over the lazy dog."))
    }

    @Test
    fun spaceNewlineMix() {
        val html = "Some\n \n \n Stuff"
        val result = html.emailApproximatePlainText()
        assert(result.contains("Some\nStuff"))
    }

    @Test
    fun handlesRealWorldEmailTemplate() {
        // Test with a more realistic email structure that might cause whitespace issues
        val html = """
            <div style="font-family: Arial, sans-serif;">
                <div style="background: #f5f5f5; padding: 20px;">
                    <img src="logo.png" alt="Company Logo">
                </div>

                <div style="padding: 20px;">
                    <p>Dear Customer,</p>

                    <p>Thank you for your order #12345.</p>

                    <table style="width: 100%;">
                        <tr>
                            <td>Product</td>
                            <td>Quantity</td>
                            <td>Price</td>
                        </tr>
                        <tr>
                            <td>Widget</td>
                            <td>2</td>
                            <td>$19.99</td>
                        </tr>
                    </table>

                    <p>Total: $39.98</p>
                </div>

                <div style="background: #333; color: white; padding: 10px;">
                    <p>Contact us: support@example.com</p>
                </div>
            </div>
        """.trimIndent()

        val result = html.emailApproximatePlainText()
        println(result)

        // No excessive whitespace
        assertFalse(result.contains("\n\n"), "Should not have consecutive newlines: '$result'")
        assertFalse(result.contains("  "), "Should not have consecutive spaces: '$result'")

        // Key content preserved
        assert(result.contains("Dear Customer"))
        assert(result.contains("Thank you for your order #12345"))
        assert(result.contains("Total:"))
        assert(result.contains("support@example.com"))
    }
}

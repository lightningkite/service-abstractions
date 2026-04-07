// by Claude
package com.lightningkite.services.database

import com.lightningkite.services.data.TextIndex
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Condition.FullTextSearch local execution to verify it correctly
 * uses the @TextIndex annotation fields to determine what to search on.
 */
class FullTextSearchTest {

    // Model WITH TextIndex annotation - should only search specified fields
    @Serializable
    @TextIndex(["title", "description"])
    data class ArticleWithIndex(
        val id: Int = 0,
        val title: String = "",
        val description: String = "",
        val authorName: String = "",  // NOT in TextIndex, should not be searched
        val internalNotes: String = ""  // NOT in TextIndex, should not be searched
    )

    // Model WITHOUT TextIndex annotation - should fall back to toString()
    @Serializable
    data class ArticleWithoutIndex(
        val id: Int = 0,
        val title: String = "",
        val description: String = "",
        val authorName: String = ""
    )

    // Model with nested field in TextIndex
    @Serializable
    @TextIndex(["name", "metadata.category"])
    data class ProductWithNestedIndex(
        val id: Int = 0,
        val name: String = "",
        val price: Double = 0.0,
        val metadata: ProductMetadata = ProductMetadata()
    )

    @Serializable
    data class ProductMetadata(
        val category: String = "",
        val tags: List<String> = emptyList(),
        val internalCode: String = ""  // NOT in TextIndex
    )

    // Model with multiple nested levels
    @Serializable
    @TextIndex(["name", "details.info.summary"])
    data class DeepNestedModel(
        val id: Int = 0,
        val name: String = "",
        val details: DeepDetails = DeepDetails()
    )

    @Serializable
    data class DeepDetails(
        val info: DeepInfo = DeepInfo()
    )

    @Serializable
    data class DeepInfo(
        val summary: String = "",
        val secret: String = ""  // NOT in TextIndex
    )

    // ========== Tests for TextIndex annotation being used ==========

    @Test
    fun `FullTextSearch should match text in indexed fields`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("kotlin")
        val article = ArticleWithIndex(
            title = "Learning Kotlin",
            description = "A guide to programming"
        )
        assertTrue(condition(article), "Should match 'kotlin' in title field")
    }

    @Test
    fun `FullTextSearch should match text in second indexed field`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("programming")
        val article = ArticleWithIndex(
            title = "Learning Kotlin",
            description = "A guide to programming"
        )
        assertTrue(condition(article), "Should match 'programming' in description field")
    }

    @Test
    fun `FullTextSearch should NOT match text in non-indexed fields when TextIndex is present`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("SecretAuthor")
        val article = ArticleWithIndex(
            title = "Learning Kotlin",
            description = "A guide to programming",
            authorName = "SecretAuthor",  // This field is NOT in @TextIndex
            internalNotes = "Some notes"
        )
        assertFalse(condition(article), "Should NOT match 'SecretAuthor' because authorName is not in @TextIndex")
    }

    @Test
    fun `FullTextSearch should NOT match text in internalNotes when not in TextIndex`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("confidential")
        val article = ArticleWithIndex(
            title = "Public Article",
            description = "Public description",
            authorName = "John",
            internalNotes = "This is confidential information"  // NOT in @TextIndex
        )
        assertFalse(condition(article), "Should NOT match 'confidential' because internalNotes is not in @TextIndex")
    }

    // ========== Tests for fallback to toString() without TextIndex ==========
    // NOTE: Without @TextIndex, FullTextSearch falls back to toString() which produces
    // data class debug format like "ClassName(field=value, ...)". This format doesn't
    // work well for text search because field values are embedded with field names.
    // Users should add @TextIndex annotation for proper full-text search support.

    @Test
    fun `FullTextSearch without TextIndex falls back to toString but has limited utility`() {
        // toString() produces: "ArticleWithoutIndex(id=0, title=Some Title, description=Some description, authorName=AuthorJohn)"
        // When split by spaces, words are: "ArticleWithoutIndex(id=0,", "title=Some", "Title,", "description=Some", "description,", "authorName=AuthorJohn)"
        // Fuzzy matching won't work well because values are concatenated with field names
        val condition = Condition.FullTextSearch<ArticleWithoutIndex>("AuthorJohn")
        val article = ArticleWithoutIndex(
            title = "Some Title",
            description = "Some description",
            authorName = "AuthorJohn"  // Becomes "authorName=AuthorJohn)" in toString()
        )
        // The word "authorName=AuthorJohn)" has Levenshtein distance > 2 from "AuthorJohn"
        // This documents that toString() fallback doesn't cleanly extract field values
        assertFalse(condition(article), "toString() fallback doesn't cleanly extract embedded field values")
    }

    @Test
    fun `FullTextSearch without TextIndex can match standalone words in toString`() {
        // toString() produces: "ArticleWithoutIndex(id=0, title=Hello World, ...)"
        // Split by spaces includes "World," as a word
        val condition = Condition.FullTextSearch<ArticleWithoutIndex>("World")
        val article = ArticleWithoutIndex(
            title = "Hello World",  // "World," becomes a separate word when split by spaces
            description = "Test",
            authorName = "Test"
        )
        // "World" (5 chars) fuzzy matches "World," (6 chars) within Levenshtein distance 2
        assertTrue(condition(article), "Can match standalone words that appear separated by spaces")
    }

    @Test
    fun `FullTextSearch without TextIndex - recommendation to use TextIndex`() {
        // This test documents that @TextIndex is recommended for reliable full-text search
        // Without it, search results depend on data class toString() format which is unpredictable

        // With @TextIndex, search is reliable:
        val articleWithIndex = ArticleWithIndex(
            title = "Important Document",
            description = "Contains crucial information",
            authorName = "Secret Author"  // NOT indexed
        )
        val indexedCondition = Condition.FullTextSearch<ArticleWithIndex>("Document")
        assertTrue(indexedCondition(articleWithIndex), "With @TextIndex, search works on indexed fields")

        // Without @TextIndex, same search is unreliable:
        val articleWithoutIndex = ArticleWithoutIndex(
            title = "Important Document",
            description = "Contains crucial information",
            authorName = "Secret Author"
        )
        // "Document" should still work because it appears as a space-separated word
        val nonIndexedCondition = Condition.FullTextSearch<ArticleWithoutIndex>("Document")
        // This may or may not work depending on toString() format
        assertTrue(nonIndexedCondition(articleWithoutIndex), "Without @TextIndex, matching depends on toString() format")
    }

    // ========== Tests for nested field paths in TextIndex ==========

    @Test
    fun `FullTextSearch should search nested fields specified in TextIndex`() {
        val condition = Condition.FullTextSearch<ProductWithNestedIndex>("electronics")
        val product = ProductWithNestedIndex(
            name = "Phone",
            metadata = ProductMetadata(
                category = "Electronics",
                internalCode = "SECRET123"
            )
        )
        assertTrue(condition(product), "Should match 'electronics' in nested metadata.category field")
    }

    @Test
    fun `FullTextSearch should NOT match nested field not in TextIndex`() {
        val condition = Condition.FullTextSearch<ProductWithNestedIndex>("SECRET123")
        val product = ProductWithNestedIndex(
            name = "Phone",
            metadata = ProductMetadata(
                category = "Electronics",
                internalCode = "SECRET123"  // NOT in @TextIndex
            )
        )
        assertFalse(condition(product), "Should NOT match 'SECRET123' because metadata.internalCode is not in @TextIndex")
    }

    @Test
    fun `FullTextSearch should search top-level field along with nested`() {
        val condition = Condition.FullTextSearch<ProductWithNestedIndex>("phone")
        val product = ProductWithNestedIndex(
            name = "Phone Case",
            metadata = ProductMetadata(category = "Accessories")
        )
        assertTrue(condition(product), "Should match 'phone' in top-level name field")
    }

    // ========== Tests for deeply nested fields ==========

    @Test
    fun `FullTextSearch should handle deeply nested field paths`() {
        val condition = Condition.FullTextSearch<DeepNestedModel>("important")
        val model = DeepNestedModel(
            name = "Test",
            details = DeepDetails(
                info = DeepInfo(
                    summary = "This is important information",
                    secret = "Hidden data"
                )
            )
        )
        assertTrue(condition(model), "Should match 'important' in deeply nested details.info.summary field")
    }

    @Test
    fun `FullTextSearch should NOT match deeply nested non-indexed field`() {
        val condition = Condition.FullTextSearch<DeepNestedModel>("hidden")
        val model = DeepNestedModel(
            name = "Test",
            details = DeepDetails(
                info = DeepInfo(
                    summary = "Public summary",
                    secret = "Hidden data"  // NOT in @TextIndex
                )
            )
        )
        assertFalse(condition(model), "Should NOT match 'hidden' because details.info.secret is not in @TextIndex")
    }

    // ========== Tests for fuzzy matching ==========

    @Test
    fun `FullTextSearch should support fuzzy matching with Levenshtein distance`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("kotlinn", levenshteinDistance = 2)  // typo
        val article = ArticleWithIndex(
            title = "Learning Kotlin",
            description = "A guide"
        )
        assertTrue(condition(article), "Should fuzzy match 'kotlinn' to 'kotlin' with Levenshtein distance 2")
    }

    @Test
    fun `FullTextSearch should respect Levenshtein distance limit`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("kotxxxn", levenshteinDistance = 1)  // too many differences
        val article = ArticleWithIndex(
            title = "Learning Kotlin",
            description = "A guide"
        )
        assertFalse(condition(article), "Should NOT match 'kotxxxn' to 'kotlin' with Levenshtein distance 1")
    }

    // ========== Tests for case insensitivity ==========

    @Test
    fun `FullTextSearch should be case insensitive`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("KOTLIN")
        val article = ArticleWithIndex(
            title = "Learning kotlin",
            description = "A guide"
        )
        assertTrue(condition(article), "Should match 'KOTLIN' to 'kotlin' case-insensitively")
    }

    // ========== Tests for multiple search terms ==========

    @Test
    fun `FullTextSearch should require all terms present by default`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("kotlin programming", requireAllTermsPresent = true)
        val article = ArticleWithIndex(
            title = "Learning Kotlin",
            description = "A guide to programming"
        )
        assertTrue(condition(article), "Should match when both 'kotlin' and 'programming' are present across indexed fields")
    }

    @Test
    fun `FullTextSearch should fail when not all required terms are present`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("kotlin java", requireAllTermsPresent = true)
        val article = ArticleWithIndex(
            title = "Learning Kotlin",
            description = "A guide to programming"
        )
        assertFalse(condition(article), "Should NOT match when 'java' is missing from indexed fields")
    }

    // ========== Edge cases ==========

    @Test
    fun `FullTextSearch should handle empty indexed fields gracefully`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("anything")
        val article = ArticleWithIndex(
            title = "",
            description = ""
        )
        assertFalse(condition(article), "Should NOT match when indexed fields are empty")
    }

    @Test
    fun `FullTextSearch should handle empty search query`() {
        val condition = Condition.FullTextSearch<ArticleWithIndex>("")
        val article = ArticleWithIndex(
            title = "Some title",
            description = "Some description"
        )
        // Empty query should match everything (vacuous truth)
        assertTrue(condition(article), "Empty search query should match (vacuous truth)")
    }
}

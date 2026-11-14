package dev.openwrtmanager.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {
    @Test
    fun plain_value_is_wrapped() = assertEquals("'abc'", shellQuote("abc"))

    @Test
    fun single_quote_is_escaped() = assertEquals("'it'\\''s'", shellQuote("it's"))

    @Test
    fun metacharacters_stay_literal() =
        assertEquals("'a b;rm -rf \$x `y`'", shellQuote("a b;rm -rf \$x `y`"))

    @Test
    fun empty_value_is_empty_quotes() = assertEquals("''", shellQuote(""))

    @Test
    fun unicode_is_preserved() = assertEquals("'Café 📶'", shellQuote("Café 📶"))
}

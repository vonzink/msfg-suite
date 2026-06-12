package com.msfg.los.documents.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTextTest {

    @Test
    void escapesAmpersand() {
        assertThat(HtmlText.escape("Mortgage & Financial")).isEqualTo("Mortgage &amp; Financial");
    }

    @Test
    void escapesAngleBrackets() {
        assertThat(HtmlText.escape("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void escapesDoubleQuote() {
        assertThat(HtmlText.escape("say \"hi\"")).isEqualTo("say &quot;hi&quot;");
    }

    @Test
    void escapesSingleQuote() {
        assertThat(HtmlText.escape("O'Brien")).isEqualTo("O&#39;Brien");
    }

    // Ampersand must be escaped first — pre-escaped input is re-escaped, never left as live markup.
    @Test
    void doesNotDoubleDecodeExistingEntities() {
        assertThat(HtmlText.escape("&lt;b&gt;")).isEqualTo("&amp;lt;b&amp;gt;");
    }

    @Test
    void escapesAllSpecialsTogether() {
        assertThat(HtmlText.escape("<a href=\"x\" onclick='y'>&"))
                .isEqualTo("&lt;a href=&quot;x&quot; onclick=&#39;y&#39;&gt;&amp;");
    }

    @Test
    void plainTextUnchanged() {
        assertThat(HtmlText.escape("Jane Smith-Jones, Jr. 123")).isEqualTo("Jane Smith-Jones, Jr. 123");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(HtmlText.escape(null)).isEmpty();
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(HtmlText.escape("")).isEmpty();
    }
}

package com.luohuo.flex.im.common.algorithm.ac;

import com.luohuo.flex.im.common.utils.sensitiveword.ACFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ACTrieTest {

    @Test
    void reportsChineseMatchOffsetsAndDeduplicatesWords() {
        ACTrie trie = new ACTrie(List.of("敏感", "违禁", "敏感"));
        List<MatchResult> matches = trie.matches("含敏感及违禁词");

        assertEquals(2, matches.size());
        assertEquals(1, matches.get(0).getStartIndex());
        assertEquals(3, matches.get(0).getEndIndex());
        assertEquals(4, matches.get(1).getStartIndex());
        assertEquals(6, matches.get(1).getEndIndex());
    }

    @Test
    void followsFailureLinksForOverlappingMatches() {
        List<MatchResult> matches = new ACTrie(List.of("aba", "bab")).matches("abab");

        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).getStartIndex());
        assertEquals(3, matches.get(0).getEndIndex());
        assertEquals(1, matches.get(1).getStartIndex());
        assertEquals(4, matches.get(1).getEndIndex());
    }

    @Test
    void returnsNoMatchesForEmptyOrUnmatchedTextAndEmptyDictionary() {
        ACTrie trie = new ACTrie(List.of("敏感"));

        assertTrue(trie.matches("").isEmpty());
        assertTrue(trie.matches("普通消息").isEmpty());
        assertTrue(new ACTrie(List.of()).matches("普通消息").isEmpty());
    }

    @Test
    void filterMasksMatchesAndPreservesUnmatchedAndBlankText() {
        ACFilter filter = new ACFilter();
        filter.loadWord(List.of("敏感", "违禁"));

        assertEquals("含**及**词", filter.filter("含敏感及违禁词"));
        assertTrue(filter.hasSensitiveWord("含敏感词"));
        assertEquals("普通消息", filter.filter("普通消息"));
        assertFalse(filter.hasSensitiveWord("普通消息"));
        assertEquals("  ", filter.filter("  "));
        assertFalse(filter.hasSensitiveWord("  "));
        assertNull(filter.filter(null));
        assertFalse(filter.hasSensitiveWord(null));
    }
}

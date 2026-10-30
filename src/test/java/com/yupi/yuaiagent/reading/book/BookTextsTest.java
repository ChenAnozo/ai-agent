package com.yupi.yuaiagent.reading.book;

import com.yupi.yuaiagent.reading.persistence.*;
import com.yupi.yuaiagent.reading.persistence.mapper.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookTextsTest {
    @Test void loadsCurrentIngestedTextWithoutMapAndRejectsMissingPositions() {
        var store = mock(ReadingStore.class);
        var texts = mock(ReadingBookStore.class);
        var kb = new ReadingKbEntity();
        kb.setId("book"); kb.setName("书"); kb.setGeneration("gen"); kb.setStatus("READY"); kb.setChunkCount(1);
        when(store.knowledgeBase("book")).thenReturn(kb);
        var loader = new BookTexts(store, texts);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> loader.load("book")).getMessage().contains("重新入库"));
        var document = new ReadingBookDocumentEntity();
        document.setTextContent("原文");
        when(texts.document("book", "gen")).thenReturn(document);
        assertThrows(IllegalArgumentException.class, () -> loader.load("book"));
        when(texts.chunks("book", "gen")).thenReturn(List.of(new ReadingBookChunkEntity()));
        assertEquals("原文", loader.load("book").text());
        kb.setGeneration("new");
        assertThrows(IllegalArgumentException.class, () -> loader.load("book"));
        kb.setStatus("EMBEDDING");
        assertThrows(IllegalArgumentException.class, () -> loader.load("book"));
    }

    @Test void reciprocalRankFusionCombinesRanks() {
        assertTrue(BookSearch.rrf(2, 2) > BookSearch.rrf(1, 0));
        assertEquals(0, BookSearch.rrf(0, 0));
    }
}

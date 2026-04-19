package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupabaseAiBookmarkResponseParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void lenientFallbackWhenPageNumberIsNotCoercibleByJackson() throws Exception {
        /* Tipo non mappabile su Integer: Jackson fallisce su tutta la lista; il parser tollerante mantiene titolo e figli. */
        String json =
                "{\"paid\":true,\"bookmarks\":[{\"title\":\"Cap\",\"page_number\":{\"raw\":\"x\"},\"children\":[]}]}";
        List<AiBookmark> list = SupabaseAiClient.parseBookmarksFromResponseNode(MAPPER.readTree(json));
        assertEquals(1, list.size());
        assertEquals("Cap", list.get(0).getTitle());
        assertEquals(null, list.get(0).getPageNumber());
    }

    @Test
    void findsBookmarksInsideDataWrapper() throws Exception {
        String json =
                "{\"data\":{\"paid\":true,\"bookmarks\":[{\"title\":\"InData\",\"page_number\":5}]}}";
        List<AiBookmark> list = SupabaseAiClient.parseBookmarksFromResponseNode(MAPPER.readTree(json));
        assertEquals(1, list.size());
        assertEquals("InData", list.get(0).getTitle());
        assertEquals(5, list.get(0).getPageNumber());
    }

    @Test
    void parsesNestedChildrenWithAliasPageField() throws Exception {
        String json =
                "{\"bookmarks\":[{\"title\":\"P\",\"page\":\"3\",\"page_label_raw\":\"iii\",\"children\":[{\"title\":\"C\",\"page_number\":2}]}]}";
        List<AiBookmark> list = SupabaseAiClient.parseBookmarksFromResponseNode(MAPPER.readTree(json));
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).getPageNumber());
        assertEquals("iii", list.get(0).getPageLabelRaw());
        assertEquals(1, list.get(0).getChildrenView().size());
        assertEquals("C", list.get(0).getChildrenView().get(0).getTitle());
    }
}

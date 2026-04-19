/*
 * StripeCatalogPricesTest.java
 *
 * Copyright (c) 2010 Flaviano Petrocchi <flavianopetrocchi at gmail.com>.
 * All rights reserved.
 *
 * This file is part of JPdfBookmarks.
 *
 * JPdfBookmarks is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPdfBookmarks is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JPdfBookmarks.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StripeCatalogPricesTest {

    @Test
    void parseFlatStandardAndAdvanced() {
        String json =
                "{\"standard\":{\"unit_amount\":115,\"currency\":\"eur\"},"
                        + "\"advanced\":{\"unit_amount\":355,\"currency\":\"eur\"}}";
        Optional<StripeCatalogPrices.Result> r = StripeCatalogPrices.parse(json);
        assertTrue(r.isPresent());
        assertEquals(115, r.get().standard().unitAmount());
        assertEquals(355, r.get().advanced().unitAmount());
    }

    @Test
    void parseDataWrapperAndNestedPrice() {
        String json =
                "{\"data\":{\"standard\":{\"price\":{\"unit_amount\":99,\"currency\":\"usd\"}},"
                        + "\"advanced\":{\"unit_amount\":50,\"currency\":\"eur\"}}}";
        Optional<StripeCatalogPrices.Result> r = StripeCatalogPrices.parse(json);
        assertTrue(r.isPresent());
        assertEquals(99, r.get().standard().unitAmount());
        assertEquals("usd", r.get().standard().currencyCode());
        assertEquals(50, r.get().advanced().unitAmount());
    }

    @Test
    void formatEurItalyLocale() {
        var tier = new StripeCatalogPrices.TierPrice(115, "eur");
        String s = StripeCatalogPrices.format(tier, Locale.ITALY);
        assertTrue(s.contains("1"), s);
        assertTrue(s.contains("15") || s.contains(",15"), s);
    }
}

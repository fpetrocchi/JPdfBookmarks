/*
 * StripeCatalogPrices.java
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

/**
 * JSON pubblicato da un endpoint server (es. Supabase Edge Function con chiave Stripe segreta lato server) così che il
 * client mostri importi aggiornati senza redistribuire il JAR. Contratto consigliato (campi come gli oggetti Price di
 * Stripe):
 *
 * <pre>{@code
 * {
 *   "standard": { "unit_amount": 115, "currency": "eur" },
 *   "advanced": { "unit_amount": 355, "currency": "eur" }
 * }
 * }</pre>
 *
 * {@code unit_amount} è nell'unità minima della valuta (centesimi per EUR/USD; per valute senza decimali coincide con
 * l'importo intero). Opzionale: radice {@code "data": { ... }} con gli stessi campi {@code standard}/{@code advanced},
 * oppure un oggetto tier con sotto-oggetto {@code "price": { "unit_amount", "currency" }}.
 */
public final class StripeCatalogPrices {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Prezzo di un tier (es. Standard o Advanced). */
    public record TierPrice(long unitAmount, String currencyCode) {}

    /** Estrazione di uno o due tier; ciascuno può essere assente se il JSON non lo definisce. */
    public record Result(TierPrice standard, TierPrice advanced) {}

    private StripeCatalogPrices() {}

    /**
     * Interpreta il corpo JSON; in caso di formato non riconosciuto o tier incompleti restituisce {@link Optional#empty()}.
     */
    public static Optional<Result> parse(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(jsonBody);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode base = root;
            JsonNode data = root.get("data");
            if (data != null && data.isObject()) {
                base = data;
            }
            TierPrice std = parseTier(base.get("standard"));
            if (std == null) {
                std = parseTier(base.get("mini"));
            }
            TierPrice adv = parseTier(base.get("advanced"));
            if (std == null && adv == null) {
                return Optional.empty();
            }
            return Optional.of(new Result(std, adv));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Formatta usando {@link Locale#getDefault(Locale.Category#FORMAT)}. */
    public static String format(TierPrice tier) {
        return format(tier, Locale.getDefault(Locale.Category.FORMAT));
    }

    /** Formatta l'importo nella valuta indicata, rispettando i decimali ISO della valuta. */
    public static String format(TierPrice tier, Locale locale) {
        if (tier == null) {
            return "";
        }
        Locale loc = locale != null ? locale : Locale.getDefault(Locale.Category.FORMAT);
        String code = tier.currencyCode != null ? tier.currencyCode.trim().toUpperCase(Locale.ROOT) : "";
        if (code.isEmpty()) {
            return "";
        }
        try {
            Currency cur = Currency.getInstance(code);
            int fraction = cur.getDefaultFractionDigits();
            BigDecimal amount = BigDecimal.valueOf(tier.unitAmount);
            if (fraction > 0) {
                amount = amount.movePointLeft(fraction);
            }
            NumberFormat nf = NumberFormat.getCurrencyInstance(loc);
            nf.setCurrency(cur);
            return nf.format(amount);
        } catch (Exception e) {
            return "";
        }
    }

    private static TierPrice parseTier(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        JsonNode obj = n;
        if (n.isObject()) {
            JsonNode price = n.get("price");
            if (price != null && price.isObject()) {
                obj = price;
            }
        } else {
            return null;
        }
        long ua = readLongUnitAmount(obj.get("unit_amount"));
        if (ua < 0) {
            return null;
        }
        String cur = readText(obj.get("currency"));
        if (cur == null || cur.isBlank()) {
            return null;
        }
        return new TierPrice(ua, cur.trim());
    }

    private static long readLongUnitAmount(JsonNode n) {
        if (n == null || n.isNull()) {
            return -1;
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isFloatingPointNumber()) {
            double d = n.asDouble();
            if (Double.isFinite(d)) {
                return Math.round(d);
            }
            return -1;
        }
        if (n.isTextual()) {
            String s = n.asText().trim();
            if (s.isEmpty()) {
                return -1;
            }
            try {
                return Long.parseLong(s, 10);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static String readText(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        if (n.isTextual()) {
            return n.asText();
        }
        return String.valueOf(n);
    }
}

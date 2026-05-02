/*
 * AiPreviewPaymentConfig.java
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

package it.flavianopetrocchi.jpdfbookmarks;

import java.util.Objects;

/**
 * URL e chiave necessari a {@link AiPreviewDialog} per avvio checkout (backend), polling pagamento e download risultati.
 * L'URL di checkout è opaco (es. Edge Function {@code create-checkout}), senza dipendere dal provider di pagamento.
 */
public record AiPreviewPaymentConfig(
        String supabaseAnonKey,
        String cloudCheckPaymentUrl,
        String cloudFetchFullResultsUrl,
        String cloudCreateCheckoutUrl,
        String cloudStripePricesUrl,
        String cloudProcessIndexUrl) {

    public static AiPreviewPaymentConfig fromPrefs(Prefs prefs) {
        Objects.requireNonNull(prefs, "prefs");
        return new AiPreviewPaymentConfig(
                prefs.getCloudSupabaseAnonKey().trim(),
                prefs.getCloudCheckPaymentUrl().trim(),
                prefs.getCloudFetchFullResultsUrl().trim(),
                prefs.getCloudCreateCheckoutUrl().trim(),
                prefs.getCloudStripePricesUrl().trim(),
                prefs.getCloudProcessIndexUrl().trim());
    }
}

/*
 * AiCloudBundledDefaults.java
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional {@code ai-cloud-defaults.properties} on the classpath (Maven-filtered for release builds).
 * Used when user preferences leave cloud URLs/keys empty.
 */
final class AiCloudBundledDefaults {

    private static final Logger LOG = Logger.getLogger(AiCloudBundledDefaults.class.getName());
    private static final String RESOURCE = "it/flavianopetrocchi/jpdfbookmarks/ai-cloud-defaults.properties";

    private static final AiCloudBundledDefaults INSTANCE = load();

    private final String processIndexUrl;
    private final String anonKey;
    private final String checkPaymentUrl;
    private final String fetchFullUrl;
    private final String stripeCheckoutMiniUrl;
    private final String stripeCheckoutAdvancedUrl;
    private final String stripePricesUrl;

    private AiCloudBundledDefaults(
            String processIndexUrl,
            String anonKey,
            String checkPaymentUrl,
            String fetchFullUrl,
            String stripeCheckoutMiniUrl,
            String stripeCheckoutAdvancedUrl,
            String stripePricesUrl) {
        this.processIndexUrl = processIndexUrl;
        this.anonKey = anonKey;
        this.checkPaymentUrl = checkPaymentUrl;
        this.fetchFullUrl = fetchFullUrl;
        this.stripeCheckoutMiniUrl = stripeCheckoutMiniUrl;
        this.stripeCheckoutAdvancedUrl = stripeCheckoutAdvancedUrl;
        this.stripePricesUrl = stripePricesUrl;
    }

    static AiCloudBundledDefaults get() {
        return INSTANCE;
    }

    boolean isCloudConfigComplete() {
        return !processIndexUrl.isEmpty() && !anonKey.isEmpty();
    }

    String processIndexUrl() {
        return processIndexUrl;
    }

    String anonKey() {
        return anonKey;
    }

    String checkPaymentUrl() {
        return checkPaymentUrl;
    }

    String fetchFullUrl() {
        return fetchFullUrl;
    }

    String stripeCheckoutMiniUrl() {
        return stripeCheckoutMiniUrl;
    }

    String stripeCheckoutAdvancedUrl() {
        return stripeCheckoutAdvancedUrl;
    }

    String stripePricesUrl() {
        return stripePricesUrl;
    }

    private static AiCloudBundledDefaults load() {
        Properties p = new Properties();
        try (InputStream in =
                AiCloudBundledDefaults.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load " + RESOURCE, e);
        }
        return new AiCloudBundledDefaults(
                trimOrEmpty(p.getProperty("ai.cloud.process.index.url")),
                trimOrEmpty(p.getProperty("ai.cloud.anon.key")),
                trimOrEmpty(p.getProperty("ai.cloud.check.payment.url")),
                trimOrEmpty(p.getProperty("ai.cloud.fetch.full.url")),
                trimOrEmpty(p.getProperty("ai.cloud.stripe.checkout.mini.url")),
                trimOrEmpty(p.getProperty("ai.cloud.stripe.checkout.advanced.url")),
                trimOrEmpty(p.getProperty("ai.cloud.stripe.prices.url")));
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }
}

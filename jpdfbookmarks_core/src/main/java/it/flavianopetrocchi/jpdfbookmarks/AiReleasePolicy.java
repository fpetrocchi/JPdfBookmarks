/*
 * AiReleasePolicy.java
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

/**
 * Distribuzione agli utenti finali nasconde la scheda IA in Opzioni; l'estrazione indice passa solo dal backend cloud
 * (URL/chiavi nel bundle Maven). Il flag di sviluppo riabilita la scheda e la configurazione locale OpenAI/Ollama.
 *
 * @see OptionsDlg#isAiOptionsTabVisible()
 */
public final class AiReleasePolicy {

    private AiReleasePolicy() {}

    /**
     * Build di sviluppo: {@code -Djpdfbookmarks.dev.showAiOptions=true} mostra Opzioni » Intelligenza artificiale.
     * Build utente (default {@code false}): nessuna preferenza IA avanzata; solo cloud quando gli endpoint bundled sono disponibili.
     */
    public static boolean isAdvancedAiOptionsTabEnabled() {
        return Boolean.getBoolean("jpdfbookmarks.dev.showAiOptions");
    }
}

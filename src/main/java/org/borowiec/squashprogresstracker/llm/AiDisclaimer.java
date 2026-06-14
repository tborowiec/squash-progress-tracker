package org.borowiec.squashprogresstracker.llm;

import org.borowiec.squashprogresstracker.user.Locale;

public final class AiDisclaimer {

    public static final String TEXT = "AI-generated advice — not factual analysis. Verify before relying on it.";

    private static final String PL_TEXT =
            "Porada wygenerowana przez AI — nie jest to analiza faktyczna. Zweryfikuj przed zastosowaniem.";

    private AiDisclaimer() {}

    public static String text(Locale locale) {
        return locale == Locale.PL ? PL_TEXT : TEXT;
    }
}

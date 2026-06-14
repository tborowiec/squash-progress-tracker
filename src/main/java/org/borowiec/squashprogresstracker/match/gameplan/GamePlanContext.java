package org.borowiec.squashprogresstracker.match.gameplan;

import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.borowiec.squashprogresstracker.user.Locale;

public record GamePlanContext(LlmRequest request, int matchCount, boolean lowData, Locale locale) {}

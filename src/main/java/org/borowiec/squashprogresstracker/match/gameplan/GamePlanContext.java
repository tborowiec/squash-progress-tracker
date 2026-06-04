package org.borowiec.squashprogresstracker.match.gameplan;

import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;

public record GamePlanContext(LlmRequest request, int matchCount, boolean lowData) {}

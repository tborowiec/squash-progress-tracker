package org.borowiec.squashprogresstracker.match.gameplan;

import org.borowiec.squashprogresstracker.llm.AiDisclaimer;
import org.borowiec.squashprogresstracker.llm.client.LlmException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@RestController
@RequestMapping("/api/game-plans")
public class GamePlanController {

    private final GamePlanService service;
    private final ObjectMapper objectMapper;

    public GamePlanController(GamePlanService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/stream")
    public SseEmitter stream(@RequestParam String opponent) {
        // prepare() runs on the request thread: reads SecurityContextHolder and closes the DB tx here
        var context = service.prepare(opponent);
        var emitter = new SseEmitter(-1L);

        Thread.ofVirtual().start(() -> {
            try {
                emitter.send(SseEmitter.event().name("meta").data(
                        objectMapper.writeValueAsString(new MetaPayload(
                                AiDisclaimer.TEXT, context.matchCount(), context.lowData()))));

                service.stream(context, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(
                                objectMapper.writeValueAsString(new TokenPayload(token))));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (LlmException e) {
                sendErrorAndComplete(emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void sendErrorAndComplete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data("{\"message\":\"AI service is temporarily unavailable\"}"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    record MetaPayload(String disclaimer, int matchCount, boolean lowData) {}
    record TokenPayload(String t) {}
}

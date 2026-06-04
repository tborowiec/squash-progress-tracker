package org.borowiec.squashprogresstracker.match;

import jakarta.validation.Valid;
import org.borowiec.squashprogresstracker.match.dto.CreateMatchRequest;
import org.borowiec.squashprogresstracker.match.dto.MatchParseResult;
import org.borowiec.squashprogresstracker.match.dto.MatchResponse;
import org.borowiec.squashprogresstracker.match.dto.ParseMatchRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final MatchService matchService;
    private final MatchParseService matchParseService;

    public MatchController(MatchService matchService, MatchParseService matchParseService) {
        this.matchService = matchService;
        this.matchParseService = matchParseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MatchResponse create(@Valid @RequestBody CreateMatchRequest request) {
        return matchService.create(request);
    }

    @PostMapping("/parse")
    public MatchParseResult parse(@Valid @RequestBody ParseMatchRequest request) {
        return matchParseService.parse(request.text());
    }

    @GetMapping
    public List<MatchResponse> list(@RequestParam(required = false) String opponent) {
        return matchService.list(opponent);
    }

    @GetMapping("/opponents")
    public List<String> opponents() {
        return matchService.listOpponents();
    }
}

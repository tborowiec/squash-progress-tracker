package org.borowiec.squashprogresstracker.match;

import jakarta.validation.Valid;
import java.util.List;
import org.borowiec.squashprogresstracker.match.dto.CreateOrUpdateMatchRequest;
import org.borowiec.squashprogresstracker.match.dto.MatchParseResult;
import org.borowiec.squashprogresstracker.match.dto.MatchResponse;
import org.borowiec.squashprogresstracker.match.dto.ParseMatchRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
    public MatchResponse create(@Valid @RequestBody CreateOrUpdateMatchRequest request) {
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

    @GetMapping("/{id}")
    public MatchResponse get(@PathVariable Long id) {
        return matchService.get(id);
    }

    @PutMapping("/{id}")
    public MatchResponse update(@PathVariable Long id, @Valid @RequestBody CreateOrUpdateMatchRequest request) {
        return matchService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        matchService.delete(id);
    }
}

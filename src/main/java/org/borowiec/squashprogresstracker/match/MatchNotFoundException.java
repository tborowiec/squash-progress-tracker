package org.borowiec.squashprogresstracker.match;

public class MatchNotFoundException extends RuntimeException {

    public MatchNotFoundException(Long id) {
        super("No match found for id: " + id);
    }
}

package com.yupi.yuaiagent.reading.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yupi.yuaiagent.reading.api.ReadingTypes.Source;

import java.util.List;

/** Stable tool payloads; serialization stays at the model/history boundary. */
final class ReadingToolResults {
    private ReadingToolResults() { }

    sealed interface SearchPayload permits SearchResult { }

    record PassageEvidence(String passageId, int start, int end, int lexicalRank, int vectorRank,
                           double score, List<String> sourceIds, boolean alreadyProvided,
                           int newCharacters) { }

    record SearchResult(String bookId, List<PassageEvidence> passages,
                        List<Source> sources, int newCharacters, int retrievedCount,
                        int deliveredCount, String stopReason) implements SearchPayload { }

    record CompactSource(String id, String text) { }

    record ReadResult<S>(S source, String bookId, int start, int end, Integer nextStart,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer remainingEstimatedTokens,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer remainingCharacters) { }

    record AlreadyProvided(boolean alreadyProvided, List<String> sourceIds, int start, int end, int nextStart) { }

    record AcceptedPoint(int inputIndex, String pointId) { }

    record InterestPoint(String id, String quote, String reason, String question, String hypothesis, String query) { }

    sealed interface CandidateOutcome permits CandidateResult, CandidateError { }

    record CandidateResult<T extends SearchPayload>(String pointId, String bookId, T result) implements CandidateOutcome { }

    record CandidateError(String pointId, String bookId, String error) implements CandidateOutcome { }

    record SelectionModel(List<AcceptedPoint> acceptedPoints, List<CandidateOutcome> evidence) { }

    record SelectionTrace(List<InterestPoint> points, List<CandidateOutcome> evidence) { }
}

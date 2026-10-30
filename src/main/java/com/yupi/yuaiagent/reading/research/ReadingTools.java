package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.book.BookTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.yupi.yuaiagent.reading.book.BookSearch;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.model.ContextBudget;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;
import static com.yupi.yuaiagent.reading.book.BookTexts.Book;
import static com.yupi.yuaiagent.reading.research.ReadingToolResults.*;

/**
 * Search and original-text tools load current books for each operation.
 */
@Service
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ReadingTools {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Complete history and the smaller tool response share the same operation identity.
     */
    record ToolResult<M, T>(M modelData, T traceData, List<Source> sources) {
    }

    private final BookTexts texts;
    private final BookSearch search;
    private final ReadingStore store;
    private final ReadingProperties p;

    public ReadingTools(BookTexts texts, BookSearch search, ReadingStore store, ReadingProperties p) {
        this.texts = texts;
        this.search = search;
        this.store = store;
        this.p = p;
    }

    public List<Book> books(ResearchRun run) {
        return run.settings.knowledgeBaseIds().stream().map(texts::load).toList();
    }

    public String fullText(ResearchRun run, Set<String> ids) {
        var books = books(run);
        if (books.stream().mapToLong(b -> b.text().length()).sum() > p.getMaxFullTextCharacters())
            throw new IllegalArgumentException("协作研究全文超过上限，请减少书籍；不会截断");
        StringBuilder body = new StringBuilder();
        for (var book : books) {
            Source source = run.exactSource(new Source(book.id() + ":" + book.generation(), "FULL_BOOK", book.name(), "完整原文 · " + book.text().length() + " 字符", "", book.text()), p.getMaxFullTextContextCharacters());
            ids.add(source.id());
            body.append("\n===== 书籍 [").append(source.id()).append("] ").append(book.name()).append(" =====\n").append(book.text()).append("\n===== 书籍结束 =====\n");
        }
        return body.toString();
    }

    public class State {
        private final ResearchRun run;
        private final ExecutorService calls;
        private final Map<String, List<int[]>> ranges = new HashMap<>();
        private final Map<String, Integer> operations = new HashMap<>();
        private final Set<String> investigationSources = new LinkedHashSet<>();
        private int noProgress, pendingCharacters, pendingTokens;
        private final ContextBudget budget;
        private final Set<String> present = new LinkedHashSet<>();
        private String contextStop;
        private List<AcceptedPoint> selectionAccepted;
        private List<CandidateOutcome> selectionEvidence;

        private record Evidence(String bookId, int start, int end, String sourceId) {
        }

        private final List<Evidence> evidence = new ArrayList<>();

        State(ResearchRun run, ExecutorService calls) {
            this.run = run;
            this.calls = calls;
            this.budget = new ContextBudget(p, run, true);
            run.managedEvidence = true;
        }

        // The collaborative Investigator shares provenance and tool budgets, but has its own task context.
        void investigation(String question) {
            contextStop = null;
            noProgress = 0;
            present.clear();
            investigationSources.clear();
            selectionAccepted = null;
            selectionEvidence = null;

        }

        ToolResult<? extends SearchPayload, ? extends SearchPayload> investigationSearchResult(String bookId, String query, String semanticQuery, String trigger, String pointId) throws Exception {
            run.beginToolTrace("searchPassages", "AUTO", Map.of("bookId", bookId, "query", query, "semanticQuery", semanticQuery, "trigger", trigger), "", pointId);

            try {
                begin("searchPassages", trigger + " · " + bookId + ":" + query + "\n" + semanticQuery);

                Book b = book(bookId);
                var found = run.await(calls, () -> search.search(b, query, semanticQuery, p.getInvestigatorHybridTopK(), true));
                if (found.isEmpty()) noProgress++;
                var feedback = searchEvidence(b, found, pointId);
                result("searchPassages", feedback);
                return feedback;
            } catch (Exception e) {
                run.event("tool_result", Map.of("tool", "searchPassages", "error", Objects.toString(e.getMessage(), "混合检索失败")));
                throw e;
            } finally {
                run.endToolTrace();
            }
        }

        void investigationInput(List<Message> messages) {
            pendingCharacters = messageCharacters(messages);
            pendingTokens = budget.tokens(messages);
            selectionAccepted = null;
            selectionEvidence = null;
        }

        String investigationStop() {
            return contextStop;
        }

        void stopInvestigation(String reason) {
            contextStop = reason;
        }

        Set<String> investigationSources() {
            return Set.copyOf(investigationSources);
        }

        Book book(String id) {
            if (!run.settings.knowledgeBaseIds().contains(id))
                throw new IllegalArgumentException("知识库不在当前请求范围内");
            return texts.load(id);
        }

        int messageCharacters(List<Message> messages) {
            return messages.stream().mapToInt(m -> {
                int size = Objects.toString(m.getText(), "").length();
                if (m instanceof ToolResponseMessage tool)
                    size += tool.getResponses().stream().mapToInt(r -> Objects.toString(r.responseData(), "").length()).sum();
                if (m instanceof AssistantMessage assistant)
                    size += assistant.getToolCalls().stream().mapToInt(t -> Objects.toString(t.arguments(), "").length()).sum();
                return size;
            }).sum();
        }

        void begin(String tool, String query) {
            run.reserveTool();
            String key = tool + ":" + query;
            int count = operations.merge(key, 1, Integer::sum);
            if (count > 2) {
                noProgress++;
                throw new IllegalArgumentException("重复操作没有新增线索，请改变查询或报告缺口");
            }
            run.event("tool_call", Map.of("tool", tool, "query", query));
        }

        String result(String tool, Object data, List<Source> sources) {
            return result(tool, new ToolResult<>(data, data, sources));
        }

        String result(String tool, ToolResult<?, ?> feedback) {
            String value = modelFeedback(feedback.modelData());
            if (pendingCharacters + value.length() > budget.characterLimit() - budget.reservedTokens() * 2 || pendingTokens + budget.estimate(value) > budget.tokenLimit() - budget.reservedTokens()) {
                contextStop = "研究输入接近上限，停止展开并预留汇总空间";
            }
            run.event("tool_result", Map.of("tool", tool, "data", feedback.traceData(), "sources", feedback.sources(), "modelFeedbackCharacters", value.length(), "traceCharacters", store.encode(feedback.traceData()).length()));
            return value;
        }

        private String modelFeedback(Object data) {
            String value = store.encode(data);
            boolean nearLimit = pendingCharacters + value.length() >= (budget.characterLimit() - budget.reservedTokens() * 2) * .9
                    || pendingTokens + budget.estimate(value) >= (budget.tokenLimit() - budget.reservedTokens()) * .9;
            if (nearLimit) {
                var node = JSON.valueToTree(data);
                if (node instanceof ObjectNode object) {
                    object.put("budgetWarning", "上下文接近上限，请缩小读取窗口或结束取证");
                    return store.encode(object);
                }
            }
            return value;
        }

        private Source registerEvidence(Book b, int from, int to) {
            Source source = run.exactSource(new Source(b.id() + ":" + b.generation() + ":" + from + ":" + to, "BOOK", b.name(), "原文字符 " + from + "–" + to, "", b.text().substring(from, to)), p.getMaxFullTextContextCharacters());
            investigationSources.add(source.id());
            if (evidence.stream().noneMatch(e -> e.sourceId().equals(source.id())))
                evidence.add(new Evidence(b.id() + ":" + b.generation(), from, to, source.id()));
            ranges.computeIfAbsent(b.id() + ":" + b.generation(), k -> new ArrayList<>()).add(new int[]{from, to});
            return source;
        }

        // The automatic searches share one selectInterests response, including its JSON envelope.
        void selectionFeedback(List<AcceptedPoint> accepted, List<CandidateOutcome> outcomes) {
            selectionAccepted = accepted;
            selectionEvidence = outcomes;
        }

        private boolean searchFeedbackFits(SearchResult result, String pointId) {
            Object payload = result;
            if (selectionAccepted != null && !pointId.isEmpty()) {
                var outcomes = new ArrayList<>(selectionEvidence);
                outcomes.add(new CandidateResult<>(pointId, result.bookId(), result));
                payload = new SelectionModel(selectionAccepted, outcomes);
            }
            String encoded = store.encode(payload);
            // Reserve room for budgetWarning, stopReason and tool-response framing.
            return pendingCharacters + encoded.length() + 500 <= budget.characterLimit() - budget.reservedTokens() * 2
                    && pendingTokens + budget.estimate(encoded) + 500 <= budget.tokenLimit() - budget.reservedTokens();
        }

        private SearchResult searchPayload(Book b, List<PassageEvidence> passages, List<Source> delivered, int retrieved) {
            return new SearchResult(b.id(), List.copyOf(passages), List.copyOf(delivered),
                    passages.stream().mapToInt(PassageEvidence::newCharacters).sum(), retrieved, passages.size(), Objects.toString(contextStop, ""));
        }

        private ToolResult<SearchResult, SearchResult> searchEvidence(Book b, List<BookSearch.Hit> found, String pointId) {
            List<PassageEvidence> passages = new ArrayList<>();
            List<Source> delivered = new ArrayList<>();
            var hits = found.stream().limit(p.getInvestigatorHybridTopK()).toList();
            for (var hit : hits) {
                run.check();
                int from = hit.start(), to = hit.end();
                if (from < 0 || to <= from || to > b.text().length())
                    throw new IllegalArgumentException("检索原文位置无效");
                var existing = evidence.stream().filter(e -> e.bookId().equals(b.id() + ":" + b.generation()) && e.end() > from && e.start() < to).sorted(Comparator.comparingInt(Evidence::start)).toList();
                List<int[]> missing = new ArrayList<>();
                int at = from;
                for (var e : existing) {
                    if (e.start() > at) missing.add(new int[]{at, Math.min(to, e.start())});
                    at = Math.max(at, e.end());
                    if (at >= to) break;
                }
                if (at < to) missing.add(new int[]{at, to});
                var reused = existing.stream().map(Evidence::sourceId).distinct().toList();
                List<Source> resend = run.sources.stream().filter(s -> reused.contains(s.id()) && !present.contains(s.id())).toList();
                // Preview exactly the sources and IDs that will be committed; no evidence is registered before fitting.
                List<Source> additions = new ArrayList<>();
                for (int[] r : missing) {
                    additions.add(new Source("S" + (run.sources.size() + additions.size() + 1), "BOOK", b.name(),
                            "原文字符 " + r[0] + "–" + r[1], "", b.text().substring(r[0], r[1])));
                }
                List<String> ids = new ArrayList<>(reused);
                additions.forEach(s -> ids.add(s.id()));
                int added = missing.stream().mapToInt(r -> r[1] - r[0]).sum();
                var passage = new PassageEvidence(hit.passageId(), from, to, hit.lexicalRank(), hit.vectorRank(), hit.score(), ids, missing.isEmpty(), added);
                var projectedSources = new ArrayList<>(delivered);
                projectedSources.addAll(resend);
                projectedSources.addAll(additions);
                var projectedPassages = new ArrayList<>(passages);
                projectedPassages.add(passage);
                if (!searchFeedbackFits(searchPayload(b, projectedPassages, projectedSources, hits.size()), pointId)) {
                    contextStop = "召回原文超过剩余上下文空间，已保留完整证据分片并停止取证";
                    break;
                }
                for (Source source : resend) {
                    delivered.add(source);
                    present.add(source.id());
                }
                investigationSources.addAll(reused);
                for (int[] r : missing) {
                    Source source = registerEvidence(b, r[0], r[1]);
                    present.add(source.id());
                    delivered.add(source);
                }
                passages.add(passage);
            }
            var data = searchPayload(b, passages, delivered, hits.size());
            return new ToolResult<>(data, data, delivered);
        }

        @Tool(description = "BM25与向量混合检索全书，按配置上限返回完整原文分片及来源编号，可直接用于调查报告；重叠区间复用已有来源。需要更多上下文时用分片start/end调用readPassage展开。")
        public String searchPassages(String bookId, String query) throws Exception {
            begin("searchPassages", bookId + ":" + query);
            Book b = book(bookId);
            var found = run.await(calls, () -> search.search(b, query, p.getInvestigatorHybridTopK(), true));
            if (found.isEmpty()) noProgress++;
            return result("searchPassages", searchEvidence(b, found, ""));
        }

        @Tool(description = "按检索结果的字符位置读取原文并展开上下文。start/end为0起始UTF-16位置，before/after为扩展字符数，总长度不超过12000。")
        public String readPassage(String bookId, int start, int end, int before, int after) {
            begin("readPassage", bookId + ":" + start + ":" + end + ":" + before + ":" + after);
            Book b = book(bookId);
            if (start < 0 || end <= start || end > b.text().length() || before < 0 || after < 0)
                throw new IllegalArgumentException("原文位置无效");
            int from = Math.max(0, start - before);
            int to = (int) Math.min(b.text().length(), (long) end + after);
            return read(b, from, to, to < b.text().length() ? to : null, "readPassage");
        }

        private String read(Book b, int from, int to, Integer next, String tool) {
            if (to - from > p.getReadCharacters())
                throw new IllegalArgumentException("展开超过单次原文上限，请缩小范围");
            String original = b.text().substring(from, to);
            var missing = new ArrayList<int[]>();
            int at = from;
            for (var e : evidence.stream().filter(e -> e.bookId().equals(b.id() + ":" + b.generation()) && present.contains(e.sourceId())).sorted(Comparator.comparingInt(Evidence::start)).toList()) {
                if (e.end() <= at || e.start() >= to) continue;
                if (e.start() > at) missing.add(new int[]{at, Math.min(to, e.start())});
                at = Math.max(at, e.end());
                if (at >= to) break;
            }
            if (at < to) missing.add(new int[]{at, to});
            if (missing.isEmpty()) {
                noProgress++;
                return result(tool, new AlreadyProvided(true, evidence.stream().filter(e -> e.bookId().equals(b.id() + ":" + b.generation()) && present.contains(e.sourceId()) && e.end() > from && e.start() < to).map(Evidence::sourceId).toList(), from, to, next == null ? -1 : next), List.of());
            }
            String provided = missing.stream().map(r -> "原文字符 " + r[0] + "–" + r[1] + "\n" + b.text().substring(r[0], r[1])).reduce("", (a, c) -> a + c + "\n");
            if (pendingCharacters + store.encode(provided).length() + 1000 > budget.characterLimit() - budget.reservedTokens() * 2 || pendingTokens + budget.estimate(provided) + 1000 > budget.tokenLimit() - budget.reservedTokens()) {
                contextStop = "原文展开将占用汇总预留空间";
                throw new IllegalArgumentException("原文展开超过剩余研究空间，请缩小窗口；将汇总现有证据");
            }
            boolean newEvidence = !covers(ranges.getOrDefault(b.id() + ":" + b.generation(), List.of()), from, to);
            Source source = registerEvidence(b, from, to);
            noProgress = newEvidence ? 0 : noProgress + 1;
            present.add(source.id());
            int remainingTokens = Math.max(0, budget.tokenLimit() - budget.reservedTokens() - pendingTokens - budget.estimate(provided) - 1000);
            int remainingCharacters = Math.max(0, budget.characterLimit() - budget.reservedTokens() * 2 - pendingCharacters - store.encode(original).length() - 1000);
            var output = new ReadResult<>(new Source(source.id(), source.type(), source.title(), source.location(), source.url(), provided),
                    b.id(), from, to, next, remainingTokens, remainingCharacters);
            var modelOutput = new ReadResult<>(new CompactSource(source.id(), provided), b.id(), from, to, next,
                    null, null);
            return result(tool, new ToolResult<>(modelOutput, output, List.of(source)));
        }

        static boolean covers(List<int[]> ranges, int start, int end) {
            int at = start;
            for (int[] range : ranges.stream().sorted(Comparator.comparingInt(r -> r[0])).toList()) {
                if (range[0] > at) break;
                if (range[1] > at) at = range[1];
                if (at >= end) return true;
            }
            return at >= end;
        }

        List<Map<String, Object>> coverage() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Book b : books(run))
                rows.add(Map.of("bookId", b.id(), "title", b.name(), "readComplete",
                        covers(ranges.getOrDefault(b.id() + ":" + b.generation(), List.of()), 0, b.text().length())));
            return rows;
        }
    }
}

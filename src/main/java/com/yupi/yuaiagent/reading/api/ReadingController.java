package com.yupi.yuaiagent.reading.api;

import com.yupi.yuaiagent.reading.knowledge.KnowledgeService;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.research.ModeRegistry;
import com.yupi.yuaiagent.reading.research.ResearchAgent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

@RestController
@RequestMapping("/reading")
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ReadingController {
    private final ReadingStore store;
    private final KnowledgeService knowledge;
    private final ModeRegistry modes;
    private final ResearchAgent agent;

    public ReadingController(ReadingStore store, KnowledgeService knowledge, ModeRegistry modes, ResearchAgent agent) {
        this.store = store;
        this.knowledge = knowledge;
        this.modes = modes;
        this.agent = agent;
    }

    @GetMapping("/knowledge-bases")
    public Object knowledgeBases() {
        return store.knowledgeBases().stream().map(this::publicKb).toList();
    }

    @PostMapping("/knowledge-bases")
    public Object upload(@RequestParam MultipartFile file) throws Exception {
        return publicKb(knowledge.upload(file));
    }

    @GetMapping("/knowledge-bases/{id}")
    public Object kb(@PathVariable String id) {
        return publicKb(store.kb(id));
    }

    @PatchMapping("/knowledge-bases/{id}")
    public Object rename(@PathVariable String id, @RequestBody NamedText text) {
        store.renameKb(id, text.name());
        return publicKb(store.kb(id));
    }

    @DeleteMapping("/knowledge-bases/{id}")
    public void deleteKb(@PathVariable String id) throws Exception {
        knowledge.delete(id);
    }

    @PostMapping({"/knowledge-bases/{id}/retry", "/knowledge-bases/{id}/rebuild"})
    public Object rebuild(@PathVariable String id) {
        knowledge.submit(id, true);
        return publicKb(store.kb(id));
    }

    @GetMapping("/sessions")
    public Object sessions() {
        return store.sessions();
    }

    @PostMapping("/sessions")
    public Object createSession() {
        return store.createSession();
    }

    @GetMapping("/sessions/{id}")
    public Object session(@PathVariable String id) {
        return store.session(id);
    }

    @PutMapping("/sessions/{id}/settings")
    public void settings(@PathVariable String id, @RequestBody Settings settings) {
        agent.settings(id, settings);
    }

    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable String id) throws Exception {
        agent.withIdleSession(id, () -> {
            agent.delete(id);
            return null;
        });
    }

    @GetMapping("/sessions/{id}/messages")
    public Object history(@PathVariable String id) {
        return store.history(id);
    }

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(@PathVariable String id, @RequestBody MessageRequest request) {
        return agent.start(id, request);
    }

    @PostMapping("/runs/{id}/stop")
    public void stop(@PathVariable String id) {
        agent.stop(id);
    }

    @PostMapping("/runs/{id}/interest-selection")
    public void confirmInterests(@PathVariable String id, @RequestBody InterestSelectionRequest request) {
        agent.confirmInterests(id, request);
    }

    @GetMapping("/modes")
    public Object modes() {
        return modes.list();
    }

    @PostMapping("/modes/refresh")
    public Object refresh() {
        return modes.refresh();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", Objects.toString(e.getMessage(), "请求无效")));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", Objects.toString(e.getReason(), "请求失败")));
    }

    private Map<String, Object> publicKb(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>(row);
        safe.remove("path");
        return safe;
    }
}

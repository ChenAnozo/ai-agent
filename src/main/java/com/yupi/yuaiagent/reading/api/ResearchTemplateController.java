package com.yupi.yuaiagent.reading.api;

import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.research.ResearchTemplates;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/reading")
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ResearchTemplateController {
    private final ResearchTemplates templates;
    private final ReadingStore store;
    public ResearchTemplateController(ResearchTemplates templates, ReadingStore store) { this.templates = templates; this.store = store; }

    @GetMapping("/research-templates")
    public Object list() { return templates.list(); }

    @GetMapping("/research-templates/{id}")
    public Object detail(@PathVariable String id, @RequestParam(required = false) Integer version) {
        return templates.detail(templates.get(id, version));
    }

    @PostMapping("/research-templates")
    public Object copy(@RequestBody(required = false) ResearchTemplates.Copy request) {
        return templates.detail(templates.copy(request));
    }

    @PutMapping("/research-templates/{id}")
    public Object update(@PathVariable String id, @RequestBody ResearchTemplates.Edit request) {
        return templates.detail(templates.update(id, request));
    }

    @PostMapping("/research-templates/preview")
    public Object preview(@RequestBody ReadingTypes.Settings settings) { return templates.detail(templates.effective(settings)); }

    @GetMapping("/sessions/{id}/research-template")
    public Object effective(@PathVariable String id) {
        var settings = store.settings(id);
        return templates.detail(templates.effective(settings));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object invalid(IllegalArgumentException e) { return Map.of("message", e.getMessage()); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "模板操作失败" : e.getReason())); }
}

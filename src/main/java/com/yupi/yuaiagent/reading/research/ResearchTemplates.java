package com.yupi.yuaiagent.reading.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yupi.yuaiagent.reading.api.ReadingTypes.Settings;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ResearchTemplates {
    public static final String DEFAULT = "mainline-development", ORIGINAL = "original-collaboration";
    public static final List<String> KEYS = List.of("primaryRole", "draftGuidance", "collaborationGuidance", "investigatorInterests", "investigationGuidance", "reportGuidance", "integrationGuidance", "continuationGuidance");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int MAX_BYTES = 128 * 1024;
    private final ReadingProperties p;
    private final Path root;

    public record Copy(String sourceId, Integer sourceVersion, String name) { }
    public record Edit(int expectedVersion, String name, String description, Map<String, String> sections) { }

    public ResearchTemplates(ReadingProperties p) {
        this.p = p;
        root = Path.of(p.getTemplatesDirectory()).toAbsolutePath().normalize();
    }

    private String id(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("研究模板ID无效");
        return id;
    }

    private void ordinaryPath(Path path) {
        for (Path at = path; at != null && at.startsWith(root); at = at.getParent())
            if (Files.isSymbolicLink(at)) throw new IllegalArgumentException("模板路径不能使用符号链接");
    }

    private ResearchTemplate read(Path path) {
        ordinaryPath(path);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES)
                throw new IllegalArgumentException("模板文件不存在或超过128KB：" + path.getFileName());
            var t = JSON.readValue(Files.readAllBytes(path), ResearchTemplate.class);
            validate(t);
            return t;
        } catch (IOException e) { throw new IllegalArgumentException("无法读取研究模板：" + path.getFileName(), e); }
    }

    private void validate(ResearchTemplate t) {
        id(t.id());
        if (t.name() == null || t.name().isBlank() || t.name().length() > 200 || t.description() == null || t.description().length() > 2000)
            throw new IllegalArgumentException("模板名称或说明无效");
        if (t.engine() == null || !Set.of("LEGACY_V2", "DEVELOPMENT_V4").contains(t.engine()) || t.version() < 1)
            throw new IllegalArgumentException("模板执行引擎或版本无效");
        if (t.baseId() == null || !t.baseId().isEmpty() && !t.baseId().matches("[a-zA-Z0-9_-]{1,80}") || t.baseVersion() < 0)
            throw new IllegalArgumentException("模板继承信息无效");
        if (!t.sections().keySet().equals(Set.copyOf(KEYS))) throw new IllegalArgumentException("模板须包含完整且仅有八项指导文字");
        for (var key : KEYS) {
            String value = t.section(key);
            if (value == null || value.isBlank() || value.length() > 16000)
                throw new IllegalArgumentException(key + "必须为1–16000字符的指导文字");
        }
        bytes(t);
    }

    private byte[] bytes(ResearchTemplate t) {
        try {
            byte[] value = JSON.writeValueAsBytes(t);
            if (value.length > MAX_BYTES) throw new IllegalArgumentException("完整模板超过128KB");
            return value;
        } catch (IOException e) { throw new IllegalArgumentException("模板序列化失败", e); }
    }

    public String digest(ResearchTemplate t) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(t))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public ResearchTemplate get(String requested) { return get(requested, null); }

    public synchronized ResearchTemplate get(String requested, Integer revision) {
        String templateId = id(requested);
        Path official = root.resolve("official").resolve(templateId + ".json");
        if (Files.exists(official, LinkOption.NOFOLLOW_LINKS)) {
            var t = read(official);
            if (!t.official() || !t.id().equals(templateId)) throw new IllegalArgumentException("官方模板标识不一致");
            if (revision == null || revision == t.version()) return t;
            return revision(templateId, revision);
        }
        Path dir = root.resolve("custom").resolve(templateId);
        ordinaryPath(dir);
        if (revision == null) {
            try {
                Path current = dir.resolve("current.json");
                ordinaryPath(current);
                if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "研究模板不存在，请重新选择");
                if (Files.size(current) > 1024) throw new IllegalArgumentException("模板版本指针无效");
                revision = JSON.readTree(Files.readAllBytes(current)).path("version").intValue();
            } catch (IOException e) { throw new IllegalArgumentException("无法读取模板当前版本", e); }
        }
        var t = revision(templateId, revision);
        if (t.official()) throw new IllegalArgumentException("自定义目录中的模板不能声明为官方模板");
        return t;
    }

    private ResearchTemplate revision(String templateId, int revision) {
        if (revision < 1) throw new IllegalArgumentException("修订版本无效");
        var t = read(root.resolve("custom").resolve(templateId).resolve("revisions").resolve(revision + ".json"));
        if (!t.id().equals(templateId) || t.version() != revision) throw new IllegalArgumentException("模板修订标识不一致");
        return t;
    }

    public synchronized Map<String, Object> list() {
        List<ResearchTemplate> templates = new ArrayList<>();
        List<Map<String, String>> errors = new ArrayList<>();
        Path official = root.resolve("official"), custom = root.resolve("custom");
        try {
            ordinaryPath(official); ordinaryPath(custom);
            if (!Files.isDirectory(official)) throw new IllegalArgumentException("官方研究模板目录不存在");
            try (var files = Files.list(official)) {
                for (var file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    try { templates.add(get(file.getFileName().toString().replaceFirst("\\.json$", ""))); }
                    catch (RuntimeException e) { errors.add(Map.of("id", file.getFileName().toString(), "error", Objects.toString(e.getMessage(), "模板无效"))); }
                }
            }
            if (Files.isDirectory(custom)) try (var dirs = Files.list(custom)) {
                for (var dir : dirs.filter(d -> Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) && Files.exists(d.resolve("current.json"))).sorted().toList()) {
                    try { templates.add(get(dir.getFileName().toString())); }
                    catch (RuntimeException e) { errors.add(Map.of("id", dir.getFileName().toString(), "error", Objects.toString(e.getMessage(), "模板无效"))); }
                }
            }
        } catch (IOException e) { throw new IllegalArgumentException("无法列出研究模板", e); }
        var summaries = templates.stream().map(t -> Map.of("id", t.id(), "name", t.name(), "description", t.description(), "engine", t.engine(), "version", t.version(), "official", t.official(), "baseId", t.baseId(), "baseVersion", t.baseVersion())).toList();
        return Map.of("templates", summaries, "errors", errors, "defaultId", DEFAULT, "originalId", ORIGINAL);
    }

    public synchronized ResearchTemplate copy(Copy input) {
        var source = get(input == null || input.sourceId() == null || input.sourceId().isBlank() ? ORIGINAL : input.sourceId(), input == null ? null : input.sourceVersion());
        preserve(source);
        String name = input == null || input.name() == null || input.name().isBlank() ? source.name() + " · 自定义" : input.name().strip();
        var sections = new LinkedHashMap<>(source.sections());
        var t = new ResearchTemplate("custom-" + UUID.randomUUID(), name, source.description(), source.engine(), 1, source.id(), source.version(), false, sections);
        validate(t);
        publish(t);
        return t;
    }

    public synchronized ResearchTemplate update(String templateId, Edit edit) {
        var current = get(templateId);
        if (current.official()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "官方模板只读，请先复制");
        if (edit == null || edit.expectedVersion() != current.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "模板已被其他页面更新；编辑内容仍保留，请重新载入后合并");
        int next = current.version() + 1;
        Path revisions = root.resolve("custom").resolve(current.id()).resolve("revisions");
        // An interrupted pointer replacement may leave an unpublished immutable revision.
        while (Files.exists(revisions.resolve(next + ".json"))) next++;
        var t = new ResearchTemplate(current.id(), edit.name(), edit.description(), current.engine(), next, current.baseId(), current.baseVersion(), false, edit.sections());
        validate(t);
        publish(t);
        return t;
    }

    public synchronized void preserve(ResearchTemplate t) {
        Path file = root.resolve("custom").resolve(t.id()).resolve("revisions").resolve(t.version() + ".json");
        ordinaryPath(file);
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                if (!digest(read(file)).equals(digest(t))) throw new IllegalArgumentException("已有模板修订内容发生冲突，请提升版本号");
            } else Files.write(file, bytes(t), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) { throw new IllegalArgumentException("无法保留模板修订", e); }
    }

    private void publish(ResearchTemplate t) {
        preserve(t);
        Path pointer = root.resolve("custom").resolve(t.id()).resolve("current.json");
        Path temporary = pointer.resolveSibling("current-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, "{\"version\":" + t.version() + "}", StandardOpenOption.CREATE_NEW);
            Files.move(temporary, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new IllegalArgumentException("保存模板失败，原版本保持不变", e); }
        finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    public synchronized Settings normalize(Settings settings) {
        if (!settings.modeId().equals("collaborative-research")) return settings;
        String selected = settings.researchTemplateId();
        if (selected.isBlank()) selected = DEFAULT;
        get(selected);
        return new Settings(settings.knowledgeBaseIds(), settings.modeId(), selected, settings.interestSelectionMode());
    }

    public ResearchTemplate effective(Settings settings) {
        var t = get(settings.researchTemplateId().isBlank() ? DEFAULT : settings.researchTemplateId());
        validate(t);
        return t;
    }

    public Map<String, Object> detail(ResearchTemplate t) {
        ResearchTemplate initial = t.official() ? t : revision(t.id(), 1);
        return Map.of("template", t, "initialSections", initial.sections(), "digest", digest(t), "effective", ResearchPrompts.preview(t, p));
    }
}

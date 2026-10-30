package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.*;
import java.util.*;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

@Service
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ModeRegistry {
    private static final Map<String, String> RETIRED = Map.of("deep-research", "深度研究", "compare-views", "观点比较", "structure-outline", "结构梳理", "general-research", "通用研究");
    private final Path directory;
    private volatile Map<String, Mode> modes = Map.of();
    private volatile List<Map<String, String>> errors = List.of();

    public ModeRegistry(ReadingProperties p) {
        directory = Path.of(p.getSkillsDirectory());
    }

    @PostConstruct
    public synchronized Map<String, Object> refresh() {
        Map<String, Mode> next = new LinkedHashMap<>();
        List<Map<String, String>> failures = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".md")).sorted().toList()) {
                    try {
                        if (Files.size(file) > 64000) throw new IllegalArgumentException("模式文件最多 64 KB");
                        Mode mode = parse(Files.readString(file));
                        rejectRetired(mode.id());
                        if (!mode.id().equals("collaborative-research"))
                            throw new IllegalArgumentException("仅支持协作研究模式；请修改协作指引或评议规则");
                        if (next.putIfAbsent(mode.id(), mode) != null)
                            throw new IllegalArgumentException("模式 ID 重复：" + mode.id());
                    } catch (Exception e) {
                        failures.add(Map.of("file", file.getFileName().toString(), "error", Objects.toString(e.getMessage(), "无效模式")));
                    }
                }
            }
        } catch (Exception e) {
            failures.add(Map.of("file", directory.toString(), "error", Objects.toString(e.getMessage(), "目录读取失败")));
        }
        modes = Collections.unmodifiableMap(next);
        errors = List.copyOf(failures);
        return list();
    }

    public static Mode parse(String text) {
        text = text.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw new IllegalArgumentException("模式缺少 YAML 元数据");
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("模式元数据未闭合");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object yaml;
        try {
            yaml = new Yaml(new SafeConstructor(options)).load(text.substring(4, end));
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new IllegalArgumentException("模式 YAML 元数据无效：" + e.getMessage(), e);
        }
        if (!(yaml instanceof Map<?, ?> meta)) throw new IllegalArgumentException("模式元数据必须是对象");
        String id = ReadingStore.required(Objects.toString(meta.get("id"), ""), 80);
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("模式 ID 只允许字母、数字、下划线与连字符");
        return new Mode(id, ReadingStore.required(Objects.toString(meta.get("name"), ""), 200),
                ReadingStore.required(Objects.toString(meta.get("description"), ""), 1000), ReadingStore.required(text.substring(end + 5), 16000));
    }

    public Map<String, Object> list() {
        return Map.of("modes", List.copyOf(modes.values()), "errors", errors);
    }

    private static void rejectRetired(String id) {
        if (id != null && RETIRED.containsKey(id))
            throw new IllegalArgumentException(RETIRED.get(id) + "已移除，请重新选择研究模式");
    }

    public Mode get(String id) {
        rejectRetired(id);
        if (id == null || id.isBlank()) return new Mode("", "普通问答", "单次检索", "");
        Mode mode = modes.get(id);
        if (mode == null) throw new IllegalArgumentException("研究模式不存在，请刷新后重新选择");
        return mode;
    }
}

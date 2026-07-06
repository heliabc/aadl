package com.nuaa.aadl.module.aadl;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class AadlPromptRuleService {

    private static final String RULE_RESOURCE = "prompts/aadl/aadl-rules.yml";

    private String globalAlwaysRules = "";
    private String generationOrder = "";
    private String forbiddenRules = "";
    private List<RuleNode> ruleNodes = List.of();

    @PostConstruct
    public void loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULE_RESOURCE).getInputStream()) {
            Object loaded = new Yaml().load(inputStream);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalStateException("AADL rule resource must be a YAML object: " + RULE_RESOURCE);
            }

            globalAlwaysRules = readNestedString(root, "global", "alwaysRules");
            generationOrder = readNestedString(root, "order", "content");
            forbiddenRules = readNestedString(root, "forbidden", "content");
            ruleNodes = Collections.unmodifiableList(readRuleNodes(root.get("rules")));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read AADL prompt rules: " + RULE_RESOURCE, e);
        }
    }

    public String buildRulesFor(String architectureRequirement) {
        String text = architectureRequirement == null ? "" : architectureRequirement;
        StringBuilder builder = new StringBuilder();

        appendSection(builder, "基础约束", globalAlwaysRules);
        for (RuleNode ruleNode : ruleNodes) {
            if (ruleNode.matches(text)) {
                appendSection(builder, ruleNode.title(), ruleNode.content());
            }
        }
        appendSection(builder, "生成顺序", generationOrder);
        appendSection(builder, "禁止规则", forbiddenRules);

        return builder.toString().trim();
    }

    private List<RuleNode> readRuleNodes(Object value) {
        if (!(value instanceof List<?> rules)) {
            return List.of();
        }

        List<RuleNode> nodes = new ArrayList<>();
        for (Object item : rules) {
            if (!(item instanceof Map<?, ?> rule)) {
                continue;
            }
            nodes.add(new RuleNode(
                    readString(rule, "id"),
                    readString(rule, "title"),
                    readBoolean(rule, "always"),
                    readTriggerKeywords(rule, "anyKeywords"),
                    readStringList(rule.get("skipIfKeywords")),
                    readString(rule, "content")
            ));
        }
        return nodes;
    }

    private List<String> readTriggerKeywords(Map<?, ?> rule, String key) {
        Object trigger = rule.get("trigger");
        if (!(trigger instanceof Map<?, ?> triggerMap)) {
            return List.of();
        }
        return readStringList(triggerMap.get(key));
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                strings.add(String.valueOf(item));
            }
        }
        return strings;
    }

    private String readNestedString(Map<?, ?> root, String objectKey, String nestedKey) {
        Object nested = root.get(objectKey);
        if (!(nested instanceof Map<?, ?> nestedMap)) {
            return "";
        }
        return readString(nestedMap, nestedKey);
    }

    private String readString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean readBoolean(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean bool && bool;
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("## ").append(title).append("\n").append(content.trim());
    }

    private record RuleNode(
            String id,
            String title,
            boolean always,
            List<String> anyKeywords,
            List<String> skipIfKeywords,
            String content
    ) {
        private boolean matches(String text) {
            if (containsAny(text, skipIfKeywords)) {
                return false;
            }
            if (always) {
                return true;
            }
            return containsAny(text, anyKeywords);
        }

        private static boolean containsAny(String text, List<String> keywords) {
            if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
                return false;
            }
            String lowerText = text.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank()
                        && lowerText.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }
}

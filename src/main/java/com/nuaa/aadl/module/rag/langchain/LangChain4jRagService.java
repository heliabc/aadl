package com.nuaa.aadl.module.rag.langchain;

import com.nuaa.aadl.chat.LlmClientService;
import com.nuaa.aadl.module.rag.service.KnowledgeBaseService;
import com.nuaa.aadl.module.rag.service.QdrantService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class LangChain4jRagService {

    private static final double DEFAULT_SCORE_THRESHOLD = 0.45;
    private static final int MAX_RESULTS_PER_FILE = 3;

    private final OllamaClient ollamaClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmClientService llmClientService;

    public LangChain4jRagService(
            OllamaClient ollamaClient,
            KnowledgeBaseService knowledgeBaseService,
            LlmClientService llmClientService) {
        this.ollamaClient = ollamaClient;
        this.knowledgeBaseService = knowledgeBaseService;
        this.llmClientService = llmClientService;
    }

    public record SearchResultItem(
        String content,
        double score,
        String fileName,
        int pageNumber,
        String sectionTitle,
        Map<String, Object> metadata
    ) {}

    /**
     * Search with default score threshold.
     */
    public List<SearchResultItem> search(String query, int limit) {
        return search(query, limit, DEFAULT_SCORE_THRESHOLD);
    }

    /**
     * Search with explicit score threshold, deduplication, and result ordering.
     */
    public List<SearchResultItem> search(String query, int limit, double scoreThreshold) {
        System.out.println("=== [LangChain4jRagService] Searching: " + query + " ===");
        System.out.println("    Score threshold: " + scoreThreshold + ", limit: " + limit);

        try {
            float[] vector = ollamaClient.embed(query);
            if (vector == null || vector.length == 0) {
                System.out.println("!!! Empty embedding vector");
                return new ArrayList<>();
            }

            System.out.println("    Query vector dim: " + vector.length);

            // Fetch more results than needed for post-filtering
            List<QdrantService.SearchResult> results =
                knowledgeBaseService.searchByVector(vector, limit * 3);

            System.out.println("    Raw results from Qdrant: " + results.size());

            if (results.isEmpty()) {
                return new ArrayList<>();
            }

            // Step 1: Filter by score threshold
            List<SearchResultItem> filtered = new ArrayList<>();
            for (QdrantService.SearchResult r : results) {
                if (r.score() >= scoreThreshold) {
                    String fileName = getMetaStr(r.metadata(), "fileName", "unknown");
                    int pageNumber = getMetaInt(r.metadata(), "pageNumber", 0);
                    String sectionTitle = getMetaStr(r.metadata(), "sectionTitle", "");

                    filtered.add(new SearchResultItem(
                        r.content(), r.score(), fileName, pageNumber, sectionTitle, r.metadata()
                    ));
                }
            }

            System.out.println("    After score filter: " + filtered.size());

            // Step 2: Deduplicate by content similarity (keep highest score)
            List<SearchResultItem> deduped = deduplicate(filtered);

            System.out.println("    After dedup: " + deduped.size());

            // Step 3: Limit per-file to avoid bias
            Map<String, List<SearchResultItem>> byFile = new LinkedHashMap<>();
            for (SearchResultItem item : deduped) {
                byFile.computeIfAbsent(item.fileName(), k -> new ArrayList<>()).add(item);
            }

            List<SearchResultItem> finalResults = new ArrayList<>();
            for (List<SearchResultItem> fileItems : byFile.values()) {
                fileItems.sort((a, b) -> Double.compare(b.score(), a.score()));
                finalResults.addAll(
                    fileItems.subList(0, Math.min(MAX_RESULTS_PER_FILE, fileItems.size()))
                );
            }

            // Step 4: Sort by score descending, limit
            finalResults.sort((a, b) -> Double.compare(b.score(), a.score()));
            if (finalResults.size() > limit) {
                finalResults = finalResults.subList(0, limit);
            }

            System.out.println("=== [RAG] Final results: " + finalResults.size() + " ===");
            for (int i = 0; i < finalResults.size(); i++) {
                SearchResultItem r = finalResults.get(i);
                String preview = r.content().length() > 100
                    ? r.content().substring(0, 100) + "..."
                    : r.content();
                System.out.println("--- RAG chunk[" + i + "] ---");
                System.out.println("    Score: " + String.format("%.4f", r.score()));
                System.out.println("    File: " + r.fileName() + ", Page: " + r.pageNumber());
                if (r.sectionTitle() != null && !r.sectionTitle().isEmpty()) {
                    System.out.println("    Section: " + r.sectionTitle());
                }
                System.out.println("    Content: " + preview);
                System.out.println("------------------");
            }

            return finalResults;
        } catch (Exception e) {
            System.out.println("!!! LangChain4jRagService search error: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Format search results as a compact context string for LLM injection.
     */
    
    // ==================== Query Rewriting ====================

    /**
     * Use LLM to rewrite/expand the user query into multiple search-friendly variants.
     * This bridges the vocabulary gap between user language and document terminology.
     */
    public List<String> rewriteQuery(String originalQuery) {
        System.out.println("=== [RAG] Rewriting query: " + originalQuery + " ===");
        try {
            String prompt = "你是一个军用标准文档检索专家。请将以下用户问题改写为3个不同角度的检索查询，"
                + "每个查询聚焦不同的关键词组合和表达方式，以最大化在GJB/Z军用标准文档中的召回率。"
                + "仅输出查询列表，每行一个，不要编号，不要解释。\n\n用户问题: " + originalQuery;

            List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
            );
            String response = llmClientService.chat(messages);
            
            List<String> variants = new ArrayList<>();
            for (String line : response.split("\n")) {
                String trimmed = line.trim();
                // Remove common numbering prefixes
                trimmed = trimmed.replaceAll("^[0-9]+[.、．)\s]+", "").trim();
                if (trimmed.length() > 3 && !trimmed.equals(originalQuery)) {
                    variants.add(trimmed);
                }
            }
            
            // Always include original query
            if (!variants.contains(originalQuery)) {
                variants.add(0, originalQuery);
            }
            
            System.out.println("=== [RAG] Generated " + variants.size() + " query variants ===");
            for (int i = 0; i < variants.size(); i++) {
                System.out.println("    Variant[" + i + "]: " + variants.get(i));
            }
            return variants;
        } catch (Exception e) {
            System.out.println("!!! [RAG] Query rewriting failed: " + e.getMessage());
            return List.of(originalQuery);
        }
    }

    // ==================== Hybrid Search (Vector + Keyword + RRF) ====================

    /**
     * Hybrid search combining vector similarity and keyword search with RRF fusion.
     */
    public List<SearchResultItem> searchHybrid(String query, int limit) {
        return searchHybrid(query, limit, DEFAULT_SCORE_THRESHOLD);
    }

    public List<SearchResultItem> searchHybrid(String query, int limit, double scoreThreshold) {
        System.out.println("=== [RAG] Hybrid search: " + query + " ===");

        try {
            // Step 1: Rewrite query into multiple variants
            List<String> queryVariants = rewriteQuery(query);

            // Step 2: Multi-query vector search
            Map<String, QdrantService.SearchResult> vectorResults = new LinkedHashMap<>();
            for (String variant : queryVariants) {
                float[] vector = ollamaClient.embed(variant);
                if (vector == null || vector.length == 0) continue;
                
                List<QdrantService.SearchResult> results = 
                    knowledgeBaseService.searchByVector(vector, limit * 2);
                for (QdrantService.SearchResult r : results) {
                    String key = extractFingerprint(r.content());
                    if (!vectorResults.containsKey(key) || r.score() > vectorResults.get(key).score()) {
                        vectorResults.put(key, r);
                    }
                }
            }

            // Step 3: Keyword search
            Map<String, QdrantService.SearchResult> keywordResults = new LinkedHashMap<>();
            try {
                List<QdrantService.SearchResult> kwResults = 
                    knowledgeBaseService.searchByKeyword(query, limit * 2);
                for (QdrantService.SearchResult r : kwResults) {
                    String key = extractFingerprint(r.content());
                    keywordResults.putIfAbsent(key, r);
                }
                System.out.println("    Keyword results: " + keywordResults.size());
            } catch (Exception e) {
                System.out.println("!!! [RAG] Keyword search failed (index may not exist): " + e.getMessage());
            }

            // Step 4: RRF (Reciprocal Rank Fusion)
            Map<String, Double> rrfScores = new HashMap<>();
            Map<String, QdrantService.SearchResult> allResults = new HashMap<>();

            // Rank vector results by score
            List<QdrantService.SearchResult> sortedVector = new ArrayList<>(vectorResults.values());
            sortedVector.sort((a, b) -> Double.compare(b.score(), a.score()));
            for (int rank = 0; rank < sortedVector.size(); rank++) {
                QdrantService.SearchResult r = sortedVector.get(rank);
                String key = extractFingerprint(r.content());
                rrfScores.merge(key, 1.0 / (60 + rank + 1), Double::sum);
                allResults.putIfAbsent(key, r);
            }

            // Rank keyword results by insertion order (no native score)
            List<QdrantService.SearchResult> sortedKeyword = new ArrayList<>(keywordResults.values());
            for (int rank = 0; rank < sortedKeyword.size(); rank++) {
                QdrantService.SearchResult r = sortedKeyword.get(rank);
                String key = extractFingerprint(r.content());
                rrfScores.merge(key, 1.0 / (60 + rank + 1), Double::sum);
                allResults.putIfAbsent(key, r);
            }

            // Step 5: Sort by RRF score, convert to SearchResultItem
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            List<SearchResultItem> finalResults = new ArrayList<>();
            for (int i = 0; i < Math.min(sorted.size(), limit); i++) {
                String key = sorted.get(i).getKey();
                double rrfScore = sorted.get(i).getValue();
                QdrantService.SearchResult r = allResults.get(key);
                
                String fileName = getMetaStr(r.metadata(), "fileName", "unknown");
                int pageNumber = getMetaInt(r.metadata(), "pageNumber", 0);
                String sectionTitle = getMetaStr(r.metadata(), "sectionTitle", "");

                finalResults.add(new SearchResultItem(
                    r.content(), rrfScore, fileName, pageNumber, sectionTitle, r.metadata()
                ));
            }

            System.out.println("=== [RAG] Hybrid search returned " + finalResults.size() + " results ===");
            return finalResults;
        } catch (Exception e) {
            System.out.println("!!! [RAG] Hybrid search error: " + e.getMessage());
            e.printStackTrace();
            // Fallback to basic vector search
            return search(query, limit, scoreThreshold);
        }
    }

    /**
     * Extract a short fingerprint from content for dedup key.
     */
    private String extractFingerprint(String content) {
        if (content == null || content.length() < 50) {
            return content != null ? content.trim() : "";
        }
        return content.substring(0, Math.min(200, content.length())).trim().toLowerCase();
    }

    // ==================== LLM Rerank ====================

    /**
     * Use LLM to rerank top candidates for better precision.
     * Sends top candidates to LLM and asks it to rank them by relevance to the query.
     */
    public List<SearchResultItem> rerankWithLLM(String query, List<SearchResultItem> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;

        System.out.println("=== [RAG] Reranking " + candidates.size() + " candidates with LLM ===");
        
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一个军用标准文档检索相关性判断专家。\n");
            prompt.append("请根据用户查询，对以下检索结果按照与查询的相关性从高到低排序。\n");
            prompt.append("只输出排序后的文档编号（用逗号分隔），不要任何解释。\n\n");
            prompt.append("用户查询: ").append(query).append("\n\n");
            
            for (int i = 0; i < candidates.size(); i++) {
                SearchResultItem c = candidates.get(i);
                String snippet = c.content();
                if (snippet.length() > 300) {
                    snippet = snippet.substring(0, 300) + "...";
                }
                prompt.append("[").append(i).append("] ").append(snippet).append("\n\n");
            }

            List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt.toString())
            );
            String response = llmClientService.chat(messages);
            
            // Parse ranking: expect comma-separated numbers like "3,0,5,1,2,4"
            List<Integer> ranking = new ArrayList<>();
            for (String token : response.split("[,\s]+")) {
                try {
                    int idx = Integer.parseInt(token.trim());
                    if (idx >= 0 && idx < candidates.size() && !ranking.contains(idx)) {
                        ranking.add(idx);
                    }
                } catch (NumberFormatException ignored) {}
            }

            List<SearchResultItem> reranked = new ArrayList<>();
            for (int idx : ranking) {
                if (reranked.size() >= topK) break;
                reranked.add(candidates.get(idx));
            }

            // Append remaining candidates if LLM ranking is incomplete
            for (int i = 0; i < candidates.size() && reranked.size() < topK; i++) {
                if (!ranking.contains(i)) {
                    reranked.add(candidates.get(i));
                }
            }

            System.out.println("=== [RAG] Rerank complete, returning top " + Math.min(topK, reranked.size()) + " ===");
            return reranked.subList(0, Math.min(topK, reranked.size()));
        } catch (Exception e) {
            System.out.println("!!! [RAG] Rerank failed, returning original: " + e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    /**
     * Full pipeline: query rewrite -> hybrid search -> LLM rerank.
     */
    public List<SearchResultItem> searchWithRerank(String query, int limit) {
        // Fetch more candidates for reranking
        List<SearchResultItem> candidates = searchHybrid(query, Math.max(limit * 3, 10));
        if (candidates.isEmpty()) return candidates;
        return rerankWithLLM(query, candidates, limit);
    }

    public String searchAsStringWithRerank(String query, int limit) {
        List<SearchResultItem> results = searchWithRerank(query, limit);

        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[Reference Knowledge Base Results (Reranked)]");
        String currentFile = "";
        String currentSection = "";

        for (SearchResultItem r : results) {
            if (!r.fileName().equals(currentFile)) {
                currentFile = r.fileName();
                sb.append("--- Source: ").append(currentFile).append(" ---");
            }
            if (r.sectionTitle() != null && !r.sectionTitle().isEmpty()
                && !r.sectionTitle().equals(currentSection)) {
                currentSection = r.sectionTitle();
                sb.append("  [Section: ").append(currentSection).append("]");
            }
            sb.append("  (Page ").append(r.pageNumber())
              .append(", Score: ").append(String.format("%.2f", r.score())).append(") ");
            String snippet = r.content();
            if (snippet.length() > 600) snippet = snippet.substring(0, 600) + "...";
            sb.append(snippet).append("\n\n");
        }
        return sb.toString().trim();
    }


    public String searchAsString(String query, int limit) {
        return searchAsString(query, limit, DEFAULT_SCORE_THRESHOLD);
    }

    public String searchAsString(String query, int limit, double scoreThreshold) {
        List<SearchResultItem> results = search(query, limit, scoreThreshold);

        if (results.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Reference Knowledge Base Results]\n");

        String currentFile = "";
        String currentSection = "";

        for (SearchResultItem r : results) {
            // Group by file
            if (!r.fileName().equals(currentFile)) {
                currentFile = r.fileName();
                sb.append("\n--- Source: ").append(currentFile).append(" ---\n");
            }

            // Show section heading if present and changed
            if (r.sectionTitle() != null && !r.sectionTitle().isEmpty()
                && !r.sectionTitle().equals(currentSection)) {
                currentSection = r.sectionTitle();
                sb.append("  [Section: ").append(currentSection).append("]\n");
            }

            // Add page reference
            sb.append("  (Page ").append(r.pageNumber())
              .append(", Score: ").append(String.format("%.2f", r.score())).append(") ");

            // Add content snippet
            String snippet = r.content();
            if (snippet.length() > 600) {
                snippet = snippet.substring(0, 600) + "...";
            }
            sb.append(snippet).append("\n");
        }

        return sb.toString().trim();
    }

/** Improved dedup with word-level n-gram Jaccard + per-section dedup + MMR diversity. */

    private List<SearchResultItem> deduplicate(List<SearchResultItem> items) {
        if (items.size() <= 1) return new ArrayList<>(items);

        // Step 1: Per-file-per-section dedup -- keep only best chunk per section
        Map<String, SearchResultItem> sectionBest = new LinkedHashMap<>();
        for (SearchResultItem item : items) {
            String sectionKey = item.fileName() + "::" + (item.sectionTitle() != null ? item.sectionTitle() : "");
            SearchResultItem existing = sectionBest.get(sectionKey);
            if (existing == null || item.score() > existing.score()) {
                sectionBest.put(sectionKey, item);
            }
        }

        // Step 2: Word-level Jaccard dedup on section-best candidates
        List<SearchResultItem> candidates = new ArrayList<>(sectionBest.values());
        List<SearchResultItem> result = new ArrayList<>();

        for (SearchResultItem item : candidates) {
            boolean isDuplicate = false;
            for (SearchResultItem existing : result) {
                if (isNearDuplicate(item.content(), existing.content())) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                result.add(item);
            }
        }

        // Step 3: MMR diversity re-ranking for top results
        if (result.size() > 5) {
            result = mmrDiversify(result, 0.7);
        }

        return result;
    }

    private boolean isNearDuplicate(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() > 30 && b.length() > 30) {
            String aCore = a.substring(0, Math.min(300, a.length()));
            String bCore = b.substring(0, Math.min(300, b.length()));
            if (aCore.contains(bCore) || bCore.contains(aCore)) return true;
        }
        Set<String> aPhrases = extractKeyPhrases(a);
        Set<String> bPhrases = extractKeyPhrases(b);
        if (aPhrases.isEmpty() && bPhrases.isEmpty()) return false;
        Set<String> intersection = new HashSet<>(aPhrases);
        intersection.retainAll(bPhrases);
        Set<String> union = new HashSet<>(aPhrases);
        union.addAll(bPhrases);
        return (double) intersection.size() / union.size() > 0.6;
    }

    private Set<String> extractKeyPhrases(String text) {
        Set<String> phrases = new HashSet<>();
        if (text == null || text.length() < 4) return phrases;
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                cjkRun.append(c);
            } else {
                if (cjkRun.length() >= 2) {
                    addNGrams(cjkRun.toString(), phrases, 2, 6);
                }
                cjkRun.setLength(0);
            }
        }
        if (cjkRun.length() >= 2) {
            addNGrams(cjkRun.toString(), phrases, 2, 6);
        }
        return phrases;
    }

    private void addNGrams(String s, Set<String> result, int minN, int maxN) {
        for (int n = minN; n <= Math.min(maxN, s.length()); n++) {
            for (int i = 0; i <= s.length() - n; i++) {
                result.add(s.substring(i, i + n));
            }
        }
    }

    private List<SearchResultItem> mmrDiversify(List<SearchResultItem> items, double lambda) {
        if (items.size() <= 1) return new ArrayList<>(items);
        List<SearchResultItem> selected = new ArrayList<>();
        List<SearchResultItem> remaining = new ArrayList<>(items);
        selected.add(remaining.remove(0));
        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            double bestMmr = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                SearchResultItem candidate = remaining.get(i);
                double maxSim = 0;
                for (SearchResultItem sel : selected) {
                    double sim = phraseSimilarity(candidate.content(), sel.content());
                    maxSim = Math.max(maxSim, sim);
                }
                double mmr = lambda * candidate.score() - (1 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestIdx = i;
                }
            }
            selected.add(remaining.remove(bestIdx));
        }
        return selected;
    }

    private double phraseSimilarity(String a, String b) {
        Set<String> pa = extractKeyPhrases(a);
        Set<String> pb = extractKeyPhrases(b);
        if (pa.isEmpty() || pb.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(pa);
        intersection.retainAll(pb);
        Set<String> union = new HashSet<>(pa);
        union.addAll(pb);
        return (double) intersection.size() / union.size();
    }

    private String getMetaStr(Map<String, Object> meta, String key, String defaultValue) {
        Object val = meta != null ? meta.get(key) : null;
        return val != null ? val.toString() : defaultValue;
    }

    private int getMetaInt(Map<String, Object> meta, String key, int defaultValue) {
        Object val = meta != null ? meta.get(key) : null;
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString()); } catch (Exception ignored) {}
        }
        return defaultValue;
    }
}
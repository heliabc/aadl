package com.nuaa.aadl.module.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 150;
    private static final int MIN_CHUNK_SIZE = 40;

    /**
     * Chunk with enriched metadata including section title detection.
     */
    public record Chunk(
        String content,
        int chunkIndex,
        int pageNumber,
        String source,
        String fileName,
        String sectionTitle
    ) {}

    // Pattern to detect section/chapter headings in Chinese documents
    // Matches: "第X章", "第X节", "X.", "X、", "（一）", "一、", etc.
    private static final Pattern SECTION_HEADING = Pattern.compile(
        "^(?:第[一二三四五六七八九十百千0-9]+[章节条]|" +
        "[0-9]+(?:\\.[0-9]+)*|" +
        "[（(][一二三四五六七八九十0-9]+[）)]|" +
        "[一二三四五六七八九十]+[、．.]|" +
        "[0-9]+[、．.])\\s*.*"
    );

    /**
     * Recursive semantic chunking with section detection.
     * Splits in priority: section boundaries -> paragraph breaks -> sentence breaks -> fixed size.
     */
    public List<Chunk> chunkByParagraphs(String text, int pageNumber, String source, String fileName) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Step 1: detect and extract section titles
        List<SectionBlock> sections = splitBySections(text);

        List<Chunk> allChunks = new ArrayList<>();
        int globalIndex = 0;
        String currentSectionTitle = "";

        for (SectionBlock section : sections) {
            if (section.isHeading) {
                currentSectionTitle = section.text;
                continue;
            }

            String sectionText = section.text;
            String activeTitle = currentSectionTitle;

            // Step 2: split section by paragraph boundaries
            String[] paragraphs = sectionText.split("\\n\\s*\\n");

            List<String> merged = new ArrayList<>();
            StringBuilder current = new StringBuilder();

            for (String para : paragraphs) {
                String trimmed = para.trim();
                if (trimmed.isEmpty()) continue;

                if (current.isEmpty()) {
                    current.append(trimmed);
                } else if (current.length() + trimmed.length() + 2 <= DEFAULT_CHUNK_SIZE) {
                    current.append("\n\n").append(trimmed);
                } else {
                    merged.add(current.toString());
                    current = new StringBuilder(trimmed);
                }
            }
            if (!current.isEmpty()) {
                merged.add(current.toString());
            }

            for (String block : merged) {
                if (isPureNoise(block)) continue;

                if (block.length() > DEFAULT_CHUNK_SIZE) {
                    List<Chunk> subChunks = chunkBySentenceBoundary(
                        block, pageNumber, source, fileName, activeTitle, globalIndex
                    );
                    allChunks.addAll(subChunks);
                    globalIndex = allChunks.size();
                } else if (block.length() >= MIN_CHUNK_SIZE) {
                    allChunks.add(new Chunk(block.trim(), globalIndex++, pageNumber, source, fileName, activeTitle));
                }
            }
        }

        return allChunks;
    }

    private List<Chunk> chunkBySentenceBoundary(
        String text, int pageNumber, String source, String fileName,
        String sectionTitle, int startIndex
    ) {
        List<Chunk> chunks = new ArrayList<>();
        int textLength = text.length();
        int chunkIndex = startIndex;
        int pos = 0;

        while (pos < textLength) {
            int end = Math.min(pos + DEFAULT_CHUNK_SIZE, textLength);

            if (end < textLength) {
                int splitPoint = findNaturalSplit(text, end, DEFAULT_CHUNK_OVERLAP);
                if (splitPoint > pos + MIN_CHUNK_SIZE) {
                    end = splitPoint;
                } else {
                    splitPoint = findNextSentenceEnd(text, end);
                    if (splitPoint > end && splitPoint <= end + 100) {
                        end = splitPoint;
                    }
                }
            }

            String chunkText = text.substring(pos, end).trim();
            if (!chunkText.isEmpty() && chunkText.length() >= MIN_CHUNK_SIZE && !isPureNoise(chunkText)) {
                chunks.add(new Chunk(chunkText, chunkIndex++, pageNumber, source, fileName, sectionTitle));
            }

            if (end >= textLength) break;

            int nextStart = end - DEFAULT_CHUNK_OVERLAP;
            if (nextStart <= pos) {
                nextStart = pos + 1;
            }
            nextStart = findNextSentenceStart(text, nextStart);
            pos = Math.max(pos + 1, nextStart);
        }

        return chunks;
    }

    private int findNaturalSplit(String text, int fromPos, int lookBack) {
        int start = Math.max(fromPos - lookBack, 0);

        // Priority 1: paragraph break (double newline)
        for (int i = fromPos - 1; i >= start; i--) {
            if (i > 0 && text.charAt(i) == '\n' && text.charAt(i - 1) == '\n') {
                return i + 1;
            }
        }

        // Priority 2: Chinese sentence-ending punctuation
        for (int i = fromPos - 1; i >= start; i--) {
            char c = text.charAt(i);
            if (c == '\u3002' || c == '\uFF01' || c == '\uFF1F' ||
                c == '?' || c == '!' || c == '\n') {
                return i + 1;
            }
        }

        // Priority 3: Chinese/English comma or semicolon
        for (int i = fromPos - 1; i >= start; i--) {
            char c = text.charAt(i);
            if (c == '\uFF0C' || c == '\uFF1B' || c == '\uFF1A' ||
                c == ',' || c == ';' || c == ':') {
                return i + 1;
            }
        }

        return -1;
    }

    private int findNextSentenceEnd(String text, int fromPos) {
        int limit = Math.min(fromPos + 100, text.length());
        for (int i = fromPos; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '\u3002' || c == '\uFF01' || c == '\uFF1F' ||
                c == '?' || c == '!' || c == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    private int findNextSentenceStart(String text, int fromPos) {
        int limit = Math.min(fromPos + 30, text.length());
        for (int i = fromPos; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '\n') continue;
            if (!Character.isWhitespace(c)) {
                return i;
            }
        }
        return fromPos;
    }

    private List<SectionBlock> splitBySections(String text) {
        List<SectionBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\\n");

        StringBuilder currentBlock = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!currentBlock.isEmpty()) {
                    currentBlock.append("\n");
                }
                continue;
            }

            if (isSectionHeading(trimmed)) {
                if (!currentBlock.isEmpty()) {
                    blocks.add(new SectionBlock(currentBlock.toString().trim(), false));
                    currentBlock = new StringBuilder();
                }
                blocks.add(new SectionBlock(trimmed, true));
            } else {
                if (!currentBlock.isEmpty()) {
                    currentBlock.append("\n");
                }
                currentBlock.append(trimmed);
            }
        }

        if (!currentBlock.isEmpty()) {
            blocks.add(new SectionBlock(currentBlock.toString().trim(), false));
        }

        if (blocks.isEmpty()) {
            blocks.add(new SectionBlock(text, false));
        }

        return blocks;
    }

    private boolean isSectionHeading(String line) {
        if (line.length() > 80) return false;
        return SECTION_HEADING.matcher(line).matches();
    }

    private record SectionBlock(String text, boolean isHeading) {}

    public List<Chunk> chunkPdfPages(List<PdfParserService.ParsedPage> pages, String source, String fileName) {
        List<Chunk> allChunks = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        for (PdfParserService.ParsedPage page : pages) {
            if (!fullText.isEmpty()) {
                fullText.append("\n\n");
            }
            fullText.append(page.content());
        }
        List<Chunk> chunks = chunkByParagraphs(fullText.toString(), 0, source, fileName);
        for (Chunk chunk : chunks) {
            String c = chunk.content();
            int bestPage = 0;
            for (PdfParserService.ParsedPage page : pages) {
                String pageContent = page.content();
                if (pageContent.contains(c.substring(0, Math.min(100, c.length())))) {
                    bestPage = page.pageNumber();
                    break;
                }
            }
            allChunks.add(new Chunk(
                chunk.content(), chunk.chunkIndex(), bestPage,
                chunk.source(), chunk.fileName(), chunk.sectionTitle()
            ));
        }
        return allChunks.isEmpty() ? chunks : allChunks;
    }

    public List<Chunk> chunkTextFile(String content, String source, String fileName) {
        return chunkByParagraphs(content, 0, source, fileName);
    }

    private boolean isPureNoise(String text) {
        if (text == null || text.length() < MIN_CHUNK_SIZE) return true;

        int meaningfulChars = 0;
        int total = text.length();

        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || isCJKCharacter(c)) {
                meaningfulChars++;
            }
        }

        return (double) meaningfulChars / total < 0.3;
    }

    private boolean isCJKCharacter(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
               block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
               block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
               block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
               block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }
}

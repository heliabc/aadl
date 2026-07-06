package com.nuaa.aadl.module.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nuaa.aadl.app.config.AppProperties;
import com.nuaa.aadl.module.rag.langchain.OllamaClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;

@Service
public class KnowledgeBaseService {

    private final AppProperties appProperties;
    private final ChunkingService chunkingService;
    private final QdrantService qdrantService;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;
    private final Path metaFile;

    public KnowledgeBaseService(
            AppProperties appProperties,
            ChunkingService chunkingService,
            QdrantService qdrantService,
            OllamaClient ollamaClient) {
        this.appProperties = appProperties;
        this.chunkingService = chunkingService;
        this.qdrantService = qdrantService;
        this.ollamaClient = ollamaClient;
        this.objectMapper = new ObjectMapper();
        
        // 元数据文件路径: data/knowledge-meta.json
        String dataDir = appProperties.dataDir();
        this.metaFile = Path.of(dataDir, "knowledge-meta.json");
    }

    public void resetKnowledgeBase() throws Exception {
        System.out.println("=== [KnowledgeBaseService] Resetting knowledge base ===");
        try {
            Files.deleteIfExists(metaFile);
            System.out.println("=== [KnowledgeBaseService] Meta file deleted ===");
        } catch (Exception e) {
            System.out.println("!!! [KnowledgeBaseService] Failed to delete meta file: " + e.getMessage());
        }
        qdrantService.recreateCollection();
        initBuiltinKnowledgeBase();
    }

    public void initBuiltinKnowledgeBase() throws Exception {
        // 加载上次的索引元数据（文件路径 → 修改时间）
        Map<String, Long> lastIndexed = loadMeta();
        System.out.println("=== [KnowledgeBaseService] 加载元数据: " + lastIndexed.size() + " 条 ===");
        
        String builtinDir = appProperties.knowledgeBaseDir();
        System.out.println("=== [KnowledgeBaseService] 知识库目录: " + builtinDir + " ===");
        
        if (builtinDir == null || builtinDir.isBlank()) {
            return;
        }
        
        File dir = new File(builtinDir);
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("!!! [KnowledgeBaseService] 目录不存在: " + builtinDir);
            return;
        }
        
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("=== [KnowledgeBaseService] 目录为空 ===");
            return;
        }
        
        System.out.println("=== [KnowledgeBaseService] 发现 " + files.length + " 个文件 ===");
        
        boolean anyUpdated = false;
        
        for (File file : files) {
            if (file.isFile()) {
                FileTime time = Files.getLastModifiedTime(file.toPath());
                String key = file.getAbsolutePath();
                Long prevTime = lastIndexed.get(key);
                
                // 文件未变化，跳过
                if (prevTime != null && prevTime.longValue() == time.toMillis()) {
                    System.out.println("=== [KnowledgeBaseService] 文件未变化，跳过: " + file.getName() + " ===");
                    continue;
                }
                
                // 文件有变化或新文件，先删除旧索引，再重新嵌入
                if (prevTime != null) {
                    System.out.println("=== [KnowledgeBaseService] 文件已更新，重新嵌入: " + file.getName() + " ===");
                    qdrantService.deleteByFileName(file.getName());
                } else {
                    System.out.println("=== [KnowledgeBaseService] 新文件，嵌入: " + file.getName() + " ===");
                }
                
                ingestFile(file);
                lastIndexed.put(key, time.toMillis());
                anyUpdated = true;
            }
        }
        
        // 如果有更新，保存元数据
        if (anyUpdated) {
            saveMeta(lastIndexed);
        } else {
            System.out.println("=== [KnowledgeBaseService] 所有文件未变化，跳���嵌入 ===");
        }
    }
    
    private Map<String, Long> loadMeta() {
        try {
            if (Files.exists(metaFile)) {
                return objectMapper.readValue(metaFile.toFile(), new TypeReference<Map<String, Long>>() {});
            }
        } catch (Exception e) {
            System.out.println("!!! [KnowledgeBaseService] 加载元数据失败: " + e.getMessage());
        }
        return new HashMap<>();
    }
    
    private void saveMeta(Map<String, Long> meta) {
        try {
            Files.createDirectories(metaFile.getParent());
            objectMapper.writeValue(metaFile.toFile(), meta);
            System.out.println("=== [KnowledgeBaseService] 元数据已保存 ===");
        } catch (Exception e) {
            System.out.println("!!! [KnowledgeBaseService] 保存元数据失败: " + e.getMessage());
        }
    }

    public void ingestUserFile(MultipartFile uploadedFile) throws Exception {
        String fileName = uploadedFile.getOriginalFilename();
        if (fileName == null) {
            return;
        }
        
        String extension = getFileExtension(fileName).toLowerCase();
        System.out.println("=== [KnowledgeBaseService] Processing file: " + fileName + " (extension: " + extension + ") ===");
        
        String content;
        if (extension.equals("pdf")) {
            Path tempFile = Files.createTempFile("upload_", "." + extension);
            uploadedFile.transferTo(tempFile.toFile());
            content = new PdfParserService().parsePdfToText(tempFile.toFile());
            Files.deleteIfExists(tempFile);
        } else if (extension.equals("docx") || extension.equals("doc")) {
            Path tempFile = Files.createTempFile("upload_", "." + extension);
            uploadedFile.transferTo(tempFile.toFile());
            content = new DocumentParserService().parseDocx(tempFile.toFile());
            Files.deleteIfExists(tempFile);
        } else {
            Path tempFile = Files.createTempFile("upload_", "." + extension);
            uploadedFile.transferTo(tempFile.toFile());
            content = new TextParserService().parseTextFile(tempFile.toFile());
            Files.deleteIfExists(tempFile);
        }
        
        if (content == null || content.isBlank()) {
            System.out.println("!!! [KnowledgeBaseService] No content extracted from: " + fileName);
            return;
        }
        
        ingestContent(fileName, content, "uploaded");
    }

    private void ingestFile(File file) throws Exception {
        String fileName = file.getName();
        String extension = getFileExtension(fileName).toLowerCase();
        
        System.out.println("=== [KnowledgeBaseService] Processing file: " + fileName + " (extension: " + extension + ") ===");
        
        String content;
        if (extension.equals("pdf")) {
            content = new PdfParserService().parsePdfToText(file);
        } else if (extension.equals("docx") || extension.equals("doc")) {
            content = new DocumentParserService().parseDocx(file);
        } else if (extension.equals("xlsx") || extension.equals("xls")) {
            content = new DocumentParserService().parseXlsx(file);
        } else {
            content = new TextParserService().parseTextFile(file);
        }
        
        if (content == null || content.isBlank()) {
            System.out.println("!!! [KnowledgeBaseService] No content extracted from: " + fileName);
            return;
        }
        
        ingestContent(fileName, content, "builtin");
    }

    private void ingestContent(String fileName, String content, String source) throws Exception {
        List<ChunkingService.Chunk> chunks = chunkingService.chunkTextFile(content, source, fileName);
        System.out.println("=== [KnowledgeBaseService] Embedding " + chunks.size() + " texts for: " + fileName + " ===");
        
        List<QdrantService.ChunkItem> chunkItems = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            ChunkingService.Chunk chunk = chunks.get(i);
            try {
                System.out.println("    Embedding " + i + ": " + chunk.content().substring(0, Math.min(30, chunk.content().length())) + "...");
                float[] vector = ollamaClient.embed(chunk.content());
                vectors.add(vector);
                System.out.println("    Embedded " + i + " OK, dim=" + vector.length);
                
                QdrantService.ChunkItem item = new QdrantService.ChunkItem(
                    UUID.randomUUID().toString(),
                    chunk.content(),
                    chunk.chunkIndex(),
                    chunk.pageNumber(),
                    source,
                    fileName,
                    "",
                    getFileExtension(fileName),
                    chunk.sectionTitle() != null ? chunk.sectionTitle() : ""
                );
                chunkItems.add(item);
            } catch (Exception e) {
                System.out.println("!!! Error embedding chunk " + i + " of " + fileName + ": " + e.getMessage());
            }
        }
        
        if (!chunkItems.isEmpty()) {
            qdrantService.upsertBatch(chunkItems, vectors);
        }
        System.out.println("=== [KnowledgeBaseService] Indexed " + chunkItems.size() + "/" + chunks.size() + " chunks from: " + fileName + " ===");
    }

    public List<QdrantService.SearchResult> searchByVector(float[] queryVector, int limit) throws Exception {
        return qdrantService.searchByVector(queryVector, limit);
    }
    public List<QdrantService.SearchResult> searchByKeyword(String query, int limit) throws Exception {
        return qdrantService.searchByKeyword(query, limit);
    }


    public void deleteUserFile(String fileId) throws Exception {
        qdrantService.deleteByFileId(fileId);
    }

    public long count() throws Exception {
        return qdrantService.count();
    }

    public List<String> listIndexedFiles() throws Exception {
        return qdrantService.listIndexedFiles();
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }
}
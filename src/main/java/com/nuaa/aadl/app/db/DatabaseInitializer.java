package com.nuaa.aadl.app.db;

import com.nuaa.aadl.app.config.AppProperties;
import com.nuaa.aadl.module.rag.service.KnowledgeBaseService;
import com.nuaa.aadl.module.rag.service.QdrantService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DatabaseInitializer {

  private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

  private final JdbcTemplate jdbcTemplate;
  private final AppProperties appProperties;
  private final QdrantService qdrantService;
  private final KnowledgeBaseService knowledgeBaseService;

  public DatabaseInitializer(JdbcTemplate jdbcTemplate, AppProperties appProperties, QdrantService qdrantService, KnowledgeBaseService knowledgeBaseService) {
    this.jdbcTemplate = jdbcTemplate;
    this.appProperties = appProperties;
    this.qdrantService = qdrantService;
    this.knowledgeBaseService = knowledgeBaseService;
  }

  @PostConstruct
  void init() throws IOException {
    Files.createDirectories(Path.of(appProperties.dataDir()));
    Files.createDirectories(Path.of(appProperties.uploadDir()));
    Files.createDirectories(Path.of(appProperties.knowledgeBaseDir()));
    Files.createDirectories(Path.of(appProperties.userKnowledgeDir()));

    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS files (
          id TEXT PRIMARY KEY,
          original_name TEXT NOT NULL,
          stored_name TEXT NOT NULL,
          mime_type TEXT,
          size INTEGER NOT NULL,
          created_at TEXT NOT NULL
        )
        """);

    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS module_docs (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          conversation_id TEXT NOT NULL,
          module TEXT NOT NULL,
          doc_type TEXT NOT NULL,
          payload_json TEXT NOT NULL,
          version INTEGER NOT NULL DEFAULT 1,
          updated_at TEXT NOT NULL,
          UNIQUE(conversation_id, module, doc_type)
        )
        """);

    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          conversation_id TEXT NOT NULL,
          role TEXT NOT NULL,
          module TEXT,
          content TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """);

    try {
      qdrantService.createCollectionIfNotExists();
      log.info("Qdrant collection created/verified");
    } catch (Exception e) {
      log.warn("Failed to create Qdrant collection: {}", e.getMessage());
    }

    try {
      knowledgeBaseService.initBuiltinKnowledgeBase();
      long count = knowledgeBaseService.count();
      log.info("Knowledge base initialized with {} chunks", count);
    } catch (Exception e) {
      log.warn("Failed to initialize knowledge base: {}", e.getMessage());
    }
  }
}

package com.nuaa.aadl.shared.doc;

import com.nuaa.aadl.util.TimeUtils;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;


// 作用是 提供对模块文档的读写操作，包括查询和更新模块文档。
@Repository
public class ModuleDocRepository {

  private final JdbcTemplate jdbcTemplate;

  public ModuleDocRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<ModuleDocRecord> readModuleDoc(String conversationId, String module, String docType) {
    return jdbcTemplate.query(
        "SELECT payload_json, version, updated_at FROM module_docs WHERE conversation_id = ? AND module = ? AND doc_type = ?",
        (rs, rowNum) -> new ModuleDocRecord(
            rs.getString("payload_json"),
            rs.getInt("version"),
            rs.getString("updated_at")
        ),
        conversationId,
        module,
        docType
    ).stream().findFirst();
  }

  public Optional<ModuleDocRecord> read(String conversationId, String module, String docType) {
    return readModuleDoc(conversationId, module, docType);
  }

  public ModuleDocWriteResult writeModuleDoc(String conversationId, String module, String docType, String payloadJson) {
    return write(conversationId, module, docType, payloadJson, null);
  }

  public ModuleDocWriteResult write(
      String conversationId,
      String module,
      String docType,
      String payloadJson,
      Integer expectedVersion
  ) {
    String now = TimeUtils.utcNow();
    Optional<ModuleDocRecord> existing = readModuleDoc(conversationId, module, docType);

    if (existing.isPresent()) {
      int currentVersion = existing.get().version();
      if (expectedVersion != null && expectedVersion != currentVersion) {
        throw new RuntimeException("Version conflict: expected " + expectedVersion + ", got " + currentVersion);
      }
      int nextVersion = existing.get().version() + 1;
      jdbcTemplate.update(
          "UPDATE module_docs SET payload_json = ?, version = ?, updated_at = ? WHERE conversation_id = ? AND module = ? AND doc_type = ?",
          payloadJson,
          nextVersion,
          now,
          conversationId,
          module,
          docType
      );
      return new ModuleDocWriteResult(nextVersion, now);
    }

    jdbcTemplate.update(
        "INSERT INTO module_docs (conversation_id, module, doc_type, payload_json, version, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
        conversationId,
        module,
        docType,
        payloadJson,
        1,
        now
    );
    return new ModuleDocWriteResult(1, now);
  }
}

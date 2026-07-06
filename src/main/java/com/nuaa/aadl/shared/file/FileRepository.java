package com.nuaa.aadl.shared.file;

import com.nuaa.aadl.util.TimeUtils;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class FileRepository {

  private static final RowMapper<StoredFileRecord> ROW_MAPPER = (rs, rowNum) -> new StoredFileRecord(
      rs.getString("id"),
      rs.getString("original_name"),
      rs.getString("stored_name"),
      rs.getString("mime_type"),
      rs.getLong("size"),
      rs.getString("created_at")
  );

  private final JdbcTemplate jdbcTemplate;

  public FileRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void upsertFileRecord(String fileId, String originalName, String storedName, String mimeType, long size) {
    jdbcTemplate.update(
        """
        INSERT INTO files (id, original_name, stored_name, mime_type, size, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          original_name = excluded.original_name,
          stored_name = excluded.stored_name,
          mime_type = excluded.mime_type,
          size = excluded.size
        """,
        fileId,
        originalName,
        storedName,
        mimeType,
        size,
        TimeUtils.utcNow()
    );
  }

  public List<StoredFileRecord> getFilesByIds(List<String> fileIds) {
    if (fileIds == null || fileIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(fileIds.size(), "?"));
    return jdbcTemplate.query(
        "SELECT id, original_name, stored_name, mime_type, size, created_at FROM files WHERE id IN (" + placeholders + ")",
        ROW_MAPPER,
        fileIds.toArray()
    );
  }
}

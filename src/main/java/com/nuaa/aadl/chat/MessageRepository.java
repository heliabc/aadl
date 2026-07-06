package com.nuaa.aadl.chat;

import com.nuaa.aadl.util.TimeUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

@Repository
public class MessageRepository {

  private final JdbcTemplate jdbcTemplate;

  public MessageRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void appendMessage(String conversationId, String role, String module, String content) {
    jdbcTemplate.update(
        "INSERT INTO messages (conversation_id, role, module, content, created_at) VALUES (?, ?, ?, ?, ?)",
        conversationId,
        role,
        module,
        content,
        TimeUtils.utcNow()
    );
  }

  public List<MessageRecord> getMessages(String conversationId) {
    return jdbcTemplate.query(
        "SELECT role, module, content FROM messages WHERE conversation_id = ? ORDER BY id ASC",
        (rs, rowNum) -> new MessageRecord(rs.getString("role"), rs.getString("module"), rs.getString("content")),
        conversationId
    );
  }

  public List<MessageRecord> getRecentMessages(String conversationId, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    List<MessageRecord> newestFirst = jdbcTemplate.query(
        "SELECT role, module, content FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
        (rs, rowNum) -> new MessageRecord(rs.getString("role"), rs.getString("module"), rs.getString("content")),
        conversationId,
        limit
    );
    Collections.reverse(newestFirst);
    return newestFirst;
  }

  public void clearMessages(String conversationId) {
    jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
  }

  public record MessageRecord(String role, String module, String content) {}
}

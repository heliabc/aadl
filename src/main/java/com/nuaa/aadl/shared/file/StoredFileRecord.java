package com.nuaa.aadl.shared.file;

public record StoredFileRecord(
    String id,
    String originalName,
    String storedName,
    String mimeType,
    long size,
    String createdAt
) {
}

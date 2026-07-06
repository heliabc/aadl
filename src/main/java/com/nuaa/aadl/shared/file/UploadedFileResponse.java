package com.nuaa.aadl.shared.file;

public record UploadedFileResponse(
    String id,
    String name,
    long size,
    String mime
) {
}
